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
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.SoftStyle
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.list.gridCards
import com.hgtv.star.list.showCatalog

// 本文件是从 MainActivity.kt 拆出来的「搜索页：输入框、热词与联想提示、搜索历史」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.searchBar(top: LinearLayout,requestFocus: Boolean) {
    // 搜索框与同一行的按钮共用一套尺寸令牌：48dp 底板、胶囊圆角、左右 28dp 内边距，
    // 因此输入框和「搜索 / 历史」在一条基线上；差别只在「凹槽 vs 凸起」这一层语义。
    val searchBand=dp(TvStyle.SOFT_BAND)
    val input=EditText(this).apply {
        // 提示里把「怎么打字」写清楚：电视上没有手机输入按钮之后，用户靠系统输入法
        // （遥控器方向键选字）或外接键盘打字，这一行是他唯一能看到说明的地方。
        //
        // 提示文字**比正文字号小一档**（[SEARCH_HINT_TEXT]）：正文是 20sp 的粗体，
        // 提示跟着那么大的话一句话就被截断，只能显示半句；小一档之后整句都放得下。
        // `hint` 只接受 CharSequence，字号靠 AbsoluteSizeSpan 挂在片段上。
        id=View.generateViewId()
        hint=android.text.SpannableString(SEARCH_HINT).apply {
            setSpan(
                android.text.style.AbsoluteSizeSpan(SEARCH_HINT_TEXT.toInt(),true),
                0,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setText(query)
        textSize=SEARCH_INPUT_TEXT; setTypeface(typeface,Typeface.BOLD)
        setTextColor(TvStyle.text); setHintTextColor(TvStyle.muted); isSingleLine=true; maxLines=1
        ellipsize=TextUtils.TruncateAt.END
        filters=arrayOf(android.text.InputFilter.LengthFilter(80))
        imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        includeFontPadding=false
        // 输入框是一条「压进底板」的凹槽：内边距里含柔光带，内阴影才有地方画。
        // 左右内边距跟紧凑档按钮走（[TvStyle.BUTTON_PAD_COMPACT]），与同一行的按钮对齐。
        setPadding(dp(TvStyle.BUTTON_PAD_COMPACT)+searchBand,searchBand,dp(TvStyle.BUTTON_PAD_COMPACT)+searchBand,searchBand)
        background=softPanel(TvStyle.well,TvStyle.RADIUS_PILL,style=SoftStyle.INSET,ring=TvStyle.outline,ringWidth=1)
        setOnFocusChangeListener { view,_ ->
            val focused=view.hasFocus()
            // 凹陷控件的焦点没法靠「打亮」表达，只能把槽压得更深、让这条中性细线加深。
            // 线色取 45% 文字基色（浅色主题是深墨、深色主题是暖白），不是强调色——
            // 选框不再有任何颜色。
            view.background=softPanel(
                TvStyle.well,TvStyle.RADIUS_PILL,style=SoftStyle.INSET,
                ring=if(focused) TvStyle.focusLine else TvStyle.outline,
                ringWidth=1,
                depth=if(focused) 1.6f else 1.1f)
        }
        // EditText 自己会把上下键吃掉做光标移动，焦点因此困在输入框里出不去。
        // 这两下在这里显式交回页面：上=顶部圆形入口，下=提示词 / 热搜词那一行（没有时退到内容第一张卡）。
        setOnKeyListener { view,keyCode,event ->
            if(event.action!=KeyEvent.ACTION_DOWN || (keyCode!=KeyEvent.KEYCODE_DPAD_UP && keyCode!=KeyEvent.KEYCODE_DPAD_DOWN)) false
            else {
                val target=when {
                    keyCode==KeyEvent.KEYCODE_DPAD_DOWN -> root.findViewById<View>(view.nextFocusDownId)?.takeIf { it.isShown && it.isFocusable }
                        ?: gridCards().firstOrNull { it.isShown } ?: navEntry()?.takeIf { it.isShown }
                    else -> root.findViewById<View>(view.nextFocusUpId)?.takeIf { it.isShown && it.isFocusable }
                        ?: navEntry()?.takeIf { it.isShown }
                }
                if(target==null) false
                else {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken,0)
                    view.clearFocus(); target.requestFocus(); true
                }
            }
        }
    }
    top.addView(input,lp(dp(SEARCH_INPUT_WIDTH),dp(SEARCH_INPUT_HEIGHT+2*TvStyle.SOFT_BAND)).apply {
        // 固定宽度（不再吃满弹性空间）：搜索框与后面两枚按钮是一组，靠左聚在一起，
        // 省下来的宽度留给这一组与右边那排圆形入口之间的空白（见本函数末尾的 Space）。
        leftMargin=dp(TvStyle.GAP_GROUP); rightMargin=dp(TvStyle.GAP_TIGHT)
    })
    // 输入框为空时按「搜索」= 打开搜索历史（原来这里只是把焦点丢回输入框）。
    // 历史按钮因此可以从导航栏里去掉，把宽度让给右边那排圆形入口。
    fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { showSearchHistory(input); return }; runSearch(next) }
    searchInput=input
    // 两个按钮用紧凑档（40dp 底板 / 15sp）：导航栏那一行要放的东西多，
    // 常规档（48dp / 16sp）摆进去整行会偏高偏长。
    // 最小宽度放开到 [SEARCH_BUTTON_MIN_WIDTH]（按文字宽收紧），
    // 间距用 [TvStyle.GAP_TIGHT]：这两枚按钮与输入框是**一组**，彼此靠近。
    searchButton=addButton(top,"搜索",size=ButtonSize.COMPACT,gap=TvStyle.GAP_TIGHT) { search() }
        .apply { minimumWidth=dp(SEARCH_BUTTON_MIN_WIDTH) }
    // 「历史」按钮去掉了：这一行原来挤着「输入框 + 搜索 + 历史」三件，
    // 把右边那排圆形入口挤得只剩一点宽度。搜索历史仍然可用——输入框为空时按「搜索」
    // 就会打开历史（见 [search] 与 [showSearchHistory]）。
    // 这一组（输入框 + 搜索）与右边那排圆形入口之间留一段弹性空白：
    // 前者靠左，后者仍然贴在右边，中间因此有一段看得出来的距离。
    top.addView(Space(this),lp(0,1).apply { weight=1f })
    input.setOnEditorActionListener { _,_,_-> search(); true }
    // 打字就取提示词（去抖）；页面重建后监听器跟着输入框一起换新的。
    input.addTextChangedListener(object: android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
        override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { scheduleSuggestions(s?.toString().orEmpty()) }
    })
    // 进搜索页、还没有关键词时焦点直接落在输入框上：这一页要「等用户输入」，
    // 焦点停在顶部圆形入口上等于让用户先按一下右键才能打字。
    // 有关键词（刚搜完切回这一页）时不抢焦点：那时焦点应该在结果或它原来的位置上。
    //
    // 这里**不能**顺手设 `nextFocusUpId`：这一排圆形入口是在 [searchBar] 之后才建好的，
    // 此刻 `navEntry()` 还是 null（设了就等于把「按上」指向自己）。上键的落点统一在
    // 建完导航栏之后由 [wireHeadNavUp] 接上。
    if(requestFocus) input.post { if(input.isAttachedToWindow) input.requestFocus() }
}
/**
 * 搜索页导航栏里的控件「按上键」都回到那一排圆形入口。
 *
 * 必须在**圆形入口建好之后**调：导航栏是先建标题与搜索控件、最后才加四个圆形入口的，
 * 建搜索控件时 `navEntry()` 还是 null，那时设上键会把焦点指向自己。
 */
