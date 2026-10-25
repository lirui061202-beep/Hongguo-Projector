// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.list

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
import com.hgtv.star.tv.IconButton
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.focusBleed
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.ACCOUNT_TABS
import com.hgtv.star.CHIP_ROW_HEIGHT
import com.hgtv.star.GRID_COLUMNS
import com.hgtv.star.LOAD_MORE
import com.hgtv.star.MainActivity
import com.hgtv.star.BuildConfig
import com.hgtv.star.registerDeviceOnce
import com.hgtv.star.startDramaBrowser
import com.hgtv.star.TAB_CATEGORY
import com.hgtv.star.TAB_HOME
import com.hgtv.star.TAB_PROFILE
import com.hgtv.star.TAB_RANK
import com.hgtv.star.TAB_SEARCH
import com.hgtv.star.TAB_SETTINGS
import com.hgtv.star.accountItems
import com.hgtv.star.accountNotice
import com.hgtv.star.addButton
import com.hgtv.star.appendCatalog
import com.hgtv.star.autoAppendCatalog
import com.hgtv.star.base
import com.hgtv.star.chipControl
import com.hgtv.star.column
import com.hgtv.star.dp
import com.hgtv.star.firstHeadControl
import com.hgtv.star.focusHead
import com.hgtv.star.fullList
import com.hgtv.star.gapInto
import com.hgtv.star.gridContentWidth
import com.hgtv.star.homeShelves
import com.hgtv.star.inlineHeadChips
import com.hgtv.star.invalidatePage
import com.hgtv.star.loadAccount
import com.hgtv.star.loadHotSearches
import com.hgtv.star.localCatalog
import com.hgtv.star.localItems
import com.hgtv.star.lp
import com.hgtv.star.manageHistory
import com.hgtv.star.message
import com.hgtv.star.navEntry
import com.hgtv.star.navIcons
import com.hgtv.star.pageBleed
import com.hgtv.star.pageTitle
import com.hgtv.star.profileHead
import com.hgtv.star.profileHeadControl
import com.hgtv.star.rankSource
import com.hgtv.star.releaseCatalogViews
import com.hgtv.star.renderHome
import com.hgtv.star.restoreChipFocus
import com.hgtv.star.row
import com.hgtv.star.scheduleSuggestions
import com.hgtv.star.searchBar
import com.hgtv.star.settings
import com.hgtv.star.showHint
import com.hgtv.star.switchAccountTab
import com.hgtv.star.switchCategoryGroup
import com.hgtv.star.switchCategoryTag
import com.hgtv.star.switchRankBoard
import com.hgtv.star.switchTab
import com.hgtv.star.syncHistoryClear
import com.hgtv.star.text
import com.hgtv.star.wireChipRow
import com.hgtv.star.wireHeadNavUp
import com.hgtv.star.work
import com.hgtv.star.error

