// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hgtv.star.core.AppUpdate
import com.hgtv.star.core.AppUpdateRepository
import com.hgtv.star.core.UpdateFiles
import com.hgtv.star.core.UpdatePrompt
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.tvColumn
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvText
import com.hgtv.star.BuildConfig

/**
 * 应用内更新。
 *
 * 行为（按需求）：
 * - **进入应用时提示**：前台空闲（不在播放界面、没有别的弹窗）时检查一次新版，
 *   有新版就弹「是否更新」窗口；用户点「更新」才下载，下好交给系统安装器覆盖安装。
 * - **暂不更新就安静**：选过一次「暂不更新」后不再主动提示，直到设备上装上了新版本
 *   （`versionCode` 变了）才重新开始提示；设置里的手动检查始终可用。
 * - **播放中不打扰**：`screen == "player"` 或有弹窗时不发请求、不弹窗。
 * - **两道校验**：下载后先比 SHA-256，安装前再比「包名 / 版本 / 最低系统版本 / 签名」。
 */
class AppUpdater(private val activity: Activity, private val idle: () -> Boolean) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = AppUpdateRepository()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    private val apk = File(activity.cacheDir, "updates/update.apk")

    /** 自动检查的最小间隔：反复切前后台时不重复请求。 */
    private val autoIntervalMs = 10 * 60 * 1000L
    /** 检查 / 下载失败后的退避时间。 */
    private val failureBackoffMs = 30 * 60 * 1000L

    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    private var available: AppUpdate? = null
    private var ready = false
    private var permissionRequested = false
    private var retryAfter = 0L
    /** 本次前台已经提示过的版本，避免同一个版本反复弹窗。 */
    private var promptedCode = 0L
    private var status = "当前版本 ${BuildConfig.VERSION_NAME}"

    private var dialog: TvDialog? = null
    private var statusView: TextView? = null
    private var actionButton: TvButton? = null
    private var anchor: View? = null

    val showing get() = dialog?.isShowing == true

    // —— 「暂不更新」的持久状态 ——

    /** 「暂不更新」的记忆；规则与边界由 core 的 [UpdatePrompt] 定义，这里只负责存取。 */
    private fun promptState() = UpdatePrompt(
        declined = preferences.getBoolean("declined", false),
        declinedAt = preferences.getLong("declined_at", -1L)
    )

    private fun save(state: UpdatePrompt) {
        preferences.edit()
            .putBoolean("declined", state.declined)
            .putLong("declined_at", state.declinedAt)
            .apply()
    }

    /** 用户选过「暂不更新」：装上新版本之前不再主动提示。 */
    private fun declined() = promptState().suppressed(BuildConfig.VERSION_CODE.toLong())

    private fun decline() = save(promptState().decline(BuildConfig.VERSION_CODE.toLong()))

    /** 已经装上新版本 → 清掉「暂不更新」，重新开始提示。 */
    private fun refreshDeclineState() = save(promptState().refresh(BuildConfig.VERSION_CODE.toLong()))

    // —— 生命周期 ——

    /**
     * 前台轮询：每 15 秒看一次，满足「空闲 + 没有弹窗 + 未选暂不更新」才动。
     * 已下载好的包先提示安装；否则距上次检查超过 [autoIntervalMs] 就再查一次。
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (idle() && dialog == null && !busy && !declined()) {
                val now = System.currentTimeMillis()
                val update = available
                when {
                    ready && update != null && promptedCode != update.versionCode -> {
                        promptedCode = update.versionCode
                        promptInstall(update)
                    }
                    now >= retryAfter &&
                        now - preferences.getLong("last_attempt", 0) >= autoIntervalMs -> check(manual = false)
                }
            }
            main.postDelayed(this, 15000)
        }
    }

    fun resume() {
        if(BuildConfig.APPLICATION_ID.endsWith(".projector")) return
        active = true
        refreshDeclineState()
        main.removeCallbacks(poll)
        main.postDelayed(poll, 8000)
        if (permissionRequested) {
            permissionRequested = false
            if (activity.packageManager.canRequestPackageInstalls()) install()
            else {
                status = "安装权限未开启，可在设置里再次安装"
                openStatus(anchor, "关闭", startDownload = false)
            }
        }
    }

    fun pause() {
        active = false
        main.removeCallbacks(poll)
        interrupt()
        dialog?.dismiss()
    }

    fun interrupt() {
        generation++
        task?.cancel(true)
        task = null
        repository.cancel()
        busy = false
    }

    fun destroy() {
        pause()
        worker.shutdownNow()
        // TLS 连接的关闭可能发 close_notify，不能放在主线程上做。
        Thread({ repository.close() }, "hgtv-update-cleanup").start()
    }

    // —— 弹窗 ——

    /** 设置 → 「版本与更新」：手动检查 / 下载 / 安装。 */
    fun show(from: View?) {
        if (!active) return
        openStatus(from, "关闭", startDownload = false)
    }

    /** 进入应用时发现新版：先问「是否更新」，用户点了才下载。 */
    private fun prompt(update: AppUpdate) {
        if (!active || declined()) return
        dialog?.dismiss()
        val next = TvDialog(activity, "发现新版本")
            .message("${update.versionName} · ${"%.1f".format(update.size / 1048576.0)} MB")
            .message(update.notes.ifBlank { "新版已发布。" })
            .message("更新由系统安装器覆盖安装，收藏与观看记录保留。", 14f, TvStyle.muted)
        next.button("暂不更新") { decline() }
        next.button("更新") { openStatus(null, "取消", startDownload = true) }
        dialog = next
        next.show(activity.currentFocus) { if (dialog === next) dialog = null }
    }

    /** 上次已经下载校验好、这次进应用才发现：直接问是否安装。 */
    private fun promptInstall(update: AppUpdate) {
        if (!active || declined()) return
        dialog?.dismiss()
        val next = TvDialog(activity, "已下载新版本")
            .message("${update.versionName} 已下载并通过校验，现在安装？")
        next.button("暂不更新") { decline() }
        next.button("安装") { install() }
        dialog = next
        next.show(activity.currentFocus) { if (dialog === next) dialog = null }
    }

    /**
     * 进度 / 手动检查共用的状态弹窗：一行状态文字 + 一个动作按钮。
     * 动作按钮随状态在「检查更新 / 下载新版 / 安装新版」之间切换。
     */
    private fun openStatus(from: View?, negative: String, startDownload: Boolean) {
        dialog?.dismiss()
        anchor = from
        val body = activity.tvColumn()
        statusView = activity.tvText(status, 16f, TvStyle.text).apply {
            setLineSpacing(activity.tvDp(TvStyle.GAP_TIGHT).toFloat(), 1f)
        }
        body.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        body.addView(
            activity.tvText("只在应用前台浏览时检查，播放中不打扰。", 13f, TvStyle.muted)
                .apply { setPadding(0, activity.tvDp(TvStyle.GAP_TIGHT), 0, 0) },
            LinearLayout.LayoutParams(-1, -2)
        )
        val next = TvDialog(activity, "版本与更新 · ${BuildConfig.VERSION_NAME}").apply {
            content.addView(body, LinearLayout.LayoutParams(-1, -2))
            button(negative) { if (busy) interrupt() }
            actionButton = button("检查更新", keepOpen = true) {
                when {
                    ready -> install()
                    available != null -> download()
                    else -> check(manual = true)
                }
            }
        }
        dialog = next
        renderActions()
        next.show(from) { if (dialog === next) { dialog = null; statusView = null; actionButton = null } }
        if (startDownload) download()
    }

    private fun updateStatus(text: String) {
        status = text
        statusView?.text = text
    }

    private fun renderActions() {
        actionButton?.text = when {
            busy -> "处理中…"
            ready -> "安装新版"
            available != null -> "下载新版"
            else -> "检查更新"
        }
    }

    // —— 后台任务 ——

    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true
        renderActions()
        val ticket = ++generation
        task = worker.submit {
            try {
                val result = action()
                main.post { if (active && generation == ticket) { busy = false; done(result); renderActions() } }
            } catch (e: Exception) {
                main.post {
                    if (active && generation == ticket) {
                        busy = false
                        ready = false
                        retryAfter = System.currentTimeMillis() + failureBackoffMs
                        updateStatus(if (e is IOException) e.message ?: "更新失败，请重试" else "更新校验失败，请重试")
                        renderActions()
                    }
                }
            }
        }
    }

    /** 检查新版。[manual] 为用户在设置里主动点击。 */
    fun check(manual: Boolean) {
        if(BuildConfig.APPLICATION_ID.endsWith(".projector")) { updateStatus("投影定制版"); return }
        if (busy || !active || !idle()) return
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…")
        work({ repository.latest() }) { update ->
            val found = update.takeIf { it.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT) }
            available = found
            ready = false
            if (found == null) {
                updateStatus(
                    when {
                        update.versionCode > BuildConfig.VERSION_CODE && update.minSdk > Build.VERSION.SDK_INT ->
                            "新版 ${update.versionName} 需要 Android API ${update.minSdk}，当前电视继续使用本版"
                        else -> "已是最新版本 ${BuildConfig.VERSION_NAME}"
                    }
                )
                worker.execute { apk.delete() }
            } else {
                updateStatus("发现 ${found.versionName} · ${"%.1f".format(found.size / 1048576.0)} MB\n${found.notes}")
                // 手动：留在弹窗里等用户按「下载新版」；自动：直接问是否更新。
                if (!manual) {
                    promptedCode = found.versionCode
                    prompt(found)
                }
            }
        }
    }

    private fun download() {
        val update = available ?: return
        if (busy || !active || !idle()) return
        updateStatus("正在下载 ${update.versionName}…")
        val ticket = generation + 1
        work({
            repository.download(update, apk) { percent ->
                main.post { if (generation == ticket && active) updateStatus("正在下载 ${update.versionName} · $percent%") }
            }
            validateApk(update)
        }) {
            ready = true
            dialog?.dismiss()
            install()
        }
    }

    /**
     * 安装前的最后一道闸：哈希、包名、版本、最低系统版本、签名，五项全对才放行。
     * 签名比对用「已安装包的签名 == 候选包的签名」，因此换过证书的包会被挡住。
     */
    @Suppress("DEPRECATION")
    private fun validateApk(update: AppUpdate) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("安装包无法识别")
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { it.toCharsString() }?.toSet().orEmpty()
        if (candidate.packageName != activity.packageName ||
            version != update.versionCode ||
            candidate.versionName != update.versionName ||
            version <= BuildConfig.VERSION_CODE ||
            candidate.applicationInfo?.minSdkVersion != update.minSdk ||
            signers(installed).isEmpty() ||
            signers(installed) != signers(candidate)
        ) throw IOException("新版包名、版本或签名不匹配，已阻止安装")
    }

    private fun install() {
        val update = available ?: return
        if (busy || !active || !ready) return
        work({ validateApk(update) }) {
            try {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    permissionRequested = true
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                } else {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .apply { clipData = ClipData.newRawUri("更新安装包", uri) }
                    )
                }
            } catch (_: Exception) {
                permissionRequested = false
                updateStatus("电视未提供安装或授权入口，请通过系统文件管理器安装新版")
                openStatus(anchor, "关闭", startDownload = false)
            }
        }
    }
}
