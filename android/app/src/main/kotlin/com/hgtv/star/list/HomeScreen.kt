// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.list

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hgtv.star.core.Series
import com.hgtv.star.tv.PosterCard
import com.hgtv.star.tv.SectionHeading
import com.hgtv.star.tv.SoftStyle
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.focusBleed
import com.hgtv.star.tv.softBand
import com.hgtv.star.tv.tvColumn
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.tv.tvText

/**
 * 首页内容区：全部由竖版海报架子组成，七个栏目固定顺序为
 * 历史记录 / 我的收藏 / 稍后观看 / 热播漫剧 / 热播短剧 / 热播真人 / 热播AI
 * （本机没有记录的三栏、远端取不到的榜单都不占位）。
 * 每栏最多十部，本机没有记录的架子不占位，由 [render] 过滤空架子。
 *
 * 排版对齐网易爆米花：整页左右安全边距 78dp，一屏 6 张海报、行内间距 14dp，
 * 分区标题就是一行 20sp 粗体 80% 白文字（没有装饰条、没有副标题），
 * 剧名与状态放在卡片下方，因此整页不需要额外的大图预览区。
 * 一行放不下的分区在行末留一张「查看全部」底板：按右键直接进入集合页，点按同样可以进入。
 * 焦点按数据 key 保存，行内横向位置与整页纵向位置分开记忆，返回后原位恢复。
 */
