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
import com.hgtv.star.player.DanmakuView
import com.hgtv.star.tv.SeekIndicator
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.player.VideoDataSource

// 本文件是从 MainActivity.kt 拆出来的「播放内核：起播、切集、弹幕加载」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.loadDanmaku(episodeId: String,seriesId: String) {
    val view=danmakuView ?: return
    view.resetCursor(); view.mode=library.danmakuMode
    // 同一集重复进入（换清晰度、播放重试）不重复拉弹幕：已经拿到的这一份直接沿用，
    // 只把播放位置指针重置回开头。空结果也算「拿到」——这一集确实没有弹幕。
    if(danmakuFor==episodeId && danmakuLoaded) return
    val token=danmakuToken.incrementAndGet()
    danmakuFor=episodeId
    danmakuLoaded=false
    danmakuCount=-1
    view.clear()
    if(library.danmakuMode==DanmakuMode.HIDDEN) return
    danmakuWorker.execute {
        val items=runCatching {
            // 边拉边画：一个 30 秒窗口到手就先铺上屏。整集要拉几十个窗口、每个之间还要留频控间隔，
            // 等全量再画等于前十几秒一条弹幕都没有，看上去和「弹幕功能没生效」一样。
            repository.danmaku(episodeId,seriesId,0,{ danmakuToken.get()!=token }) { partial ->
                main.post {
                    if(isDestroyed || danmakuToken.get()!=token || screen!="player") return@post
                    danmakuView?.setDanmaku(partial)
                }
            }
        }
            .onFailure { android.util.Log.w("HGTV","Danmaku fetch failed episode=$episodeId",it) }
            .getOrDefault(emptyList())
        main.post {
            if(isDestroyed || danmakuToken.get()!=token || screen!="player") return@post
            danmakuLoaded=true; danmakuCount=items.size
            android.util.Log.i("HGTV","Danmaku episode=$episodeId count=${items.size} mode=${library.danmakuMode.storedValue}")
            danmakuView?.setDanmaku(items)
            // 一条都没取到才提示一次：平台上只有部分剧集对匿名设备放出弹幕，
            // 不说明的话「屏幕上没有弹幕」会被当成功能坏了。

            updatePlaybackText()
        }
    }
}
// media3 的 `PlayerView` / `DefaultLoadControl` 这些成员标了 `@UnstableApi`，lint 的
// `UnsafeOptInUsageError` 要求使用处显式 opt-in（行为不变，只是把「我知道这是不稳定 API」写在代码上）。
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun MainActivity.playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false) {
    val data=detail ?: return
    val playbackStarted=android.os.SystemClock.elapsedRealtime()
    val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
    library.setWatched(data.series.id,false)
    val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
    val prefetched=if(position==0L && !recovering) preparedNext.take("${data.episodes[selectedIndex]}:${library.maxQuality}") else null
    val claimedHttp=if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
    // 无感切集：同一个播放器复用、目标是下一集的开头、而且下一集**已经预热好**
    //（播放地址 / moov / 首批数据都在本地，见 [maybePrefetch]）。
    // 这时切集不再走「停播放器 → 清空 → 重新取地址 → 重建画面」那条路：
    // 不清播放器（`stop()` / `clearMediaItems()` 会把画面掉到黑屏，编解码器还要重建一次），
    // 不显示缓冲转圈，旧一集的视频等新源出画面之后再收。
    val seamless=reuse && prefetched!=null
    if(seamless) suppressBufferingUi() else restoreBufferingUi()
    // 上一次切集留下的尾巴（正常情况为空：新源出画面时就收掉了，见 [retirePreviousEpisode]）。
    // 它早已不在播放器上，这里先收掉，免得下面记新的尾巴时把它顶掉。
    retirePreviousEpisode()
    saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
    dismissPlaybackMenu()
    if(reuse) {
        cancelRecovery(); cancelPrefetch(); tvTools.close()
        episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        if(!seamless) { player?.stop(); player?.clearMediaItems() }
        // 旧一集的视频与传输先记下来：无感切集不当场关（旧源可能还在收尾，当场关会让它读到
        // 一个已经关闭的视频而报错），等新源出画面再收；非无感路径下面紧接着就收掉。
        retiringVideo=video; retiringHttp=playbackHttp; video=null; playbackHttp=null
        if(!seamless) retirePreviousEpisode() else scheduleRetire()
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
    } else releasePlayer()
    artwork.cancelPage(clearMemory=true)
    playbackHttp=claimedHttp; video=prefetched
    var firstReady=true; var firstFrame=true
    if(!recovering) recovery.reset()
    autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
    if(autoplay) sleepStopped=false
    screen="player"; episodeIndex=selectedIndex
    panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
    player?.playWhenReady=requestedAutoplay
    prefetchAttempts=0; prefetchAttemptAt=0
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if(!reuse) {
        root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
        val view=PlayerView(this).apply {
            useController=false; resizeMode=frameResizeMode(); isFocusable=false
            setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
        view.setOnClickListener { if(dramaBrowser.preview) enterBrowserDrama() else togglePlayback() }
        // 弹幕层压在画面之上、标题条与控制层之下：隐藏弹幕时整层不可见，也不参与绘制。
        // 播放位置与播放状态都从播放器现取，因此暂停 / 跳转 / 倍速都不会和画面脱节。
        val overlay=DanmakuView(this).apply {
            mode=library.danmakuMode
            // 字号、透明度与速度都是本机偏好（设置面板里调出来的），
            // 换集、换清晰度重建这一层时都要带上，否则会回退成默认档。
            textScale=library.danmakuScale
            textAlpha=library.danmakuAlpha
            speed=library.danmakuSpeed
            positionMillis={ player?.currentPosition ?: 0L }
            playing={ player?.isPlaying==true }
        }
        danmakuView=overlay
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        // 顶部标题条：对齐爆米花 activity_player —— 整宽 96dp、50% 黑 → 透明，
        // 左侧 24sp 粗体剧名、右侧同字号时钟，左右安全边距 78dp。
        // 播放界面压在视频上，文字固定用暖白（TvStyle.playerText），不跟主题：
        // 浅色主题下如果跟着变成深色字，这条标题条就完全看不见了。
        playbackTitle=text("",24f,TvStyle.playerText).apply {
            maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD)
            setShadowLayer(5f,0f,2f,0x80000000.toInt())
        }
        val clock=text("",24f,TvStyle.playerText).apply { setShadowLayer(5f,0f,2f,0x80000000.toInt()) }
        playerClock=clock
        // 顶部标题条：整宽 96dp、50% 黑 → 透明，左侧 24sp 粗体剧名、右侧同字号时钟。
        // 这一条压在画面上，加柔光底板反而会挡住画面、也不符合「播放界面尽量不打扰」的取向。
        // 播放时不打扰：默认收起，只有唤出播放界面（底部控制条 / 播放菜单 / 选集面板）才出现，
        // 平时屏幕上只剩画面和弹幕。
        val topBar=row().apply {
            setPadding(dp(78),0,dp(78),0)
            background=TvStyle.gradient(0x80000000.toInt(),Color.TRANSPARENT)
            visibility=View.GONE
        }
        topBar.addView(playbackTitle,lp(0,-2).apply { weight=1f })
        topBar.addView(clock,lp(-2,-2))
        root.addView(topBar,FrameLayout.LayoutParams(-1,dp(96),Gravity.TOP))
        playbackTopBar=topBar

        // 底部控制层保持原样：透明 → 60% 黑 → 90% 黑的三段渐变，压在画面上自然暗下去。
        // 播放界面（标题条 + 控制层 + 进度条）不套新拟态：任何柔光底板都会和画面抢注意力，
        // 这一层只负责让画面暗下来、让控制项读得清。
        // 第一行 = 42.5dp 播放键 + 进度条 + 右对齐时间；第二行 = 20sp 状态项。
        hud=column().apply {
            setPadding(dp(78),dp(70),dp(78),0)
            background=TvStyle.panel()
        }
        val transport=row()
        transportIcon=ImageView(this).apply {
            setImageResource(R.drawable.ic_player_play)
            scaleType=ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { togglePlayback() }
        }
        transport.addView(transportIcon,lp(dp(43),dp(43)))
        progressBar=SeekIndicator(this)
        transport.addView(progressBar,lp(0,dp(43)).apply { weight=1f; leftMargin=dp(13); rightMargin=dp(26) })
        playbackText=text("",18f,TvStyle.playerText).apply { setShadowLayer(4f,0f,1f,0x80000000.toInt()) }
        transport.addView(playbackText,lp(-2,-2))
        hud.addView(transport,lp(-1,dp(47)))
        // 状态行只显示当前值、只做点击入口（遥控器走 上=播放设置菜单、下=选集），因此不抢焦点。
        val options=row().apply { setPadding(0,dp(20),0,0) }
        fun option(label: String,action: ()->Unit): TextView {
            val item=text(label,20f,TvStyle.playerDim).apply {
                gravity=Gravity.CENTER; maxLines=1
                setOnClickListener { action() }
                // 状态项的焦点只做「更亮一档」，同样不引入颜色。
                setOnFocusChangeListener { view,focused -> (view as TextView).setTextColor(if(focused) TvStyle.playerText else TvStyle.playerDim) }
            }
            options.addView(item,lp(-2,-2).apply { rightMargin=dp(34) })
            return item
        }
        // 点击任一状态项直接打开竖向菜单的对应项目。
        qualityOption=option("清晰度 ${library.maxQuality}P") { showPlaybackMenu(MENU_ROW_QUALITY) }
        speedOption=option("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}") { showPlaybackMenu(MENU_ROW_SPEED) }
        frameOption=option("画面 ${library.frameMode.label}") { showPlaybackMenu(MENU_ROW_FRAME) }
        danmakuOption=option("弹幕 ${library.danmakuMode.label}") { showPlaybackMenu(MENU_ROW_DANMAKU) }
        sleepOption=option("定时 ${sleepTimer.label()}") { showPlaybackMenu(sleepRow(library.danmakuMode.showsStyle)) }
        hud.addView(options,lp(-1,-2))
        // 底部居中的下拉箭头（爆米花 scroll_indicator）：点击直接打开选集。
        hud.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_down)
                scaleType=ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { showEpisodePanel(hud) }
            },
            lp(dp(75),dp(38)).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=dp(8) }
        )
        controls=row().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
        // 这个滚动容器高度是 WRAP_CONTENT，内容永远不超过自己，因此不需要外壳裁剪；
        // 容器自己也不裁剪，播放器按钮放大后的底色才能画出来。
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipChildren=false; clipToPadding=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM)) // focus-bleed-exempt: 边界就是整屏宽，播放器按钮离边缘 ≥78dp
    }
    playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集${if(dramaBrowser.preview) " · 刷剧" else if(dramaBrowser.active) " · 追剧" else ""}"
    loadDanmaku(data.episodes[episodeIndex],data.series.id)
    playbackText.text="正在获取播放地址…"; progressBar.progress=0f; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
    showHud()
    showBrowserHint()
    controls.removeAllViews(); controls.visibility=View.GONE
    syncMediaSession()
    val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
    work({
        // 播放用的是**平台注册过的**设备身份（本机随机值已经不再参与签名）：
        // 注册还没落地时先补一次，免得取地址落到内置那份被拉黑的档案上。
        if(!library.deviceRegistered()) registerDeviceOnce()
        prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality)).prepare()
    }, { remote ->
        video=remote; quality=remote.info.quality
        val p=player ?: run {
            val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).build()
            val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                player=it; playerView?.player=it
                it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                it.setHandleAudioBecomingNoisy(true)
            }
        }
        p.setPlaybackSpeed(library.playbackSpeed)
        val firstFrameDeadline=Runnable {
            if(generation==ticket && screen=="player" && player===p && !playError && foreground && requestedAutoplay && firstFrame) {
                p.stop()
                playerFailure(java.net.SocketTimeoutException("视频缓冲超时"))
            }
        }
        val listener=object: Player.Listener {
            internal fun active()=player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId
            override fun onPlaybackStateChanged(state: Int) {
                if(!active()) return
                if(state==Player.STATE_READY) {
                    if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                        playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                        return
                    }
                    playbackReady=true
                    // 新源已经出画面：这时候再收上一集的视频与传输最稳，顺手把缓冲指示恢复。
                    retirePreviousEpisode(); restoreBufferingUi()
                    if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HGTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
                    if(hud.visibility==View.VISIBLE) updatePlaybackText()
                }
                syncMediaSession()
                if(state==Player.STATE_ENDED) {
                    saveProgress(true)
                    if(sleepTimer.episodeEnded()) stopForSleep()
                    else if(foreground && requestedAutoplay && !pausedForLifecycle && (library.autoNext || dramaBrowser.active)) {
                        cancelBrowseLoad()
                        val next=if(dramaBrowser.active) dramaBrowser.episodeAfterEnd(episodeIndex,data.episodes.size)
                            else (episodeIndex+1).takeIf { it in data.episodes.indices }
                        if(next!=null) playEpisode(next)
                        else { requestedAutoplay=false; p.pause(); playbackText.text="本剧已结束 · 返回继续刷剧"; showBrowserHint(); showHud() }
                    }
                    else { requestedAutoplay=false; p.pause(); playbackText.text="本集已结束"; showHud() }
                }
            }
            override fun onRenderedFirstFrame() {
                if(active() && firstFrame) {
                    firstFrame=false
                    main.removeCallbacks(firstFrameDeadline)
                    if(!playError && playbackMenu==null && episodePanel==null) hidePlaybackOverlay()
                    android.util.Log.i("HGTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                if(!active()) return
                if(hud.visibility==View.VISIBLE) showHud()
                syncMediaSession()
                // 弹幕层的动画循环在暂停时停下，重新播放要把它叫醒。
                if(playing) danmakuView?.invalidate()
            }
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
                if(!active()) return
                syncMediaSession()
                // 跳转之后弹幕要跟着画面走：指针从头重找一次，再画一帧。
                danmakuView?.resetCursor(); danmakuView?.invalidate()
            }
            override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
        }
        playerListener=listener; p.addListener(listener)
        val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hgtv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
        p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle
        main.postDelayed(firstFrameDeadline,20_000L)
    }, { problem -> playerFailure(problem) })
}