internal fun MainActivity.wireHeadNavUp() {
    val up=navEntry()?.id ?: return
    searchInput?.nextFocusUpId=up
    searchButton?.nextFocusUpId=up
    searchActions.forEach { it.nextFocusUpId=up }
}
/**
 * 输入变化 → 去抖取提示词。
 *
 * 去抖 220ms：每敲一个字都发请求会把接口打满，也会让那一行一直闪。
 * 清空输入时立刻清掉那一行（不发请求）。
 */
internal fun MainActivity.scheduleSuggestions(text: String) {
    suggestRun.getAndSet(null)?.let { main.removeCallbacks(it) }
    val clean=text.trim()
    // 清空输入：换回热搜词（而不是留一行空白）。
    if(clean.isEmpty()) { suggestToken.incrementAndGet(); loadHotSearches(); return }
    val run=Runnable { fetchSuggestions(clean) }
    suggestRun.set(run)
    main.postDelayed(run,SUGGEST_DEBOUNCE)
}
/**
 * 取一次热搜词（搜索页空输入时铺在输入框下面那一行）。
 *
 * 与提示词共用 [suggestRow] 那一行与同一套令牌：热搜词是「没有输入」时的默认内容，
 * 一旦开始打字就换成提示词。走自己的线程，不占用 [work] 那条导航通道。
 */