class HomeScreen(
    context: Context,
    private val top: View,
    private val inset: Int,
    private val loadCover: (ImageView, String) -> Unit,
    private val open: (Series, Boolean) -> Unit,
    /** 长按 / 菜单键打开卡片菜单；[shelf] 是卡片所在剧架的 key，菜单据此决定有没有「删除记录」。 */
    private val actions: (Series, View, String) -> Unit,
    private val remember: (String) -> Unit
) : LinearLayout(context) {
    data class Entry(
        val series: Series,
        val label: String,
        val resume: Boolean = false,
        val progress: Int? = null,
        val time: String = "",
        /** 海报右下角的角标；留空时按剧集自己的 badge 显示。详情页用它标注「第 N 季」。 */
        val badge: String = ""
    )

    data class Shelf(
        val key: String,
        val title: String,
        val entries: List<Entry>,
        /**
         * 行末那张「查看全部」底板。为空时这一行**不放**这张卡——
         * 一屏（6 张）就装得下的栏目不需要它，多一张底板反而把内容挤窄。
         */
        val more: (() -> Unit)? = null,
        /**
         * 行末再按右键的去处（默认与 [more] 同一个动作）。
         *
         * 与 [more] 分开是因为两者该出现的时机不同：**只要有内容就该能右键进集合页**，
         * 而「查看全部」底板只在条目多到一屏放不下时才有意义。只有 1~6 条记录时
         * [more] 为空，那时右键仍然要能进集合页（这正是「首页历史记录按右键进不去」的原因）。
         */
        val enter: (() -> Unit)? = null
    )

    data class Position(
        val focus: String,
        val shelf: String?,
        val column: Int,
        val row: Int,
        val vertical: Int,
        val horizontal: Map<String, Int>
    )

    private class Card(val key: String, val view: View, var entry: Entry?) {
        var image: ImageView? = null
        var poster: PosterCard? = null
        var pending = false
        var repaint: (Boolean) -> Unit = {}
    }

    private class Row(
        val key: String,
        val block: LinearLayout,
        val horizontal: HorizontalScrollView,
        val cards: List<Card>,
        val slotWidth: Int
    )

    private fun dp(value: Number) = context.tvDp(value)

    /**
     * 剧架滚动容器：左右各留 [TvStyle.FOCUS_BLEED]。
     * 卡片就贴在容器左边缘，焦点放大后第一张卡的左描边会落到容器外面，
     * 被容器自己裁掉——这就是「第一张卡左边框看不见」的原因。
     */
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        // 容器自己不裁剪：卡片要能画进 focusBleed() 留出来的焦点余量里，
        // 否则第一行 / 最左边那张卡放大后的描边会被切掉。
        // 把滚动内容关在可视区里的裁剪由外面的 tvScrollHost 提供（裁剪边界取的是直接父级）。
        clipChildren = false
        clipToPadding = false
        focusBleed()
        // 滚动位置报给宿主：宿主据此收起 / 展开顶部工具条（首页与二级页同一套行为）。
        // 恢复上次位置的那一次不算，免得一进页面页头就闪一下。
        setOnScrollChangeListener { _, _, y, _, _ -> if (!restoring) onScroll?.invoke(y) }
    }
    /** 滚动容器的外壳：把滚动内容关在可视区里的裁剪由它做（见 TvComponents.tvScrollHost）。 */
    private val scrollHost = context.tvScrollHost(scroll)
    private val contents = LinearLayout(context).apply {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val rows = mutableListOf<Row>()
    private val cards = linkedMapOf<String, Card>()
    private var shelves = emptyList<Shelf>()
    private var lastFocus = ""
    private var lastRow: String? = null
    private var restoring = false
    private var revision = 0
    /**
     * 紧凑模式（详情页「相关剧集」）：整块高度按内容走，因此 [fitArtwork] 不能参与——
     * 它是按「控件自身高度」去反算海报高度的，在这里会「收窄海报 → 控件变矮 → 再收窄」地越缩越小。
     */
    private var compact = false

    /** 剧架滚动时把纵向位置报给宿主：宿主用它决定顶部工具条收起还是展开。 */
    var onScroll: ((Int) -> Unit)? = null

    /**
     * 第一栏再按上键时，把焦点交给页头里的控件（由宿主决定是谁，并负责先把页头展开）。
     *
     * 返回 true 表示这一下已经被接管。宿主没接（返回 false）时退回框架默认的
     * `nextFocusUpId` 行为。
     *
     * 为什么不由 HomeScreen 自己 `requestFocus`：页头收着的时候那一行高度是 0、
     * alpha 也是 0，直接落焦点会停在一个看不见的控件上——用户读到的就是「按上没反应」。
     * 展开页头这件事只有宿主（[MainActivity]）能做，因此这里只上报意图。
     */
    var onHeadUp: (() -> Boolean)? = null

    /**
     * 渲染收尾（位置恢复、内容高度变化引起的夹取都做完了）时，把**最终**的纵向位置报给宿主。
     *
     * 与 [onScroll] 分开：[onScroll] 在 `restoring` 期间是静默的（那期间的滚动不是用户滚的），
     * 于是「这次重建把列表摆在哪儿」宿主永远收不到通知。首页因此会停在
     * 「列表其实还在顶部、页头却已经收着」的状态——表现出来就是顶部导航栏不显示、
     * 只能靠把焦点挪到第一栏（历史记录）把列表带回顶部才恢复。
     */
    var onSettled: ((Int) -> Unit)? = null

    /**
     * 详情页「相关剧集」用的紧凑模式：整块只画一栏、高度按内容走、不参与整页滚动。
     *
     * 详情页把这一块放在海报右下方（原来的选集位置），因此它不能像首页那样
     * 「滚动容器 + 弹簧撑满」，只能是一块自带横向滚动的内容。
     */
    fun renderCompact(shelves: List<Shelf>, focus: String, requestFocus: Boolean) {
        compact = true
        this.shelves = shelves
        revision++
        val ticket = revision
        restoring = false
        contents.removeAllViews()
        rows.clear()
        cards.clear()
        lastRow = null
        shelves.filter { it.entries.isNotEmpty() }.forEach { shelf ->
            val block = LinearLayout(context).apply {
                orientation = VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            block.addView(SectionHeading(context, shelf.title), LinearLayout.LayoutParams(-1, -2))
            val horizontal = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                setPadding(dp(2), 0, dp(2), 0)
            }
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
                clipChildren = false
                clipToPadding = false
            }
            horizontal.addView(line)
            block.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
            val slotWidth = slotWidth()
            val rowCards = mutableListOf<Card>()
            shelf.entries.forEach { addCard(shelf, line, rowCards, it, null, ticket, slotWidth) }
            contents.addView(block, LinearLayout.LayoutParams(-1, -2))
            rows += Row(shelf.key, block, horizontal, rowCards, slotWidth)
        }
        rows.forEachIndexed { i, row ->
            row.cards.forEachIndexed { j, card ->
                card.view.nextFocusLeftId = row.cards[(j - 1).coerceAtLeast(0)].view.id
                card.view.nextFocusRightId = row.cards[(j + 1).coerceAtMost(row.cards.lastIndex)].view.id
                card.view.nextFocusUpId =
                    if (i == 0) top.id else rows[i - 1].cards[j.coerceAtMost(rows[i - 1].cards.lastIndex)].view.id
                card.view.nextFocusDownId =
                    if (i == rows.lastIndex) card.view.id
                    else rows[i + 1].cards[j.coerceAtMost(rows[i + 1].cards.lastIndex)].view.id
            }
        }
        val target = target(focus, null)
        if (requestFocus) target.requestFocus()
        post layout@{
            if (revision != ticket) return@layout
            loadNearbySelection()
        }
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        scroll.addView(contents)
        // 滚动容器挂在自己的外壳上：外壳裁剪，容器留余量。
        addView(scrollHost, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun capturePosition(): Position {
        val key = findFocus()?.tag?.toString()?.takeIf { it in cards } ?: lastFocus
        val row = rows.indexOfFirst { item -> item.cards.any { it.key == key } }
        val column = rows.getOrNull(row)?.cards?.indexOfFirst { it.key == key } ?: 0
        return Position(
            key,
            rows.getOrNull(row)?.key,
            column.coerceAtLeast(0),
            row.coerceAtLeast(0),
            scroll.scrollY,
            rows.associate { it.key to it.horizontal.scrollX }
        )
    }

    /**
     * 按 key 就地刷新**已有卡片的状态行**，不重建任何视图、不动焦点。
     *
     * 给「长按菜单里改了一下这部剧的本机状态（收藏 / 稍后观看）」这类**局部**变化用：
     * 走 [updateShelves] 会整块重建剧架（`contents.removeAllViews()` + 重新量海报高度），
     * 观感就是「画面闪缩一下」。这里只改那几张卡的文字，代价与剧架长度无关。
     *
     * 返回**没能就地更新**的 key（新出现的剧不在剧架里，需要调用方决定要不要重建）；
     * 全部命中时返回空。
     */
    fun refreshEntries(next: List<Shelf>): List<String> {
        val wanted = next.flatMap { shelf -> shelf.entries.map { shelf.key to it } }
        val missing = mutableListOf<String>()
        for ((shelfKey, entry) in wanted) {
            if (!updateEntry(shelfKey, entry)) missing += "$shelfKey:${entry.series.id}"
        }
        return missing
    }

    /** 当前剧架里所有卡片的 key（`<栏>:<剧集 id>`）；调用方据此判断结构有没有变。 */
    fun cardKeys(): Set<String> = cards.keys.toSet()

    /** 当前纵向滚动位置。宿主用它把页头的收 / 展状态与真实位置摆一致（见 MainActivity 的页头判据）。 */
    fun scrollY(): Int = scroll.scrollY

    /**
     * 焦点当前落在第几栏（从 0 起）；焦点不在剧架上时返回 -1。
     *
     * 宿主的页头判据要用它：**焦点走到第二栏及以后，页头就该让开**。
     * 只看滚动位置是不够的——首页的栏高是按「一屏放得下一栏」算的，第二栏往往不用滚动
     * 就已经露出下半截，那时位置还是 0，页头却应该收起（与「滚下去才收」是同一件事的两种表达）。
     */
    fun focusedRowIndex(): Int = rows.indexOfFirst { row -> row.cards.any { it.view.hasFocus() } }

    /** 只重建这一块有界区域，并尽量保留原卡片或它的邻居。 */
    fun updateShelves(next: List<Shelf>, requestFocus: Boolean = hasFocus()) {
        val position = capturePosition()
        render(next, position.focus, requestFocus, position)
    }

    /** 不重建视图、不移动焦点的局部更新；结构性变化走 updateShelves。 */
    fun updateEntry(shelfKey: String, entry: Entry): Boolean {
        val card = cards["$shelfKey:${entry.series.id}"] ?: return false
        card.entry = entry
        card.poster?.let { poster ->
            poster.title.text = entry.series.title
            poster.setStatus(entry.label)
            poster.setProgress(entry.progress)
            poster.setCover(entry.series.cover)
        }
        card.view.contentDescription = listOf(
            shelves.firstOrNull { it.key == shelfKey }?.title.orEmpty(),
            entry.series.title,
            entry.label
        ).filter { it.isNotBlank() }.joinToString("，")
        shelves = shelves.map { shelf ->
            if (shelf.key == shelfKey)
                shelf.copy(entries = shelf.entries.map { if (it.series.id == entry.series.id) entry else it })
            else shelf
        }
        loadNearbySelection()
        return true
    }

    /** 页面或生命周期变化取消封面请求后，重新加载可见卡片。 */
    fun reloadArtwork() {
        loadNearbySelection()
    }

    fun render(
        shelves: List<Shelf>,
        focus: String,
        requestFocus: Boolean,
        position: Position? = null
    ) {
        val previous = position ?: if (rows.isNotEmpty()) capturePosition() else null
        compact = false
        this.shelves = shelves
        revision++
        val ticket = revision
        restoring = true
        contents.removeAllViews()
        rows.clear()
        cards.clear()
        lastRow = null

        shelves.filter { it.entries.isNotEmpty() || it.more != null }.forEach { shelf ->
            val block = LinearLayout(context).apply {
                orientation = VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            block.addView(
                // 分区标题就是一行文字：不再标注「更多 ›」。行末那张「查看全部」底板
                // （以及行内按右键直达集合页）已经把这个入口表达清楚，标题再写一次是重复的。
                SectionHeading(context, shelf.title),
                LinearLayout.LayoutParams(-1, -2)
            )
            val horizontal = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                setPadding(dp(2), 0, dp(2), 0)
            }
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
                clipChildren = false
                clipToPadding = false
            }
            horizontal.addView(line)
            block.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
            val slotWidth = slotWidth()
            val rowCards = mutableListOf<Card>()
            shelf.entries.forEach { addCard(shelf, line, rowCards, it, null, ticket, slotWidth) }
            shelf.more?.let { action -> addCard(shelf, line, rowCards, null, action, ticket, slotWidth) }
            contents.addView(
                block,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            )
            rows += Row(shelf.key, block, horizontal, rowCards, slotWidth)
        }

        if (cards.isEmpty())
            contents.addView(
                context.tvText("还没有本机记录，热门剧加载后可选择观看。", 16f, TvStyle.textDim)
                    .apply { setPadding(0, dp(20), 0, dp(20)) },
                LinearLayout.LayoutParams(-1, -2)
            )
        // 首页没有底部按钮行：刷新搬到了顶部导航栏的圆形入口（与搜索 / 排行榜 / 设置同一套），
        // 首页没有底部按钮行：刷新在顶部圆形入口里，四个「热播…」栏目各自的「查看全部」
        // 已经能把对应榜单接下去看，底部再放一个全局入口是重复的。
        rows.forEachIndexed { i, row ->
            row.cards.forEachIndexed { j, card ->
                card.view.nextFocusLeftId = row.cards[(j - 1).coerceAtLeast(0)].view.id
                card.view.nextFocusRightId = row.cards[(j + 1).coerceAtMost(row.cards.lastIndex)].view.id
                card.view.nextFocusUpId =
                    if (i == 0) top.id
                    else rows[i - 1].cards[j.coerceAtMost(rows[i - 1].cards.lastIndex)].view.id
                // 最后一行按下键停在原地：下面已经没有东西了，焦点不会跑到框外。
                card.view.nextFocusDownId =
                    if (i == rows.lastIndex) card.view.id
                    else rows[i + 1].cards[j.coerceAtMost(rows[i + 1].cards.lastIndex)].view.id
            }
        }
        top.nextFocusDownId = firstId()

        val preferred = focus.ifBlank { previous?.focus.orEmpty() }
        val target = target(preferred, previous)
        if (requestFocus) target.requestFocus()
        post layout@{
            if (revision != ticket) return@layout
            fitArtwork()
            post restore@{
                if (revision != ticket) return@restore
                if (previous != null) restoreOffsets(previous)
                restoring = false
                if (requestFocus && previous == null)
                    rows.firstOrNull { row -> row.cards.any { it.view === target } }?.let { revealSelection(it) }
                loadNearbySelection()
                // 位置恢复、revealSelection、内容夹取都做完了，把最终位置报给宿主一次。
                onSettled?.invoke(scroll.scrollY)
                // 布局刚跑完这一帧时 ScrollView 还可能再夹一次位置（海报高度到这一帧才最终确定），
                // 因此下一帧把最终位置**再报一次**：宿主据此把页头状态与真实位置摆一致。
                // 少了这一下，进页面 / 返回首页时页头可能与列表位置对不上（位置在顶部、页头却收着）。
                scroll.post { if (revision == ticket) onSettled?.invoke(scroll.scrollY) }
            }
        }
    }

    fun restorePosition(position: Position, requestFocus: Boolean = true) {
        restoring = true
        if (requestFocus) target(position.focus, position).requestFocus()
        post {
            restoreOffsets(position)
            restoring = false
            loadNearbySelection()
            onSettled?.invoke(scroll.scrollY)
            scroll.post { onSettled?.invoke(scroll.scrollY) }
        }
    }

    /** 第一张卡片的 id；一栏都没有时退到顶部入口，焦点始终有落点。 */
    fun firstId() = rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id

    /** 第一张卡片本身：详情页展开「相关剧集」之后要把焦点落上去。 */
    fun firstCard() = rows.firstOrNull()?.cards?.firstOrNull()?.view

    /** 行内可用宽度 = 屏宽 − 页面左右边距 − 剧架自身左右内边距。 */
    private fun contentWidth() =
        (resources.displayMetrics.widthPixels - 2 * inset - 2 * dp(2)).coerceAtLeast(dp(320))

    /**
     * 与爆米花一致：整行铺满，一屏 6 张竖版海报（约 175×233dp，间距 14dp），
     * 1280dp 画布下与爆米花宫格基本重合。所有分区同一尺寸，行与行之间节奏一致。
     */
    private fun slotWidth(): Int {
        // 卡片自己四周有 SOFT_BAND 的柔光带，行内间距因此收到 10dp，
        // 视觉上的间隔与改版前的 14dp 一致，一行仍然 6 张。
        val gap = dp(10)
        val columns = 6
        return ((contentWidth() - gap * (columns - 1)) / columns).coerceAtLeast(dp(90))
    }

    /**
     * 卡片内封面高度：按 134:196 换算，要先扣掉卡片自身的 5dp 内边距，
     * 否则封面会露出底色。
     */
    private fun slotHeight(width: Int): Int {
        // 扣掉三样东西才轮到封面：卡片自己的柔光带（两侧 SOFT_BAND）+ 海报井的 4dp 内衬。
        val inner = (width - dp(2 * TvStyle.SOFT_BAND + 8)).coerceAtLeast(dp(60))
        return (inner / TvStyle.POSTER_ASPECT).toInt()
    }

    private fun badgeFor(entry: Entry): String {
        val badge = entry.badge.trim().ifBlank { entry.series.badge.trim() }
        if (badge.isBlank()) return ""
        return if (badge.length <= 9) badge else badge.take(8) + "…"
    }

    private fun addCard(
        shelf: Shelf,
        line: LinearLayout,
        rowCards: MutableList<Card>,
        entry: Entry?,
        moreAction: (() -> Unit)?,
        ticket: Int,
        slotWidth: Int
    ) {
        val key = if (entry != null) "${shelf.key}:${entry.series.id}" else "${shelf.key}:more"
        // 行末「查看全部」卡与海报同高，否则会把整行撑高、把后续分区挤出屏幕。
        val height = slotHeight(slotWidth)
        val card =
            if (entry == null) moreCard(key, height)
            else posterCard(key, height, entry)
        val view = card.view
        view.tag = key
        view.setOnFocusChangeListener { _, focused ->
            card.poster?.setFocused(focused)
            card.repaint(focused)
            if (focused) {
                lastFocus = key
                remember(key)
                if (!restoring) {
                    loadNearbySelection()
                    if (lastRow != shelf.key)
                        scroll.post { if (revision == ticket && view.hasFocus()) reveal(blockOf(shelf.key)) }
                }
                lastRow = shelf.key
            }
        }
        view.setOnClickListener {
            lastFocus = key
            remember(key)
            card.entry?.let { open(it.series, it.resume) } ?: moreAction?.invoke()
        }
        // 第一栏的卡片按上键：交给宿主去展开页头再落焦点（见 [onHeadUp]）。
        // 首页的卡片本来靠 `nextFocusUpId` 指到页头控件，但**页头收起时那一行高度是 0**，
        // 焦点请求会成功却停在看不见的地方，页头也不会展开——这正是「从收藏页返回首页后
        // 按上键永远出不来页头」的原因。非第一栏按上键是行内移动，不受影响。
        view.setOnKeyListener { _, code, event ->
            if (code != KeyEvent.KEYCODE_DPAD_UP || event.action != KeyEvent.ACTION_DOWN) false
            else {
                val first = rows.firstOrNull() ?: return@setOnKeyListener false
                if (first.key != shelf.key) false
                else {
                    lastFocus = key
                    remember(key)
                    onHeadUp?.invoke() ?: false
                }
            }
        }
        if (entry != null) {
            view.setOnLongClickListener {
                lastFocus = key
                remember(key)
                card.entry?.let { actions(it.series, view, shelf.key) }
                true
            }
            view.setOnKeyListener { target, code, event ->
                when {
                    code == KeyEvent.KEYCODE_MENU -> {
                        if (event.action == KeyEvent.ACTION_UP) {
                            lastFocus = key
                            remember(key)
                            card.entry?.let { actions(it.series, target, shelf.key) }
                        }
                        true
                    }
                    // 行末再按右键直接进集合页，不必先把焦点移到「查看全部」底板再确认。
                    // 落点用 [Shelf.enter]（没有时退回 [Shelf.more]）：只要这一栏有内容，
                    // 右键就该能进去，不取决于有没有那张「查看全部」底板。
                    code == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN &&
                        event.repeatCount == 0 && rowCards.lastOrNull { it.entry != null } === card ->
                        (shelves.firstOrNull { it.key == shelf.key }?.enter
                            ?: shelves.firstOrNull { it.key == shelf.key }?.more)?.let { go ->
                            lastFocus = key
                            remember(key)
                            go()
                            true
                        } ?: false
                    else -> false
                }
            }
        }
        line.addView(view, LinearLayout.LayoutParams(slotWidth, -2).apply { rightMargin = dp(10) })
        rowCards += card
        cards[key] = card
    }

    private fun blockOf(shelfKey: String): View =
        rows.firstOrNull { it.key == shelfKey }?.block ?: contents

    /** 行末「查看全部」卡：与海报卡同一套材质（凸起底板 + 焦点抬升），只是里面没有封面。 */
    private fun moreCard(key: String, height: Int): Card {
        val band = context.softBand()
        val plate = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            id = View.generateViewId()
            clipChildren = false
            clipToPadding = false
            setPadding(band, band, band, band)
        }
        val stack = context.tvColumn().apply { gravity = Gravity.CENTER }
        stack.addView(context.tvText("›", 44f, TvStyle.textDim, bold = true))
        stack.addView(
            context.tvText("查看全部", 14f, TvStyle.textDim),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) }
        )
        plate.addView(stack, FrameLayout.LayoutParams(-1, height, Gravity.CENTER))
        paintMore(plate, false)
        return Card(key, plate, null).apply {
            repaint = { focused -> paintMore(plate, focused) }
        }
    }

    /**
     * 「查看全部」底板：常态凸起面，焦点换成抬升面 + 更深的柔光 + 光晕（与海报卡同一套）。
     * 2026-10-07 主题改版之后焦点不再画强调色细环，靠材质自己的凹凸表达。
     */
    private fun paintMore(plate: FrameLayout, focused: Boolean) {
        plate.background = TvStyle.soft(
            context,
            if (focused) TvStyle.surfaceHigh else TvStyle.surface,
            TvStyle.RADIUS_CARD,
            TvStyle.SOFT_BAND,
            SoftStyle.RAISED,
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = if (focused) 1.35f else 1f
        )
        TvStyle.focus(plate, focused)
    }

    private fun posterCard(key: String, height: Int, entry: Entry): Card {
        val poster = PosterCard(context, height, loadCover)
        // 紧凑模式（详情页「相关剧集」）不标右上角季号：那一条剧架的右下角已经在写季号了。
        poster.bind(entry.series, entry.label, badgeFor(entry), entry.progress, false, seasonBadge = !compact)
        return Card(key, poster, entry).apply {
            this.poster = poster
            image = poster.image
        }
    }

    private fun target(key: String, position: Position?): View {
        cards[key]?.let { return it.view }
        if (position != null) {
            val row = rows.firstOrNull { it.key == position.shelf }
                ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let { return it.view }
        }
        return cards.values.firstOrNull()?.view ?: top
    }

    /**
     * 恢复纵向位置与每一栏的横向位置。
     *
     * **必须重试**：列表刚重建时 `contents` 还没量过高度，而 `ScrollView.scrollTo()` 的上限
     * 是按子控件高度算的（`computeVerticalScrollRange`）——那时上限是 0，位置会被**夹成 0**。
     * 后果不是「只是位置没恢复」这么简单：位置回到顶部、焦点却还停在下面那一行，
     * 于是页头显示着（位置在顶部）、而按上键回到第一行时列表被 reveal 滚动、页头反而收起，
     * 用户看到的就是**状态与位置反过来**。这里等下一帧（布局完成后）再补，最多三次。
     */
    private fun restoreOffsets(position: Position, attempt: Int = 0) {
        rows.forEach { row -> row.horizontal.scrollTo(position.horizontal[row.key] ?: 0, 0) }
        scroll.scrollTo(0, position.vertical)
        // 「要恢复到非零位置、却仍是 0，而且内容还没量出高度」= 这一下被夹掉了，下一帧再试。
        val clamped = position.vertical > 0 && scroll.scrollY == 0 && contents.height <= 0
        if (clamped && attempt < 3) {
            val ticket = revision
            scroll.post { if (revision == ticket) restoreOffsets(position, attempt + 1) }
        }
        // 被移除的分区可能把最近的幸存卡片留在旧视口之外；位置没恢复成功时这一步会把
        // 焦点所在的那一行滚进视口（页头因此收起，与位置一致）。
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let(::revealSelection)
    }

    private fun revealSelection(row: Row) {
        row.cards.firstOrNull { it.view.hasFocus() }?.view?.let { card ->
            val horizontal = row.horizontal
            val left = horizontal.scrollX
            val right = left + horizontal.width - horizontal.paddingRight
            val target = when {
                card.left < left -> card.left
                card.right > right -> card.right - horizontal.width + horizontal.paddingRight
                else -> left
            }
            if (target != left) horizontal.scrollTo(target.coerceAtLeast(0), 0)
        }
        reveal(row.block, false)
    }

    private fun reveal(block: View, smooth: Boolean = true) {
        val target = if (smooth) block.top else when {
            block.top < scroll.scrollY -> block.top
            block.bottom > scroll.scrollY + scroll.height ->
                if (block.height >= scroll.height) block.top else block.bottom - scroll.height
            else -> scroll.scrollY
        }.coerceAtLeast(0)
        if (target != scroll.scrollY) {
            if (smooth) scroll.smoothScrollTo(0, target) else scroll.scrollTo(0, target)
        }
    }

    private fun loadNearbySelection() {
        if (restoring) return
        val row = rows.indexOfFirst { it.cards.any { card -> card.key == lastFocus } }.coerceAtLeast(0)
        val column = rows.getOrNull(row)?.cards?.indexOfFirst { it.key == lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items ->
            for (index in (column - 1).coerceAtLeast(0)..(column + 6).coerceAtMost(items.lastIndex))
                requestCover(items[index])
        }
        rows.getOrNull(row + 1)?.cards?.take(6)?.forEach(::requestCover)
    }

    private fun requestCover(card: Card) {
        val image = card.image ?: return
        if (card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket = revision
        fun start() {
            card.pending = false
            if (ticket != revision || !image.isAttachedToWindow || image.width <= 0 || image.height <= 0) return
            loadCover(image, card.entry!!.series.cover)
        }
        if (image.isLaidOut && !image.isLayoutRequested) start()
        else {
            card.pending = true
            image.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    image.removeOnLayoutChangeListener(this)
                    start()
                }
            })
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { reloadArtwork() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 只有宽度变化才重新排版封面：高度变化来自顶部工具条收展，跟着重算会让整屏海报抖一下。
        if (w != oldw) post {
            fitArtwork()
            loadNearbySelection()
        }
    }

    /** 屏高不足时按可用高度收窄卡片，保证「整行 + 分区标题」始终完整可见。 */
    private fun fitArtwork(retry: Boolean = true) {
        if (compact || height <= 0) return
        // 标题行还没量到高度时，下面算出来的「可用高度」会偏大（把标题那一段当成了 0），
        // 海报于是先按大的排、下一帧再缩回去——观感就是「画面闪缩一下」。
        // 这一帧跳过、等下一帧量准了再排；只重试一次，量不出来（异常布局）就按当前值排。
        if (retry && rows.any { it.block.getChildAt(0).measuredHeight <= 0 }) {
            post { fitArtwork(false) }
            return
        }
        rows.forEach { row ->
            val heading = row.block.getChildAt(0).measuredHeight
            val available = (height - heading - dp(58 + 2 * TvStyle.SOFT_BAND + 8) - dp(24)).coerceAtLeast(dp(110))
            val target = minOf(slotHeight(row.slotWidth), available)
            row.cards.forEach { card ->
                card.poster?.resizePoster(target)
            }
        }
    }
}
