// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hgtv.star.core.*
import java.util.concurrent.Executors
import com.hgtv.star.player.EpisodeGrid
import com.hgtv.star.player.EpisodePanel
import com.hgtv.star.player.PlaybackMenu
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.data.WatchProgress

// 本文件是从 MainActivity.kt 拆出来的「播放菜单与各类选择器（清晰度 / 倍速 / 画面 / 弹幕 / 定时）、进度保存」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

// media3 的 `PlayerView` / `DefaultLoadControl` 这些成员标了 `@UnstableApi`，lint 的
// `UnsafeOptInUsageError` 要求使用处显式 opt-in（行为不变，只是把「我知道这是不稳定 API」写在代码上）。
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun MainActivity.showPlaybackMenu(focusRow: Int=0,focusChoice: Int=-1) {
    if(screen!="player" || playError) return
    dismissPlaybackMenu()
    // 先建面板再逐行追加：选项的点击回调要引用面板本身，不能在构造链里读还没赋值的变量。
    val menu=PlaybackMenu(this)
    // 隐藏弹幕时整层不画、也不发请求，字号与透明度改完看不到任何变化——那两行直接不添加，
    // 面板从「弹幕」直接跳到「定时」。判定与设置页同一条规则（[DanmakuMode.showsStyle]）。
    val showsStyle=library.danmakuMode.showsStyle
    menu
        .addRow("清晰度",PlaybackQuality.options.mapIndexed { index,value ->
            PlaybackMenu.Choice("${value}P",value==library.maxQuality) {
                val changed=library.maxQuality!=value
                library.maxQuality=value
                // 换清晰度要重新解析播放地址，播放器复用后菜单需要按新状态重建。
                if(changed) { playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay); showPlaybackMenu(MENU_ROW_QUALITY,index) }
                else { menu.select(MENU_ROW_QUALITY,index); updatePlaybackText() }
            }
        })
        .addRow("倍速",PlaybackSpeed.options.mapIndexed { index,value ->
            PlaybackMenu.Choice(PlaybackSpeed.label(value),value==library.playbackSpeed) {
                library.playbackSpeed=value; player?.setPlaybackSpeed(value)
                menu.select(MENU_ROW_SPEED,index); updatePlaybackText()
            }
        })
        .addRow("画面模式",VideoFrameMode.entries.mapIndexed { index,mode ->
            PlaybackMenu.Choice(mode.label,mode==library.frameMode) {
                library.frameMode=mode; playerView?.resizeMode=frameResizeMode()
                menu.select(MENU_ROW_FRAME,index); updatePlaybackText()
            }
        })
        // 弹幕显示方式：全屏 / 上方 / 隐藏。选完立刻作用在画面上的弹幕层，并存进本机设置。
        .addRow("弹幕",DanmakuMode.entries.mapIndexed { index,mode ->
            PlaybackMenu.Choice(mode.label,mode==library.danmakuMode) {
                val previous=library.danmakuMode
                library.danmakuMode=mode
                danmakuView?.mode=mode
                // 隐藏档不发请求：从隐藏切回可见时如果这一集还没取过，这里补一次；
                // 已经取过的（raw 还在）只换画法，由 mode 的 setter 重排轨道，不再请求。
                if(previous==DanmakuMode.HIDDEN && mode!=DanmakuMode.HIDDEN && !danmakuLoaded) {
                    detail?.let { data -> data.episodes.getOrNull(episodeIndex)?.let { episode -> loadDanmaku(episode,data.series.id) } }
                } else if(mode!=DanmakuMode.HIDDEN) danmakuView?.invalidate()
                // 档位切换会改变面板的行数（隐藏时没有两条外观项），因此整块重建，
                // 焦点按回刚改的这一行、落在同一个档位上。
                if(previous!=mode) showPlaybackMenu(MENU_ROW_DANMAKU,index)
                else { menu.select(MENU_ROW_DANMAKU,index); updatePlaybackText() }
            }
        })
    // 弹幕的三项可调项：移动速度（三档，选中后整批重排轨道）+ 两项连续外观（字号缩放与不透明度）。
    // 速度排在字号前面：它决定同一条轨道能排多密，是「弹幕挤不挤」的第一观感。
    if(showsStyle) menu
        .addRow("弹幕速度",DanmakuSpeed.entries.mapIndexed { index,option ->
            PlaybackMenu.Choice(option.label,option==library.danmakuSpeed) {
                library.danmakuSpeed=option; danmakuView?.speed=option
                menu.select(MENU_ROW_DANMAKU_SPEED,index); updatePlaybackText()
            }
        })
        .addSlider("弹幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
            library.danmakuScale=value; danmakuView?.textScale=value
        }
        .addSlider("弹幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
            library.danmakuAlpha=value; danmakuView?.textAlpha=value
        }
    menu
        .addRow("定时",sleepChoices(menu))
    if(dramaBrowser.active) menu.addRow("剧目",listOf(
        PlaybackMenu.Choice("选集",false) { enterBrowserDrama(); hidePlaybackOverlay(); showEpisodePanel(hud) },
        PlaybackMenu.Choice("搜索剧名",false) { leaveBrowserForSearch() },
        PlaybackMenu.Choice("继续刷剧",false) { dramaBrowser.browse(); hidePlaybackOverlay(); showBrowserHint() }
    ))
    menu
        .wire()
    playbackMenu=menu
    panel=true
    // 设置菜单打开时收起底部控制条：参考界面里菜单就是这一层唯一的浮层。
    main.removeCallbacks(hideHud)
    hud.visibility=View.GONE
    // 菜单占着下半屏，标题条这时留在上面才读得出「在放哪一集」。
    playbackTopBar?.visibility=View.VISIBLE
    // 整宽贴在画面下方，**高度锁死在屏幕高度的三分之一**，超出的行靠上下键滚出来。
    // 面板本身就是滚动内容，渐变背板画在外壳上：否则背板会跟着内容一起被拉长，
    // 滚到下半段时面板顶部会变成半黑，而不是「从透明渐渐暗下去」。
    // 这里**不补 FOCUS_BLEED_VERTICAL**，只按豁免标注（见 docs/UI_REDESIGN.md 第 3 条）：
    //   · 横向：外壳整屏宽，菜单自己的左右内边距就是 78dp 内容线，最左 / 最右那枚选项
    //     放大 2.5% 之后离屏幕边还有 ≥68dp，画不到边界；
    //   · 纵向：面板里最高的一件是紧凑档选项（BUTTON_COMPACT + 2 × SOFT_BAND = 52dp），
    //     放大 1.05 倍向上 / 向下各溢出 52 × 2.5% ≈ 1.3dp，下面那 6dp 内边距已经盖住；
    //     而且首行之上还有菜单自己的 24dp 上内边距。
    // 换成 focusBleed() 会把左右内边距一并撑到 7dp，内容线就从 78dp 变成 85dp，
    // 与顶部标题条（78dp）对不齐，因此这里只标注、不补余量。
    val shell=tvScrollHost(ScrollView(this).apply { // focus-bleed-exempt: 外壳整屏宽、左右内容线 78dp；纵向溢出 ≤1.3dp 已由 6dp 内边距覆盖
        isVerticalScrollBarEnabled=false
        isFocusable=false
        clipChildren=false
        clipToPadding=false
        // 上下各留一点余量：焦点放大 1.05 的那圈底色画在行的边界之外，留白不够会被裁掉。
        setPadding(0,dp(TvStyle.SOFT_BAND),0,dp(TvStyle.SOFT_BAND))
        addView(menu,FrameLayout.LayoutParams(-1,-2))
    }).apply { background=TvStyle.overlayPanel() }
    // 面板自己不再画背板（背板已经在外壳上），否则两层渐变叠起来会比选集面板深一档。
    menu.background=null
    playbackMenuShell=shell
    root.addView(shell,FrameLayout.LayoutParams(-1,resources.displayMetrics.heightPixels/3,Gravity.BOTTOM))
    if(focusChoice<0) menu.focusSelected(focusRow) else menu.focus(focusRow,focusChoice)
}
internal fun MainActivity.showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
    if(speedDialog!=null) return
    val speeds=PlaybackSpeed.options
    val selected=speeds.indexOf(library.playbackSpeed)
    // 与播放器菜单同一套选项按钮：整行左对齐的胶囊，当前值用强调色标出。
    val picker=TvDialog(this,"全局默认倍速").choices(speeds.map(PlaybackSpeed::label),selected) { index ->
        library.playbackSpeed=speeds[index]
        if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
        if(inPlayer) updatePlaybackText()
    }
    picker.button("取消")
    speedDialog=picker
    picker.show(anchor) {
        speedDialog=null
        if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
    }
}
internal fun MainActivity.showQualityPicker(anchor: TextView,inPlayer: Boolean) {
    val options=PlaybackQuality.options
    val picker=TvDialog(this,"全局默认清晰度").choices(options.map { "${it}P" },options.indexOf(library.maxQuality)) { index ->
        val target=options[index]; val changed=library.maxQuality!=target
        library.maxQuality=target
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
        if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
    }
    picker.button("取消")
    picker.show(anchor)
}
/**
 * 播放器底部的选集面板（遥控器「↓」打开）。
 *
 * **打开面板不暂停播放**：选集是一层贴底的浮层，画面与声音照常走，用户挑完再切集。
 * 因此这里不动播放状态，关闭时也不需要「恢复播放」——只把焦点还给底部控制条 / 下拉箭头，
 * 并按打开前的可见性恢复浮层。播放位置照旧定期落盘（[saveProgress] 由播放进度回调负责）。
 */