internal fun MainActivity.loadHotSearches() {
    val ticket=suggestToken.incrementAndGet()
    suggestJob?.cancel(true)
    suggestJob=io.submit {
        val result=runCatching { repository.hotSearches() }.getOrDefault(emptyList())
        main.post {
            if(isDestroyed || screen!="catalog" || tab!=TAB_SEARCH) return@post
            if(ticket!=suggestToken.get()) return@post
            // 用户已经开始打字了（输入框里有内容）：这一批热搜词让位给提示词。
            if((searchInput as? EditText)?.text?.toString()?.trim().orEmpty().isNotEmpty()) return@post
            renderHintRow(result)
        }
    }
}
/**
 * 取一次提示词。
 *
 * 请求走自己的线程与令牌（不占用 [work] 那条「一次导航一个请求」的通道）：
 * 它是**边打字边发**的，绝不能把页面重排、更不能取消用户正在等的搜索。
 * 结果回来时先比对令牌与当前输入框内容——晚回来的旧结果直接丢掉。
 */
internal fun MainActivity.fetchSuggestions(keyword: String) {
    suggestJob?.cancel(true)
    val ticket=suggestToken.incrementAndGet()
    suggestJob=io.submit {
        val result=runCatching { repository.suggestions(keyword,SUGGEST_COUNT) }.getOrDefault(emptyList())
        main.post {
            if(isDestroyed || screen!="catalog" || tab!=TAB_SEARCH) return@post
            if(ticket!=suggestToken.get()) return@post
            // 输入框里的词已经变了（用户又敲了字）：这一批已经过时。
            val current=(searchInput as? EditText)?.text?.toString()?.trim().orEmpty()
            if(current!=keyword) return@post
            renderHintRow(result.map { it.name })
        }
    }
}
/**
 * 重建输入框下面那一行（热搜词 / 提示词共用）。
 *
 * **只动这一行**：换关键词、热搜换成提示词都不重画整页，因此输入时页面不会闪。
 * 每一枚芯片都是**一个搜索词**：点了就把这个词填进输入框并搜（见 [runSearch]）——
 * 即使服务端在这一条上给了剧集信息（`short_play_name`），也不直接跳简介页：
 * 那一行是「搜索建议」，用户点它是想搜这个词，不是想跳过搜索结果。
 * 空列表时整行隐藏，不留空槽。
 */
