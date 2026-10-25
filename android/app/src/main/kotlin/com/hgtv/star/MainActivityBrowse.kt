// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.graphics.Color
import android.widget.FrameLayout
import com.hgtv.star.core.*
import com.hgtv.star.list.showCatalog

internal fun MainActivity.startDramaBrowser(selected:Series?=null,preview:Boolean=true) {
    cancelBrowseLoad()
    projectorAutoBrowse=false
    val queue=if(tab==TAB_HOME) homeFeed.values.flatMap { it.items } else catalog
    val available=if(selected!=null) queue+selected else queue
    dramaBrowser.start(available,selected?.id,preview)
    browsePage=1; browseHasMore=true; browseLoading=false
    if(!dramaBrowser.active) { tvTools.toast("短剧还未加载，请稍后再试"); return }
    openBrowserDrama(dramaBrowser.current!!)
}

internal fun MainActivity.openBrowserDrama(series:Series) {
    cancelBrowseLoad()
    // Save the old drama before openDetail clears it; its player must not keep playing behind loading.
    saveProgress(); library.flush(); releasePlayer()
    browseLoading=false; panel=false
    catalogFocus=series.id
    openDetail(series,resume=true)
}

internal fun MainActivity.enterBrowserDrama() {
    if(!dramaBrowser.preview) return
    cancelBrowseLoad()
    dramaBrowser.enter()
    showBrowserHint()
    if(screen=="player") {
        playbackTitle.text="${detail?.series?.title.orEmpty()}  ·  第 ${episodeIndex+1} 集 · 追剧"
        tvTools.toast("已进入本剧 · ↑ 上一集  ↓ 下一集 · 返回继续刷剧")
    }
}

internal fun MainActivity.browseDrama(direction:Int) {
    if(!dramaBrowser.preview) return
    dramaBrowser.move(direction)?.let { openBrowserDrama(it); return }
    if(direction<0) { tvTools.toast("已经是第一部短剧"); return }
    if(browseLoading) return
    if(!browseHasMore) { tvTools.toast("暂时没有更多短剧，返回可搜索剧名"); return }
    browseLoading=true
    tvTools.toast("正在加载更多短剧…")
    val next=browsePage+1
    val browseQuery=if(tab==TAB_SEARCH && query.isNotBlank()) query else "短剧"
    val scope=RequestScope(); browseScope=scope
    val ticket=generation
    // Pagination must never cancel an in-flight playback-address request.
    browseJob=io.submit {
        val loaded=runCatching { scope.run { repository.search(browseQuery,next,ContentType.SHORT) } }
        main.post {
            if(browseScope!==scope) return@post
            browseLoading=false; browseScope=null; browseJob=null
            if(isDestroyed || !foreground || generation!=ticket || !dramaBrowser.preview) return@post
            loaded.onSuccess { result ->
                browsePage=next; browseHasMore=result.hasMore
                dramaBrowser.append(result.items)
                val item=dramaBrowser.move(1)
                if(item!=null) openBrowserDrama(item)
                else tvTools.toast(if(browseHasMore) "本页没有新短剧，按下键继续" else "暂时没有更多短剧，返回可搜索剧名")
            }.onFailure { tvTools.toast("加载更多失败，按下键重试，或返回搜索") }
        }
    }
}

/** Modal menus keep their normal focus navigation; held keys never skip multiple videos. */
internal fun MainActivity.dispatchBrowserKey(event:KeyEvent):Boolean {
    if(!dramaBrowser.active || screen !in listOf("player","detail") || (panel && !playError)) return false
    val key=event.keyCode
    val confirm=key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_NUMPAD_ENTER
    if(key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN) {
        if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
            val direction=if(key==KeyEvent.KEYCODE_DPAD_UP) -1 else 1
            if(dramaBrowser.preview) browseDrama(direction)
            else if(screen=="player") {
                val target=episodeIndex+direction
                if(target in 0 until (detail?.episodes?.size ?: 0)) skipEpisode(direction)
                else tvTools.toast(if(direction<0) "已经是第一集" else "已经是最后一集")
            }
        }
        return true
    }
    if(confirm && dramaBrowser.preview && (screen!="player" || !playError)) {
        if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) enterBrowserDrama()
        return true
    }
    if(key==KeyEvent.KEYCODE_BACK || key==KeyEvent.KEYCODE_ESCAPE) {
        if(event.action==KeyEvent.ACTION_UP) {
            if(screen=="player" && (playbackMenu!=null || episodePanel!=null)) hidePlaybackOverlay()
            else if(!dramaBrowser.preview) {
                dramaBrowser.browse()
                if(screen=="player") {
                    showBrowserHint()
                    tvTools.toast("已回到刷剧 · ↑↓ 换剧 · 确定进入本剧")
                }
            } else {
                dramaBrowser.close(); browseLoading=false
                cancelBrowseLoad()
                saveProgress(); library.flush(); invalidatePage(); releasePlayer(); showCatalog()
            }
        }
        return true
    }
    // Keep the menu reachable with physical keyboards that do not have a TV menu key.
    if(key==KeyEvent.KEYCODE_M || key==KeyEvent.KEYCODE_F10 || key==KeyEvent.KEYCODE_MENU) {
        if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 && screen=="player") showPlaybackMenu()
        return true
    }
    return false
}

internal fun MainActivity.browserHint()=if(dramaBrowser.preview)
    "刷剧  ↑↓ 换剧    确定 / 点击 进入本剧    菜单 / M 选集与画面    返回 搜索"
else "追剧  ↑ 上一集  ↓ 下一集    确定 暂停    菜单 / M 选集与画面    返回 刷剧"

internal fun MainActivity.showBrowserHint() {
    browseKeyHint?.let { (it.parent as? ViewGroup)?.removeView(it) }
    val hint=text(if(dramaBrowser.active) browserHint() else "↓ 选集    ← / → 快退快进    确定 暂停    返回 退出",16f,Color.WHITE).apply {
        setBackgroundColor(0xCC17202D.toInt()); setPadding(dp(18),dp(9),dp(18),dp(9))
        gravity=Gravity.CENTER
    }
    browseKeyHint=hint
    root.addView(hint,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(14) })
    hint.postDelayed({ if(!dramaBrowser.preview) (hint.parent as? ViewGroup)?.removeView(hint) },8000L)
}

internal fun MainActivity.leaveBrowserForSearch() {
    cancelBrowseLoad()
    dramaBrowser.close(); projectorAutoBrowse=false; browseLoading=false
    saveProgress(); library.flush(); invalidatePage(); releasePlayer(); panel=false
    switchTab(TAB_SEARCH)
}

internal fun MainActivity.cancelBrowseLoad() {
    browseScope?.cancel(); browseScope=null
    browseJob?.cancel(true); browseJob=null; browseLoading=false
}