internal fun MainActivity.showEpisodePanel(anchor: View) {
    val data=detail ?: return
    if(episodePanel!=null) return
    val ticket=generation
    val overlayWasVisible=hud.visibility==View.VISIBLE
    // 选集面板也要知道在放哪一集，标题条跟着面板一起露出来。
    playbackTopBar?.visibility=View.VISIBLE
    // 面板内容与简介页共用同一个 EpisodeGrid，只有宿主不同（贴底弹窗 / 页面末尾），
    // 翻页方式一致：左右到行两端、上下到第一行 / 最后一行到边缘翻页。
    val grid=EpisodeGrid(this,data.episodes.size,episodeIndex,player=true,
        { selected ->
            if(selected==episodeIndex) episodePanel?.dismiss()
            else { episodePanel?.dismiss(); playEpisode(selected) }
        })
    grid.fillHeight=true
    val next=EpisodePanel(this,grid) {
        episodePanel=null
        if(foreground && generation==ticket && screen=="player") {
            if(anchor.isAttachedToWindow) anchor.requestFocus()
            if(overlayWasVisible) showHud() else hidePlaybackOverlay()
        }
    }
    episodePanel=next; next.show()
}
internal fun MainActivity.formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
/** 播放器时间轴：爆米花固定 hh:mm:ss，短剧也只有几分钟也不会跳字宽。 */
internal fun MainActivity.formatSpan(millis: Long): String {
    val seconds=millis.coerceAtLeast(0)/1000
    return "%02d:%02d:%02d".format(seconds/3600,(seconds%3600)/60,seconds%60)
}
internal fun MainActivity.saveProgress(completed: Boolean=false) {
    val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
    val progress=WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis())
    library.save(progress)
    syncProgress(progress,data.episodes.size)
}
internal fun MainActivity.releasePlayer() {
    cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
    dismissPlaybackMenu()
    val wasForeground=foreground; foreground=false
    val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
    speedDialog?.dismiss(); speedDialog=null
    main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
    restoreBufferingUi(); retirePreviousEpisode()
    playerListener?.let { player?.removeListener(it) }; playerListener=null
    video?.close(); video=null; playerView?.player=null; playerView=null; player?.release(); player=null; playbackReady=false
    // 在途的弹幕请求作废，弹幕层连同它的轨道一起丢掉。
    danmakuToken.incrementAndGet(); danmakuFor=""; danmakuLoaded=false; danmakuCount=-1; danmakuView=null
    playbackTopBar=null
    closeTransport(playbackHttp); playbackHttp=null
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
