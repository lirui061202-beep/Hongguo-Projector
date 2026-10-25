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
import com.hgtv.star.MainActivity.DeferredUi
import com.hgtv.star.data.ArtworkLoader
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.list.showCatalog

// 本文件是从 MainActivity.kt 拆出来的「本机库加载、封面取图、异步任务封装与空页 / 错误提示」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.showLibraryError(problem: Throwable) {
    val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
}
internal fun MainActivity.loadLibrary() {
    libraryLoadError=null
    library.loadAsync(onFailure={ problem ->
        if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
    }) {
        if(!isDestroyed && foreground) { run {
            val defaults=getSharedPreferences("projector-defaults",Context.MODE_PRIVATE)
            if(!defaults.getBoolean("applied",false)) {
                library.maxQuality=720; library.danmakuMode=DanmakuMode.HIDDEN
                defaults.edit().putBoolean("applied",true).apply()
            }
            if(!defaults.getBoolean("browse-fullscreen-applied",false)) {
                library.autoNext=true
                defaults.edit().putBoolean("browse-fullscreen-applied",true).apply()
            }
            // Restore the original aspect ratio once, including upgrades from the stretched default.
            if(!defaults.getBoolean("original-aspect-applied",false)) {
                library.frameMode=VideoFrameMode.FIT
                defaults.edit().putBoolean("original-aspect-applied",true).apply()
            }
        }; applySessionToken(); applyAccount(); showCatalog(load=true); if(repository.signedIn) loadAccount() }
    }
}
/**
 * 搜索登录态（可选）跟着本机设置走：留空时搜索退回官网搜索，填上后直接使用 App 搜索接口。
 * 设置里改完立刻作用于后面的搜索请求。
 */