internal fun MainActivity.renderHintRow(items: List<String>) {
    val line=suggestLine ?: return
    val scroll=suggestRow ?: return
    suggestButtons.clear()
    line.removeAllViews()
    if(items.isEmpty()) {
        scroll.visibility=View.GONE
        // 这一行收起来之后，导航栏里那几件（输入框 / 搜索 / 历史 / 手机输入）按下键
        // 要回到内容第一张卡——不能指向一条看不见的行。
        gridCards().firstOrNull()?.let { card ->
            searchInput?.nextFocusDownId=card.id
            searchButton?.nextFocusDownId=card.id
            searchActions.forEach { it.nextFocusDownId=card.id }
        }
        return
    }
    // 从 GONE 恢复：高度按固定档写回去（页头收展动画会改这个 LayoutParams，
    // 因此这里每次都重新写一遍，不能只改 visibility）。
    scroll.layoutParams=(scroll.layoutParams as LinearLayout.LayoutParams).apply { height=dp(CHIP_ROW_HEIGHT) }
    val chips=mutableListOf<View>()
    items.forEach { label ->
        val chip=ToggleChip(this,label,false,ButtonSize.COMPACT) {
            // 点这一条：输入框里换成这个词，然后**搜索**它。
            (searchInput as? EditText)?.setText(label)
            runSearch(label)
        }
        chip.contentDescription="$label，搜索"
        chips+=chip
        gapInto(line,chip,lp(-2,-2))
    }
    // 左右在行内走（走到两端停在原地），向上回到输入框（它就在这一行正上方）。
    wireChipRow(chips,{ searchInput })
    // 向下进内容第一张卡；没有结果时停在原地，不会变成「按下没反应」。
    val first=gridCards().firstOrNull()?.id
    chips.forEach { it.nextFocusDownId=first ?: it.id }
    // 导航栏里那几件（输入框 / 搜索 / 历史 / 手机输入）按下键先落进这一行
    // （这一行就在它们正下方）。
    val down=chips.first().id
    searchInput?.nextFocusDownId=down
    searchButton?.nextFocusDownId=down
    searchActions.forEach { it.nextFocusDownId=down }
    // 这一行是异步铺出来的，铺好的那一刻栅格可能还没建（那时只能指向自己）；
    // 等这一帧布局跑完再补一次，把下键接到内容第一张卡上。
    scroll.post {
        if(suggestRow!==scroll || scroll.visibility!=View.VISIBLE) return@post
        gridCards().firstOrNull()?.let { card -> chips.forEach { it.nextFocusDownId=card.id } }
    }
    suggestButtons.addAll(chips)
    scroll.visibility=View.VISIBLE
}
internal fun MainActivity.runSearch(value: String) {
    val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
    (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
    library.rememberSearch(clean); query=clean; tab=1; page=1; catalogFocus=""; showCatalog(true)
}
internal fun MainActivity.showSearchHistory(anchor: View) {
    val history=library.searches()
    if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
    tvTools.choose("搜索历史",history.map { it.query },anchor,{ index -> runSearch(history[index].query) },"清空历史") {
        tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
    }
}
internal fun MainActivity.manageHistory(series: Series,anchor: View?) {
    tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
        when(index) {
            0 -> openDetail(series,true)
            1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
            2 -> deleteHistoryRecord(series,anchor)
        }
    })
}
/**
 * 本机记录 → 云端同步（尽力而为）。
 *
 * 账号参数可用时，本机的「收藏」与「观看记录」同时推给平台：收藏增删走
 * `bookshelf/add` / `bookshelf/video/update`，观看进度走 `read_progress/upload`
 * （`write_mode=1` 会一并写进云端历史）。没配参数、或请求失败都**静默放弃**：
 * 本机记录已经落盘，云端只是补一份，不该在遥控器上弹提示打断播放。
 *
 * **先改本地那份账号数据、再发网络请求**：账号侧的列表（「我的」页四栏、首页那两栏、
 * 集合页补在后面的那一段）都读 [accountData]，只在第一次进页面时联网取过一次。
 * 不就地改它的话，在本机收藏 / 播放 / 删记录之后再回到「我的」页，读到的还是那份旧快照——
 * 明明已经同步到云端了，页面上却还是旧样子。
 */