// 本文件是从 MainActivity.kt 拆出来的「列表页（分类 / 搜索 / 榜单）整页渲染与栅格排布」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false,keepChip: Boolean=false) {
    // 不取远端内容的这一次重建没有「内容落地」这一步，意图要清掉：
    // 否则它会留到下一次无关的落地，把焦点莫名其妙地推到第一张卡上。
    //
    // [keepChip] 是例外：「我的」页切栏目（收藏 / 历史 / 点赞 / 预约）内容全在内存里，
    // 不需要联网，但焦点必须**留在刚点的那枚芯片上**。少了这个开关，这次重建会把
    // [chipFocusKey] 清掉，焦点于是跟着栅格兜底跑到第一张卡上——用户读到的就是
    // 「切一栏焦点就跳进内容区」。
    if(!load && !keepChip) { focusFirstCardOnLanding=false; chipFocusKey=null }
    initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
    // 榜单页与搜索结果从第 2 页起是「向下追加」：保留已经显示的剧集，只把新的一页接在后面。
    val appending=load && appendCatalog() && page>1 && catalog.isNotEmpty()
    // 首页的四个剧架不是「一页列表」：它们来自四个榜单各自的第 1 页，
    // 因此首屏先把上次缓存铺上（四个榜单各一份），后台再更新。
    val home=tab==TAB_HOME && collection==null && page==1
    // Never cache a new page number with the previous page's results while a request is pending.
    if(load) {
        if(home) {
            val cached=RankBoard.entries.mapNotNull { board -> library.cachedHome(board)?.let { board to it } }.toMap()
            homeFeed=LinkedHashMap(cached)
            homeFromCache=cached.isNotEmpty()
            catalog=emptyList(); hasMore=false; ranking=null
        } else if(!appending && chipFocusKey==null) { catalog=emptyList(); hasMore=false; ranking=null }
    }
    invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
    // 换榜单 / 换分类的请求还在路上时切走了标签（或在搜索页重新搜）：那一次的落地会被
    // [invalidatePage] 作废，落点意图因此永远等不到消费，必须在这里清掉——
    // 留着它会让新页面一直「芯片握着焦点」（不预取下一页、不恢复位置）。
    // 判定按「这一页认不认这枚芯片」来：分类页的意图不能跟到榜单页上，反之亦然。
    val ownsChip=when(tab) {
        TAB_RANK -> chipFocusKey?.startsWith("board:")==true
        TAB_CATEGORY -> chipFocusKey?.startsWith("group:")==true || chipFocusKey?.startsWith("tag:")==true
        TAB_PROFILE -> chipFocusKey?.startsWith("account:")==true
        else -> false
    }
    if(!ownsChip) chipFocusKey=null
    if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
    // 顶部导航栏：左侧是页面名（首页是品牌「红果TV」，二级界面与榜单页显示自己的名字），
    // 右侧是这一页自己的动作与 4 个圆形图标入口（刷新 / 搜索 / 排行榜 / 设置）。
    // 整条**不画底板**：没有边框、也没有可以按进去的面板，文字直接落在内容线上；
    // 品牌是标识而不是按钮，因此不参与焦点链（不可选中、不可点击），回主界面一律走返回键。
    // 高度固定 TOOLBAR_HEIGHT：页头收展动画直接改这一行的高度，不用再跟 minimumHeight 抢。
    val top=row().apply {
        // 左边与内容线对齐；右边少让一条柔光带，圆形入口的可见圆盘才正好压在内容线上。
        setPadding(pageBleed(),0,pageBleed()-dp(TvStyle.SOFT_BAND),0)
    }
    top.addView(text(pageTitle(),30f,TvStyle.textStrong).apply {
        setTypeface(typeface,Typeface.BOLD)
        setPadding(0,0,dp(4),0)
    },lp(-2,-2))
    val filterSlot=row()
    gapInto(top,filterSlot,lp(-2,-2),TvStyle.GAP_GROUP)
    // 二级界面的动作跟着标题走同一条：标题进了导航栏，动作也放进来，页头下面不再单独占一行。
    if(collection=="history" && library.history().isNotEmpty()) historyManage=addButton(top,"管理记录") {
        val rows=library.history()
        tvTools.choose("选择要管理的历史记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
            tvTools.confirm("清空历史记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); syncHistoryClear(); catalogFocus=""; showCatalog() }
        }
    }
    // 搜索页：搜索框与「搜索 / 历史 / 手机输入」就在导航栏里（用户不用先按一下才够得着），
    // 输入框吃掉中间那段弹性空间；其余页面这里是一条弹性空白。
    if(tab==TAB_SEARCH) searchBar(top,query.isBlank() && !load && !focusNav)
    else top.addView(Space(this),lp(0,1).apply { weight=1f })
    // 顶部只有四个圆形入口，顺序固定：搜索 → 分类 → 榜单 → 设置。
    // 「刷新」按钮整个去掉了：首页、榜单、分类、搜索都是「进页面 / 滚到顶 / 换榜单 / 换分类」时自己重取，
    // 留着它只是多一个入口（职责已经被这些动作覆盖）。
    listOf(
        com.hgtv.star.R.drawable.ic_toolbar_search to "搜索",
        com.hgtv.star.R.drawable.ic_toolbar_category to "分类",
        com.hgtv.star.R.drawable.ic_toolbar_rank to "榜单",
        com.hgtv.star.R.drawable.ic_toolbar_settings to "设置",
        com.hgtv.star.R.drawable.ic_toolbar_profile to "我的"
    ).forEachIndexed { index,entry ->
        val item=IconButton(this,entry.first,entry.second) { switchTab(index+1) }
        item.setActive(index+1==tab)
        nav+=item
        gapInto(top,item,lp(-2,-2),TvStyle.GAP_BLOCK)
    }
    container.addView(top,lp(-1,dp(TvStyle.TOOLBAR_HEIGHT)))
    registerTopAreaRow(top)
    // 圆形入口建好了：把搜索页导航栏里那几件的「按上」接回它们（见 [wireHeadNavUp]）。
    if(tab==TAB_SEARCH) wireHeadNavUp()
    // 页面内提示行：应用里不弹系统 Toast，需要「刚刚发生了什么」这类反馈时显示这一行淡字。
    // 它不参与焦点、不盖画面，几秒后自己消失；页面重建时按 [catalogHint] 复原。
    val hintView=text("",14f,TvStyle.textDim).apply {
        setPadding(pageBleed(),dp(4),pageBleed(),dp(2))
        visibility=if(catalogHint==null) View.GONE else View.VISIBLE
        text=catalogHint.orEmpty()
    }
    catalogHintView=hintView
    container.addView(hintView,lp(-1,-2))
    // 「我的」页的身份行：圆形头像 + 昵称 + 重新导入 / 清除。
    // 放在导航栏下面（与分类页的二级分类同一层）：上面一行是「看哪一栏」，这一行是「这是谁」。
    if(tab==TAB_PROFILE) {
        val headRow=row().apply { setPadding(pageBleed(),0,pageBleed()-dp(TvStyle.SOFT_BAND),dp(4)) }
        profileHead(headRow)
        profileHeadRow=headRow
        container.addView(headRow); registerTopAreaRow(headRow)
    }
    if(tab==TAB_SETTINGS) {
        val scroll=ScrollView(this).focusBleed(); val body=column(); scroll.addView(body); container.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
        listScrollY=scroll.scrollY
        scroll.setOnScrollChangeListener { _,_,y,_,_ -> onListScroll(y) }
        settings(body); if(focusNav) navEntry()?.requestFocus(); return
    }
    // 「我的」页的四个栏目芯片（收藏 / 历史 / 点赞 / 预约）：**完全照分类页的一级分类那一套**——
    // 屏幕够宽就塞进导航栏（标题右边、那排圆形入口左边），窄了退回导航栏下面单独一行。
    // 内容区走统一的卡片栅格（见 [seriesList] 里 TAB_PROFILE 那一支），排版与榜单页一致。
    if(tab==TAB_PROFILE) {
        val chips=mutableListOf<View>()
        ACCOUNT_TABS.forEach { entry ->
            val chip=ToggleChip(this,entry.second,accountTab==entry.first) { switchAccountTab(entry.first) }
            accountButtons[entry.first]=chip
            chips+=chip
        }
        // 芯片在导航栏那一行时，往右走到最后一枚接同一视觉行里紧跟着的那排圆形入口。
        val afterChips=if(inlineHeadChips()) nav.firstOrNull() else null
        wireChipRow(chips,{ chips[it] },afterChips)
        if(inlineHeadChips()) {
            chips.forEach { gapInto(filterSlot,it,lp(-2,-2),TvStyle.GAP_INLINE) }
        } else {
            val chipRow=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(4)) }
            chips.forEach { gapInto(chipRow,it,lp(-2,-2)) }
            container.addView(chipRow); registerTopAreaRow(chipRow)
        }
        // 导航栏那一行的按下键一律落到**身份行**（头像 / 「导入账号参数」/ 重新导入 …），
        // 那一行就在导航栏正下方。指到同一行的芯片上会让焦点在导航栏那一行里打转，
        // 指到内容第一张卡上又会跳过身份行——未登录时这里只有「导入账号参数」一个入口，
        // 用户读到的就是「焦点在顶部的按钮上，按向下到不了导入按钮」。
        // 芯片同一条：按下键也进身份行，不再指向自己（内容为空时没有任何后续接线能改它）。
        val headDown=profileHeadControl()?.id ?: accountButtons.getValue(accountTab).id
        navIcons().forEach { it.nextFocusDownId=headDown }
        accountButtons.values.forEach { it.nextFocusDownId=headDown }
        restoreChipFocus("account:")
    }
    // 榜单页的四个榜单切换芯片：放进榜单名那一行（屏幕窄 / 字号大时退回下面单独一行）。
    // 首页不再有「漫剧 / 短剧」开关：四个榜单本身就是首页的四栏，分类由栏目标题表达。
    if(tab==TAB_RANK) {
        val boards=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(4)) }
        val boardChips=mutableListOf<View>()
        RankBoard.entries.forEach { board ->
            // 四个按钮分别是 漫剧 / 短剧 / 真人 / AI剧；页面主标题固定写「热播榜」（见 [pageTitle]）。
            val chip=ToggleChip(this,board.label,rankBoard==board) { switchRankBoard(board) }
            boardButtons[board]=chip
            boardChips+=chip
            gapInto(boards,chip,lp(-2,-2))
        }
        // 这一行就在页头最上面：按上键停在原地（以前会跳到导航栏的「榜单」图标上，
        // 用户读到的就是「已经在最上面了还换焦点」）。右移在四枚之间切换，
        // 走到最后一枚接同一视觉行里紧跟着的那排圆形入口。
        // 芯片就在导航栏里时，往右走到最后一枚接着是那排圆形入口（同一视觉行，方向连续）；
        // 退回下面单独一行时不接（那时它在自己的一行里，右到头就是到头）。
        val afterBoards=if(inlineHeadChips()) nav.firstOrNull() else null
        wireChipRow(boardChips,{ boardChips[it] },afterBoards)
        if(inlineHeadChips()) {
            boards.setPadding(0,0,0,0); filterSlot.addView(boards)
        } else {
            container.addView(boards)
        }
        navIcons().forEach { it.nextFocusDownId=boardButtons.getValue(rankBoard).id }
        // 没有内容可落时（空榜单 / 加载中）这一下也不会变成「按下没反应」：
        // 先让它指向自己，内容铺好之后由 `wireGridFocus` 改成第一张卡。
        boardButtons.values.forEach { it.nextFocusDownId=it.id }
        // 点了另一枚榜单芯片：焦点立刻交回那一枚，不等内容落地、也不经过别的控件。
        restoreChipFocus("board:")
    }
    if(tab==TAB_SEARCH) {
        // 输入框下面那一行：**没有输入时是热搜词，打字时换成提示词**。
        // 固定高度、宽度与内容区对齐（见 [CHIP_ROW_HEIGHT]），因此这一行换来换去
        // 不会让下面的卡片行上下跳；两者都没有时整行隐藏，不留空槽。
        val hintScroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false
            clipChildren=false; clipToPadding=false
            isFillViewport=true
            visibility=View.GONE
        }
        val hintLine=row().apply { gravity=Gravity.CENTER_VERTICAL }
        hintScroll.addView(hintLine)
        container.addView(
            hintScroll,
            lp(gridContentWidth(),dp(CHIP_ROW_HEIGHT)).apply { leftMargin=pageBleed() }
        )
        registerTopAreaRow(hintScroll)
        suggestRow=hintScroll; suggestLine=hintLine
        // 进搜索页先把热搜词铺上（还没有输入时这一行就是它）；输入框变化时在下面接管。
        if(query.isBlank()) loadHotSearches() else scheduleSuggestions(query)
    } else if(tab==TAB_CATEGORY) {
        // 分类页的**一级分类进导航栏**（与榜单页的四个榜单同一套做法），下面只留一行二级分类。
        // 页面主标题写「分类」，当前选中的小类写在这一行的右侧（一行淡字）。
        val group=CategoryCatalog.groupOf(categoryPath) ?: CategoryCatalog.groups.first()
        val groupChips=mutableListOf<View>()
        CategoryCatalog.groups.forEach { option ->
            val chip=ToggleChip(this,option.label,option==group) { switchCategoryGroup(option) }
            categoryGroupButtons[option]=chip
            groupChips+=chip
        }
        // 一级分类与四个榜单一样在页头最上面：按上键停在原地，左右在行内走，
        // 走到最后一枚接同一视觉行里紧跟着的那排圆形入口。
        val afterGroups=if(inlineHeadChips()) nav.firstOrNull() else null
        wireChipRow(groupChips,{ groupChips[it] },afterGroups)
        // 「漫剧 · 玄幻」这行淡字跟着一级分类走：一级分类在导航栏里时它也放那儿，
        // 退回单独一行时它跟在芯片后面（不进二级分类那一行的横向滚动区，否则会被滚出去）。
        val describe=text(CategoryCatalog.describe(categoryPath),12f,muted)
        if(inlineHeadChips()) {
            groupChips.forEach { gapInto(filterSlot,it,lp(-2,-2),TvStyle.GAP_INLINE) }
            gapInto(filterSlot,describe,lp(-2,-2),TvStyle.GAP_BLOCK)
        } else {
            val groupRow=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(2)) }
            groupChips.forEach { gapInto(groupRow,it,lp(-2,-2)) }
            gapInto(groupRow,describe,lp(-2,-2),TvStyle.GAP_BLOCK)
            container.addView(groupRow); registerTopAreaRow(groupRow)
        }
        // 二级分类那一行：**固定高度**（与下面的卡片行同宽、同一内容线）。
        // 高度不跟着芯片数量 / 是否换行变，换一级分类时这一块不会忽高忽低，下面的卡片不会上下跳。
        // 不给行内边距：芯片自己的柔光带就是它与内容线的间距（与海报卡同一档），
        // 因此第一枚芯片的可见边界与下方第一张海报的左边界落在同一条线上。
        val tagRow=row().apply {
            gravity=Gravity.CENTER_VERTICAL
        }
        val tagChips=mutableListOf<View>()
        group.tags.forEach { tag ->
            // 小类（二级分类）整体小一档：高度 40dp、字号 15sp、左右内边距 18dp（大类是 48/16/28）。
            val chip=ToggleChip(this,tag.label,tag.path==categoryPath,ButtonSize.COMPACT) { switchCategoryTag(tag) }
            categoryTagButtons[tag.path]=chip
            tagChips+=chip
            gapInto(tagRow,chip,lp(-2,-2))
        }
        // 二级分类：左右在行内走（走到两端停在原地），向上回到它所属的一级分类芯片。
        // 它自己单独占一行（上面还有一行一级分类 / 导航栏），因此往右不接任何别的控件。
        wireChipRow(tagChips,{ categoryGroupButtons[group] ?: tagChips[it] })
        // 小类多的目录（真人 24 个）一行放不下：整行横向可滚，左右键走到行末继续推。
        val tagScroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false
            clipChildren=false; clipToPadding=false
            // 固定高度 + 撑满视口：里面的 [tagRow] 拿到整行高度，芯片靠它的 gravity 垂直居中。
            // 少了这一条，芯片会贴在行的上边缘，换一级分类时看着像整块在往上跳。
            isFillViewport=true
        }
        tagScroll.addView(tagRow)
        // 宽度与下面那一排卡片**逐像素对齐**：容器自己让出的是 pageInset()-pageBleed()，
        // 这里补回 pageBleed()，左右边界就正好落在卡片行的内容线上（[gridContentWidth]）。
        container.addView(
            tagScroll,
            lp(gridContentWidth(),dp(CHIP_ROW_HEIGHT)).apply { leftMargin=pageBleed() }
        )
        registerTopAreaRow(tagScroll)
        // 二级分类那一行固定高度：内容按 [CHIP_ROW_HEIGHT] 垂直居中，因此换一级分类时
        // 这一块的高度不变，下面的卡片行不会跟着上下跳。
        navIcons().forEach { it.nextFocusDownId=categoryGroupButtons.getValue(group).id }
        // 一级分类按下键进二级分类那一行；二级分类按下键进内容第一张卡
        // （内容铺好之后由 `wireGridFocus` 接上真正的第一张卡）。
        groupChips.forEach { it.nextFocusDownId=categoryTagButtons[categoryPath]?.id ?: it.id }
        tagChips.forEach { it.nextFocusDownId=it.id }
        // 点了大类或小类芯片：焦点立刻交回那一枚（换大类时小类那一行整个换掉，
        // 焦点也必须落在新的小类芯片上，不能留在第一枚大类上）。
        restoreChipFocus("group:")
        restoreChipFocus("tag:")
    } else if(tab==TAB_RANK) {
        // 榜单名已经进了导航栏，这一行只剩来源 / 更新时间；
        // 「刷新榜单」按钮去掉：进页面、切榜单、滚到顶都会重新取，不需要单独一个入口。
        val subtitle=text(if(load) "正在读取最新榜单…" else ranking?.updatedText?.ifBlank { rankSource() } ?: rankSource(),13f,muted)
            .apply { setPadding(pageBleed(),0,pageBleed(),dp(8)) }
        rankSubtitle=subtitle
        container.addView(subtitle); registerTopAreaRow(subtitle)
    }
    val body=column(); catalogBody=body; container.addView(body,lp(-1,0).apply { weight=1f })
    if(localCatalog()) {
        // 本机集合页：一次取全（本机 + 账号合并），不分页。
        // 账号那半份可能还没拉到（首次进这一页），那就先用本机的，等 [loadAccount] 落地后重建。
        catalog=localItems(); ranking=null; page=1; hasMore=false
        if(collection!=null && accountData==null && accountError==null) loadAccount()
    }
    // 本机集合页没有远端列表要取（内容是「本机 + 账号」合并出来的），load 对它只表示
    // 「重建一次」：直接走下面的栅格分支，别掉进远端请求那条路（那会把搜索结果塞进 catalog）。
    if(load && !localCatalog()) {
        // 换榜单 / 换分类（点了芯片）时**不把列表清空**：旧的一屏留在原位，等新的一页落地再换。
        // 清空的话这一整段时间页面上只剩一行「正在加载…」，观感就是「每次选分类都闪一下」。
        // 位置也照旧：[releaseCatalogViews] 刚把旧列表的滚动位置记在它自己的 key 上
        // （此刻 [catalogPageKey] 还没换），照搬过来才不会先跳回顶部再被新内容替换。
        val stale=chipFocusKey!=null && appendCatalog() && catalog.isNotEmpty()
        // 芯片握着焦点时（[chipFocusKey] 非空）一律不许把焦点交给页头 / 顶部入口：
        // 用户刚点的那一枚芯片就是落点，这一页剩下的重建都不该动它。
        val holdChip=chipFocusKey!=null
        if(appending) seriesList(body,focusNav,focusType,loadingMore=true)
        else if(home) seriesList(body,focusNav,focusType,loading=true)
        else if(stale) seriesList(body,if(holdChip) false else focusNav,focusType,keepScroll=scrollPositions[catalogPageKey])
        else {
            message(body,"正在加载…")
            // 加载期间焦点必须有一个**确定的落点**：整页重建之后，刚按下的那枚芯片已经离树，
            // 不管的话系统会把焦点交给新布局里第一个可聚焦控件（页头那排入口 / 芯片）。
            // 点了芯片就把焦点交回那枚芯片（从头到尾不动）；否则落在页头控件上，
            // 内容一到再由 [focusFirstCardOnLanding] 把焦点交给第一张卡。
            val chip=chipControl(chipFocusKey)
            if(chip!=null) chip.requestFocus()
            else if(focusType || focusFirstCardOnLanding) firstHeadControl()?.requestFocus()
        }
        val requestTab=tab; val requestPage=page; val requestQuery=query; val requestBoard=rankBoard
        val requestCategory=categoryPath
        work({
            // 首页一次取四个榜单（并发，见 homeFeed）；榜单页只取当前这一个；
            // 分类页取当前小类的第 N 页；搜索页走搜索。四者都用同一个「请求 key → 页面」的形状，
            // 因此下面的落地逻辑（追加 / 换页 / 报错）只有一份。
            when(requestTab) {
                TAB_HOME -> if(BuildConfig.APPLICATION_ID.endsWith(".projector")) {
                    registerDeviceOnce()
                    repository.projectorFeed()
                } else repository.homeFeed()
                TAB_RANK -> mapOf(requestBoard to repository.rank(requestPage,requestBoard))
                TAB_CATEGORY -> mapOf(requestBoard to repository.category(requestCategory,requestPage))
                else -> mapOf(requestBoard to repository.search(requestQuery,requestPage))
            }
        }, { result ->
            if(requestTab==TAB_HOME) {
                // 四个剧架一起换。首屏可能是上次缓存（[homeFromCache]），
                // 新内容到了**就地更新剧架**（[HomeScreen.updateShelves] 会保留当前卡片与滚动位置），
                // 而不是整页重画——用户正在看的那一栏不会跳。
                val first=homeFeed.isEmpty()
                homeFeed=LinkedHashMap(result)
                result.forEach { (board,page) -> library.cacheHome(board,page.items,page.hasMore) }
                if(projectorAutoBrowse && homeFeed.values.any { it.items.isNotEmpty() }) {
                    startDramaBrowser()
                    return@work
                }
                val keepNav=nav.any { it.hasFocus() }
                val homeView=homeScreen
                // 首屏（还没有任何剧架）整页铺；已经有剧架就地更新——`updateShelves` 默认
                // 「焦点本来在剧架上就把焦点按回原卡片」，因此用户正在看的那一栏不会跳、也不会丢焦点。
                focusFirstCardOnLanding=false
                if(first || homeView==null) {
                    // 首页四栏：焦点交给第一栏第一张卡（keepNav 为真时留在顶部入口）。
                    body.removeAllViews(); seriesList(body,keepNav || focusNav)
                } else homeView.updateShelves(homeShelves())
                return@work
            }
            val page=result[requestBoard] ?: CatalogPage(emptyList(),false)
            if(appending) {
                // 追加：只接住还没出现过的剧集，并把这一页的名次与热度并进已有榜单。
                val added=page.items.filterNot { item -> catalog.any { it.id==item.id } }
                val wanted=moreFocus; moreFocus=null
                catalog=catalog+added; ranking=mergeRanking(ranking,page.ranking); hasMore=page.hasMore
                rankSubtitle?.text=ranking?.updatedText?.ifBlank { rankSource() } ?: rankSource()
                // 栅格还活着（空页那次重建之后又追加）就只往下接，不整页重画。
                if(gridList!=null && catalogBody===body) appendGridItems(added,wanted)
                else { body.removeAllViews(); seriesList(body,false) }
            } else {
                catalog=page.items; ranking=page.ranking; hasMore=page.hasMore
                rankSubtitle?.text=ranking?.updatedText?.ifBlank { rankSource() } ?: rankSource()
                // 焦点落点按**显式意图**决定（见 [focusFirstCardOnLanding]）：换了一份列表
                // （换榜单 / 换分类 / 从首页进榜单）就不要留在页头——`focusNav` 置 false 之后，
                // seriesList 末尾的兜底会把焦点交给第一张卡（`catalogFocus` 在换列表时已清空）。
                // 其余情况保留用户当前在页头的位置（切标签回来看同一页等）。
                val landFirst=focusFirstCardOnLanding; focusFirstCardOnLanding=false
                // 点了芯片的这一次（[chipFocusKey] 非空）同样不要把焦点交给第一张卡：
                // 用户还在挑榜单 / 挑分类，落点就是他刚点的那一枚芯片。
                val holdChip=chipFocusKey!=null
                val keepNav=!holdChip && !landFirst && nav.any { it.hasFocus() }
                // [keepFocus]：新的一页铺好之后**一个焦点都不许动**——用户刚点的那一枚芯片
                // 就是落点，内容落地不该把焦点带去第一张卡（那正是「焦点跳到别处」的来源）。
                body.removeAllViews(); seriesList(body,if(holdChip) false else keepNav || focusNav,keepFocus=holdChip)
                // 新的一页已经铺好、焦点也已交回芯片，这一轮的意图到此结束。
                chipFocusKey=null
                // 上面那次重建刻意跳过了预取（那时屏上还是旧列表），这里补一次：
                // 用户第一次按下键时下一页的内容已经在手上。
                if(holdChip) prefetchNextPage()
            }
        }, { problem ->
            if(requestTab==TAB_HOME) {
                if(homeFeed.isNotEmpty()) { showHint("热门暂时取不到，仍在显示上次内容"); return@work }
                // 一栏都没有：只剩提示行会是一个空页面，这里给出错误说明与「重试」。
                body.removeAllViews(); error(body,problem) { showCatalog(true) }
                return@work
            }
            if(appending) {
                // 追加失败不动已经显示的剧集，页码退回待加载的那一页，底部按钮可以直接重试。
                page=requestPage-1; moreFocus=LOAD_MORE
                rankSubtitle?.text="加载更多失败 · 当前显示 ${catalog.size} 部"
                body.removeAllViews(); seriesList(body,false)
                return@work
            }
            catalog=emptyList(); hasMore=false; ranking=null
            rankSubtitle?.text=if(tab==TAB_SEARCH) "搜索失败" else "榜单加载失败"
            if(problem is SearchSessionExpiredException) {
                page=1; catalogFocus=""
                showHint("搜索结果已过期，已回到第 1 页刷新")
                showCatalog(true)
            } else {
                body.removeAllViews()
                val retry=error(body,problem) { showCatalog(true) }
                // 取不到内容时焦点仍然留在用户刚点的那一枚芯片上（不跳到「重试」上），
                // 但「重试」要够得着：芯片按下键就是它，它按上键回芯片。
                chipControl(chipFocusKey)?.let { chip ->
                    chip.nextFocusDownId=retry.id
                    retry.nextFocusUpId=chip.id
                    chip.requestFocus()
                }
                chipFocusKey=null
            }
        })
    } else seriesList(body,focusNav,focusType)
}
/**
 * **全站唯一的列表入口**。
 *
 * 分类页、榜单页、搜索页、我的页、本机集合页、首页四栏（走 [renderHome] 那一支）都由这里铺出来，
 * 因此「哪些剧、怎么排、怎么翻页、焦点怎么走」全站只有这一份实现：
 *
 * * 卡片本身：`tv/PosterCard`（全站同一张海报卡，封面取图走 `MainActivity.loadCover`）；
 * * 排版：[SeriesGrid] 的窗口化六列栅格（列表页），或 [HomeScreen] 的横排剧架（首页 / 相关推荐）；
 * * 动作：点卡片 = [openSeries]（进详情 / 续播），长按或菜单键 = [seriesMenu]（剧卡菜单）；
 * * 行为：向下滚动到底按 [requestNextPage] 取下一页；焦点、滚动位置、页头收展都在这一套里。
 *
 * 新加一个「剧集列表」页面时**不要**另写一套列表：把数据交给这里，用 tab / collection 决定来源即可。
 */