internal fun MainActivity.applySessionToken() { repository.sessionToken=library.searchToken.ifBlank { null } }
internal fun MainActivity.base(title: String?=null): LinearLayout {
    root.removeAllViews(); root.setBackgroundColor(bg)
    val container=column().apply { clipChildren=false; clipToPadding=false }
    val horizontal=pageInset()-pageBleed(); val vertical=dp(12)
    container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
    if(title!=null) {
        // 二级界面（详情页加载中）不再放返回按钮：返回键与顶部导航就是返回入口，页头只留标题。
        // 页头行补回焦点余量，标题仍从内容线开始。
        val heading=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(6)) }
        heading.addView(text(title,20f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(TvStyle.BUTTON_NORMAL)).apply { weight=1f })
        container.addView(heading)
    }
    return container
}
/** 预取下一行的封面：只排队，不抢当前可见卡片的队列位置。 */
internal fun MainActivity.prefetchCover(view: ImageView,url: String) {
    artwork.load(view,url,ArtworkLoader.PRIORITY_PREFETCH)
}
internal fun MainActivity.loadCover(view: ImageView,url: String) {
    var ancestor: View?=view
    var focused=false
    while(ancestor!=null && ancestor!==root) {
        if(ancestor.isFocused) { focused=true; break }
        ancestor=ancestor.parent as? View
    }
    artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
}
internal fun MainActivity.cancelWork() {
    workDeadline?.let { main.removeCallbacks(it) }; workDeadline=null
    deferredWork?.discard?.invoke(); deferredWork=null
    workScope?.cancel(); workScope=null
    workJob?.cancel(true); workJob=null
    (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
}
internal fun MainActivity.invalidatePage() {
    generation++; cancelWork(); artwork.cancelPage()
}
internal fun MainActivity.releaseCatalogViews() {
    homeScreen?.let { homePosition=it.capturePosition() }
    // 换榜单 / 换分类时 [catalogPageKey] 还是**上一条列表**的 key（它要到 seriesList 里才重算），
    // 因此这里照记没错：记的是上一条列表自己的浏览位置，用户切回来时还能回到原处。
    catalogScroll?.let { rememberScroll(catalogPageKey,it.scrollY) }
    catalogBody=null
    homeScreen=null; catalogScroll=null
    nav.clear(); boardButtons.clear(); accountButtons.clear(); profileHeadRow=null
    categoryGroupButtons.clear(); categoryTagButtons.clear()
    searchInput=null; searchButton=null; searchActions.clear()
    // 提示词那一行连同在途的请求一起作废：它属于上一页的输入框，换页后不该再往新页面里塞。
    suggestRun.getAndSet(null)?.let { main.removeCallbacks(it) }
    suggestJob?.cancel(true); suggestJob=null
    suggestToken.incrementAndGet()
    suggestRow=null; suggestLine=null; suggestButtons.clear()
    historyManage=null; rankSubtitle=null; moreButton=null
    favoriteStatus=null
    // 栅格连同它的追加状态一起作废：在途的预取结果不能再往新页面上接。
    gridPrefetchJob?.cancel(true); gridPrefetchJob=null
    gridList=null; gridScroll=null; gridPaging=null; moreCount=null; pagingHasMore=false
    gridWindow=null; gridTopSpacer=null; gridBottomSpacer=null
    gridItems=emptyList(); gridRowStride=0; gridWindowStart=-1
    gridRows.clear(); gridRowPool.clear(); gridCardWidth=0; gridGap=0; gridPosterHeight=0
    moreInFlight=false; moreInFlightPage=0; moreVisible=false; moreFocusWanted=false; moreFailed=false
    // 页头收展状态：同一页原地追加（加载更多）保留，其余换页都从展开态开始。
    val keepTopArea=keepTopAreaState
    keepTopAreaState=false
    if(!keepTopArea) topAreaHidden=false
    // 运行中的页头动画先快照、再清表、最后取消。
    // ValueAnimator.cancel() 会**同步**回调 onAnimationEnd，而那条回调会从这张表里摘掉自己；
    // 边遍历这张表边取消就是 ConcurrentModificationException——页头有两行以上
    // （搜索行 / 榜单标题行 / 集合页标题行）时必现，而收展动画那 200ms 里
    // 「展示更多」「刷新榜单」、切分类、进详情都会重建页面，表现出来就是偶发崩溃。
    val running=topAreaAnimators.values.toList()
    topAreaAnimators.clear()
    running.forEach { it.cancel() }
    topArea.clear(); topAreaHeights.clear(); topAreaRestore.clear()
}
internal fun <T> MainActivity.work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
    cancelWork()
    val ticket=generation; val scope=RequestScope(); workScope=scope
    val delivered=java.util.concurrent.atomic.AtomicBoolean(false)
    val deadline=Runnable {
        if(isDestroyed || generation!=ticket || workScope!==scope || !delivered.compareAndSet(false,true)) return@Runnable
        scope.cancel(); workJob?.cancel(true)
        val problem=java.net.SocketTimeoutException("连接内容服务超时，请重试或换一集")
        if(foreground) failed(problem) else deferredWork=DeferredUi({ failed(problem) })
    }
    workDeadline=deadline
    main.postDelayed(deadline,30_000L)
    workJob=io.submit {
        try {
            val result=scope.run(action)
            main.post {
                main.removeCallbacks(deadline)
                if(workDeadline===deadline) workDeadline=null
                val discard={ if(result is RemoteVideo) result.close(); Unit }
                if(isDestroyed || generation!=ticket || workScope!==scope || !delivered.compareAndSet(false,true)) discard()
                else if(foreground) done(result)
                else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
            }
        } catch(e: Exception) {
            main.post {
                main.removeCallbacks(deadline)
                if(workDeadline===deadline) workDeadline=null
                if(!isDestroyed && generation==ticket && workScope===scope && delivered.compareAndSet(false,true)) {
                    if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                }
            }
        }
    }
}
internal fun MainActivity.message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(pageBleed(),dp(28),pageBleed(),dp(18)) }) }
/** 加载失败时的说明 + 「重试」按钮；返回那枚按钮，调用方可以把它接进自己的焦点链。 */
internal fun MainActivity.error(parent: LinearLayout,problem: Throwable,retry: ()->Unit): TvButton {
    message(parent,"暂时无法加载，请检查网络后重试。")
    val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
    parent.addView(text(detail,13f,muted).apply { setPadding(pageBleed(),0,pageBleed(),0) })
    // 重试按钮与上面的提示文字共用同一条内容线：纵向间距由 addButton 给，横向自己补回焦点余量。
    return addButton(parent,"重试",onClick=retry).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin=pageBleed(); requestFocus() }
}