internal fun MainActivity.seriesList(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false,loadingMore: Boolean=false,keepScroll: Int?=null,keepFocus: Boolean=false) {
    // 「这一次重建是点了榜单 / 分类芯片引起的」要在这里就定下来：下面的焦点落点是
    // `post` 里跑的，等它执行时 [chipFocusKey] 可能已经被落地逻辑消费掉了，
    // 那时再读就守不住「不许抢焦点」这条规则。
    val chipHeld=chipFocusKey!=null || keepFocus
    // 这一页会新建自己的栅格：旧的活引用先作废（首页剧架不是栅格，这里同样清干净），往下重建。
    gridList=null; gridScroll=null; gridPaging=null; moreCount=null; pagingHasMore=false
    gridWindow=null; gridTopSpacer=null; gridBottomSpacer=null
    gridItems=emptyList(); gridRowStride=0; gridWindowStart=-1
    gridRows.clear(); gridRowPool.clear(); gridCardWidth=0; gridGap=0; gridPosterHeight=0
    gridRestoring=true
    if(tab==TAB_HOME && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
    // 「我的」页：把当前栏目的条目填进 [catalog]，下面走**同一套**卡片栅格
    // （[PosterCard] + 窗口化 + 六列排版），因此它与榜单页的卡片列表完全一致。
    // 资料还没拿到 / 没通过验证时 catalog 是空的，下面的空列表分支会铺 [accountNotice]。
    if(tab==TAB_PROFILE) { catalog=accountItems(accountTab); hasMore=false; ranking=null }
    // 这一页会新建自己的页头行与底部按钮，先把上一页留下的引用清掉：
    // 让已经离树的控件继续当焦点目标，焦点请求会失败，焦点就可能落到页头的按钮上，
    // 页头于是被重新展开——观感就是「加载更多之后顶部那排按钮又显示出来」。
    val wanted=moreFocus; moreFocus=null
    moreButton=null
    // 原地重建列表（refreshLibraryCards）会把上一次的页头行摘下来，这里顺手清掉，别留着已经离树的引用。
    topArea.filter { it.parent==null }.forEach { row ->
        topAreaAnimators.remove(row)?.cancel()
        topAreaHeights.remove(row); topAreaRestore.remove(row)
    }
    topArea.removeAll { it.parent==null }
    // 集合页的标题与「检查更新 / 管理记录」都搬进了顶部导航栏，这里只剩收藏页的更新状态行。
    if(collection=="favorites") {
        val status=text(favoriteMonitor.status,13f,muted).apply { setPadding(pageBleed(),0,pageBleed(),dp(4)) }
        favoriteStatus=status
        body.addView(status); registerTopAreaRow(status)
    }
    // 远端长列表是向下追加的同一份内容，滚动位置不能跟着页码走，否则每次「展示更多」都会跳回顶部。
    // 向下追加的长列表（榜单 / 分类 / 搜索）位置不跟着页码走，因此 key 里页码固定为 1；
    // 分类页还要带上当前小类，否则换分类会沿用上一条的滚动位置。
    catalogPageKey=buildString {
        append(tab); append(':'); append(collection.orEmpty()); append(':')
        append(if(appendCatalog() || fullList()) 1 else page); append(':')
        if(tab==TAB_CATEGORY) append(categoryPath).append(':')
        if(tab==TAB_PROFILE) append(accountTab).append(':')
        append(query)
    }
    val scroll=ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=false }.focusBleed(); catalogScroll=scroll
    val list=column(); scroll.addView(list); body.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
    // 页头收展：恢复上次滚动位置的那一次不算，免得一进页面页头就闪一下。
    listScrollY=scroll.scrollY
    // 列表重建期间产生的滚动（位置恢复、焦点回带、内容夹取）都不是用户滚动：
    // 静默期内不改页头状态，否则每次「展示更多」重建页面都会把这些滚动当成「滚到顶了」，
    // 页头于是又展开一次。
    topAreaSettleUntil=android.os.SystemClock.elapsedRealtime()+TvStyle.DURATION+120
    scroll.setOnScrollChangeListener { _,_,y,_,_ ->
        if(catalogScroll!==scroll || gridRestoring) return@setOnScrollChangeListener
        // 先按新的滚动位置把窗口挪好，再交给页头收展 / 预取那套逻辑。
        // 窗口外的那几百行根本不存在，因此这一步与「已加载多少」无关。
        syncGridWindow()
        onListScroll(y)
    }
    // 远端长列表不切页：catalog 就是已经向下追加出来的整份列表，一次全画出来。
    // 首页与热门隐藏掉本机标为「不感兴趣」的剧；搜索结果不做这层过滤（搜到就是要看）。
    val displayed=when {
        // 集合页与「我的」页：一次全量铺出来（窗口化只物化视口附近那几行），不分页、不截断。
        // 这两类列表不过滤「不感兴趣」：本机记录与账号里的收藏 / 历史是用户自己的东西，
        // 本机那套隐藏规则是给推荐位用的。
        fullList() -> catalog
        tab==TAB_HOME || tab==TAB_RANK || tab==TAB_CATEGORY -> catalog.filterNot { it.id in library.hidden() }
        appendCatalog() -> catalog
        else -> catalog.take(30)
    }
    if(displayed.isEmpty()) {
        // 「我的」页：**资料没拿到**（未导入 / 读取中 / 读取失败 / 参数无效）时铺那一套说明与按钮；
        // 资料拿到了只是这一栏为空，就走下面那句按栏目区分的空文案，不再摆「参数无效」。
        if(tab==TAB_PROFILE && accountData?.profile==null) {
            accountNotice(list)
            focusFirstCardOnLanding=false
            ensureFocusView { chipControl(chipFocusKey) ?: firstHeadControl() ?: navEntry() }
            return
        }
        message(list,if(loading) "正在加载推荐…" else when {
            // 搜索页的「怎么打字」在这里也说一次：这是没有结果时用户唯一能看到的说明。
            tab==TAB_SEARCH -> if(query.isBlank()) "输入关键词后按「搜索」；遥控器用方向键选字，也可外接键盘" else "没有找到相关剧集，换个关键词试试"
            tab==TAB_CATEGORY -> "${CategoryCatalog.describe(categoryPath)}暂时没有内容"
            collection=="favorites" -> "在剧集详情中选择收藏，喜欢的剧就会出现在这里"
            collection=="history" -> "播放过的剧集会自动保存在这里"
            // 没导入凭据时不能写「账号里还没有…」——那句话读起来像「这个账号是空的」，
            // 而真实原因是**这些功能要凭据才能用**（见 [SignInRequiredException]）。
            tab==TAB_PROFILE -> if(!repository.signedIn) "导入账号参数后，这里会显示云端的内容" else when(accountTab) {
                "favorites" -> "账号里还没有收藏的剧集"
                "history" -> "账号里还没有观看记录"
                "likes" -> "账号里还没有点赞的剧集"
                else -> "账号里还没有预约的剧集"
            }
            else -> "本页没有更多内容"
        })
        // 一页内容都没有（空页）时没有栅格可追加，这里的入口退回整页重建。
        // 搜索结果不给按钮：往下滚就自动接上，这里只提示还剩多少没显示。
        if(appendCatalog() && hasMore && !autoAppendCatalog()) addButton(list,moreLabel()) { loadMore() }
        if(autoAppendCatalog() && hasMore) message(list,"向下滚动会自动加载更多")
        // 空页没有卡片可落。**只在焦点真的丢了的时候**才补一个落点（见 [ensureFocus]）：
        // 全站原则是不主动抢焦点，内容变空不是用户动作，不该把焦点从用户正按着的地方搬走。
        focusFirstCardOnLanding=false
        ensureFocusView { chipControl(chipFocusKey) ?: firstHeadControl() ?: navEntry() }
        return
    }
    val gap=dp(6)
    val gridWidth=gridContentWidth()
    val width=((gridWidth-gap*(GRID_COLUMNS-1))/GRID_COLUMNS).toInt()
    // 与 HomeScreen.slotHeight 同一算法：先扣掉卡片 5dp 内边距，再按 134:196 换算。
    val posterHeight=((width-dp(2*TvStyle.SOFT_BAND+8))/TvStyle.POSTER_ASPECT).toInt()
    gridList=list; gridScroll=scroll; gridCardWidth=width; gridGap=gap; gridPosterHeight=posterHeight
    gridItems=displayed
    gridRowStride=0
    gridWindowStart=-1
    // 列表高度由两个 spacer 撑出来：中间只放视口附近那几行（见 [syncGridWindow]）。
    // spacer 必须是真正的 Space：它只占高度、不参与绘制、也不进焦点链。
    val topSpacer=Space(this); val window=column(); val bottomSpacer=Space(this)
    gridTopSpacer=topSpacer; gridWindow=window; gridBottomSpacer=bottomSpacer
    list.addView(topSpacer,lp(-1,0))
    list.addView(window,lp(-1,-2))
    list.addView(bottomSpacer,lp(-1,0))
    syncGridWindow(force=true)
    val paging=row(); paging.gravity=Gravity.CENTER
    var prev: View?=null; var next: View?=null
    if(appendCatalog()) {
        // 榜单页与搜索结果去掉「上一页 / 下一页 / 下一批」：只向下追加，底部留一个向下的按钮按需加载下一页。
        gridPaging=paging
        fillPagingRow(paging)
        list.addView(paging,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND+TvStyle.GAP_TIGHT)))
        next=moreButton?.takeIf { it.isFocusable }
    } else if(tab==TAB_SEARCH) {
        // 只有搜索页还留翻页按钮（它的第 2 页起走的是片库目录补页，不是向下追加）。
        // 集合页与「我的」页是一次全量铺出来的长列表，没有页码这个概念。
        prev=addButton(paging,"上一页") { if(page>1) { page--; catalogFocus=""; showCatalog() } }.apply { isEnabled=page>1; isFocusable=page>1 }
        gapInto(paging,text("第 $page 页",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(95),dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)))
        next=addButton(paging,"下一页") { if(hasMore) { page++; catalogFocus=""; showCatalog() } }.apply { isEnabled=hasMore; isFocusable=hasMore }
        list.addView(paging,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND+TvStyle.GAP_TIGHT)))
    }
    wireGridFocus(prev,next)
    // 追加一页之后把焦点交给新出现的第一张卡片；没有新卡片或加载失败就留在底部按钮上。
    // 窗口化之后新卡片往往还没物化，因此这里记的是**索引**，落地时由 [focusGridIndex] 补窗口。
    val appendedIndex=when {
        wanted==null -> null
        wanted==LOAD_MORE -> null
        else -> gridItems.indexOfFirst { it.id==wanted }.takeIf { it>=0 }
    }
    // 三种情况：换榜单 / 换分类时屏上还是**旧列表**（[keepScroll] 把它原来的位置带过来，
    // 免得点完芯片先跳一下）；换列表的新内容落地时从头开始（不沿用上一条的位置）；
    // 其余重建按这个 key 记住的位置恢复。
    val savedScroll=keepScroll ?: if(chipHeld) null else scrollPositions[catalogPageKey]
    // 位置恢复要等视口高度量到再算：视口还没测量时窗口只能按估算铺，
    // 恢复位置也会被 ScrollView 夹成 0（clamp 里 my >= child 直接返回 0），
    // 换页之后列表就跳回顶部、页头跟着展开。
    scroll.post {
        if(catalogScroll!==scroll) return@post
        val fitted=minOf(posterHeight,(scroll.height-dp(56)).coerceAtLeast(dp(120)))
        gridPosterHeight=fitted
        gridRowStride=0
        gridRows.forEach { holder -> holder.cards.forEach { card -> card.resizePoster(fitted) } }
        // 行高先量准，再恢复位置：反过来的话 spacer 还按估算高度撑，恢复出来的位置是错的。
        // 海报高度刚改过，这里量到的行高才是最终的；resizePoster 内部对同值不重设，不会白花布局。
        measureRowStride()
        savedScroll?.let { scroll.scrollTo(0,it) }
        syncGridWindow(force=true,focusIndex=gridFocusIndex())
        gridRestoring=false
        // 位置恢复期间（`gridRestoring`）的滚动不上报，这里按最终位置把页头状态摆正。
        syncHeaderState()
    }
    // 兜底：视口尺寸万一没变化、上面的 post 没跑到，静默期不能一直挂着。
    list.postDelayed({ if(catalogScroll===scroll) gridRestoring=false },400)
    // 焦点落点与位置恢复都要等视口量到：spacer 撑出来的高度、窗口范围、行高
    // 都以 `scroll.height` 为准，第一帧还没量到时算出来的是错的。
    // 页头 / 顶部入口这类「确定不依赖视口」的落点直接给，不等。
    when {
        // 这两个是**显式意图**（「加载占位时把焦点放在页头」「把焦点留在页头」），
        // 不是异步落地抢焦点，因此照旧直接落；但要先把收起的页头展开，
        // 否则焦点会停在一条高度为 0 的行上（用户读到的就是「按上没反应」）。
        focusType -> firstHeadControl()?.let { focusHead(it) }
        focusNav -> navEntry()?.let { focusHead(it) }
        else -> scroll.post {
            if(catalogScroll!==scroll) return@post
            // 点了榜单 / 分类芯片的这一次，焦点已经交回那枚芯片（见 [restoreChipFocus]），
            // 这里不许再抢：抢了就是「选完分类焦点又跳到内容区」。
            if(chipHeld) return@post
            when {
                loadingMore -> ensureFocus { moreButton?.requestFocus() }
                wanted==LOAD_MORE -> {
                    ensureFocus { moreButton?.requestFocus() }
                    revealRow(gridRowCount()-1,false)
                }
                appendedIndex!=null -> ensureFocus { focusGridIndex(appendedIndex,false) }
                // 记住过焦点的卡片（同一页重建）优先；其次落到第一张卡——
                // 「换榜单 / 换分类 / 从首页进榜单」这类换列表的动作走的就是这一条。
                else -> ensureFocus { focusGridIndex(gridItems.indexOfFirst { it.id==catalogFocus }.takeIf { it>=0 } ?: 0,false) }
            }
        }
    }
    // 首屏刚铺好就先把下一页取回来：用户第一次按下键时内容已经在手上。
    // 换榜单 / 换分类期间屏上还是旧列表，这一下要跳过：不能拿旧列表去预取新列表的下一页。
    if(!chipHeld) prefetchNextPage()
}
/**
 * 底部按钮文案。
 *
 * 静默预取不打扰用户（还是「展示更多 ↓」），只有用户自己按出来的加载才显示「正在加载更多…」。
 * 分类页与榜单页都有这个按钮（它们不是自动追加）；搜索结果没有按钮，滚到底就自动接。
 */
