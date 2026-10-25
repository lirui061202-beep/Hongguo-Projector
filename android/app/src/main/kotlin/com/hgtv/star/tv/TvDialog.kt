// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 应用内唯一的模态框：标题 + 内容 + 一排 [TvButton]。
 *
 * 之前所有弹窗都交给系统 AlertDialog，按钮是 Material 的文字按钮——高度、圆角、焦点反馈
 * 和页面上的胶囊按钮都不是一套，看起来像另一个应用。这里把弹窗内容做成普通 View，
 * 按钮直接复用全站按钮组件，因此模态框内外完全一致。
 *
 * 用法：
 * ```
 * TvDialog(this, "删除这条记录")
 *     .message("删除《…》的本机观看进度？收藏会保留。")
 *     .button("取消") {}
 *     .button("确认") { library.removeHistory(id) }
 *     .show(anchor)
 * ```
 * 列表型弹窗用 [choices]：每项都是整行左对齐的常规按钮，超过半屏由 [ScrollView] 接管。
 * 三个区块（内容 / 列表 / 按钮行）只有真正有东西时才占位，空着不留间距。
 */
internal class TvDialog(private val activity: Activity, title: String) {

    private companion object {
        /**
         * 弹窗中部可滚动区域（内容区 + 列表区）合计最多占屏幕高度的比例。
         *
         * 更新日志这类长文本原先**不封顶也不滚动**，一长就把按钮行顶出画面——
         * 用户看得到日志、看不到「更新 / 暂不更新」。现在这一块封顶，超出部分各自滚动，
         * 按钮行始终留在画面里。
         *
         * 两块**共用一个预算**（而不是各占一个比例）：同时出现内容与列表的弹窗
         * （备份恢复：说明文字 + 选项）加起来才不会顶出一屏。取 .5 加上标题、
         * 按钮行与弹窗四周留白，整框仍在一屏之内，内容区也还读得下十来行。
         */
        const val SCROLLABLE_MAX_FRACTION = .5f
    }

    private val dp = { value: Number -> activity.tvDp(value) }
    /** 弹窗面板的柔光带：面板比窗口边界内缩这么多，投影 / 高光画在这条带里。 */
    private val band = activity.softBandLarge()
    // 容器比内容早 FOCUS_BLEED 开始：列表项 / 按钮行放大后的焦点底色才有地方画；
    // 再加上面板的柔光带，弹窗内容仍然落在同一条 24dp 内容线上。
    // 标题、内容、按钮行各自补回 FOCUS_BLEED，因此弹窗里所有元素对齐。
    private val body = activity.tvColumn().apply {
        clipChildren = false
        clipToPadding = false
        val inset = dp(TvStyle.GAP_GROUP)
        val bleed = dp(TvStyle.FOCUS_BLEED)
        setPadding(inset - bleed + band, inset + band, inset - bleed + band, inset + band)
    }

    /** 调用方可以直接往里塞二维码、复选框这类自定义内容。 */
    val content: LinearLayout = activity.tvColumn().apply {
        clipChildren = false
        clipToPadding = false
    }

    /**
     * 内容区的滚动容器。
     *
     * 裁剪由 [contentHost] 提供（与列表同一套规则）。内边距**不在这里写死**：
     * 上下那点 [TvStyle.FOCUS_BLEED] 只在内容里真有可聚焦控件时（备份恢复弹窗那个开关）
     * 才补上——纯文字弹窗留了只是白占高度；左右不留，内容自己已经有左右余量，
     * 这里再加一份会让正文比标题多缩进一条，两处对不齐。
     */
    private val contentScroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
    }
    /**
     * 内容区的裁剪外壳：在**测量阶段**就把高度压到上限之内。
     *
     * 为什么不用「布局完成后再改 LayoutParams」那种做法：那要依赖 `OnLayoutChangeListener`，
     * 而它的时机跟「弹窗是第一次弹出、还是同一个位置再弹一次」有关——
     * 第二次弹出时内容**一开始就是长的**（状态文字上一次检查后就已经是整段更新日志），
     * 于是 `show()` 里那次 `post` 量到的还是 0 高度、判定成「不溢出」，
     * 而随后的布局回调也没能把按钮救回来，表现出来就是「第一次能滚、再打开按钮又被顶没」。
     * 放在测量阶段就没有这个时序问题：无论内容何时变长，量的时候就已经封顶了。
     */
    private inner class ContentHost : FrameLayout(activity) {
        init {
            clipChildren = true
            clipToPadding = true
        }

        /** 当前是不是「内容超出上限、正在滚动」的状态。 */
        private var overflow = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // 先按自然高度量一次。`ScrollView` 给子控件传的是 UNSPECIFIED，
            // 因此这一次量到的就是内容的真实高度，不会被上限反过来压小。
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val natural = measuredHeight
            val cap = contentCap()
            val next = natural > cap
            if (next) super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.EXACTLY))
            if (next != overflow) {
                overflow = next
                // 测量里不改焦点链（那是布局的事），推到下一帧做。
                post { if (isShowing) wireContentFocus(next) }
            }
        }
    }

    private val contentHost = ContentHost().apply {
        addView(contentScroll, FrameLayout.LayoutParams(-1, -1))
    }

    private val items = activity.tvColumn().apply {
        clipChildren = false
        clipToPadding = false
    }
    private val scroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        visibility = View.GONE
        focusBleed()
    }
    /** 列表滚动的外壳：裁剪由它做，列表项放大后的底色才能画进容器自己的焦点余量里。 */
    private val listHost = activity.tvScrollHost(scroll)
    private val actions = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val buttons = mutableListOf<TvButton>()
    private var listHeight = 0
    private var dialog: AlertDialog? = null
    private var dismissed: (() -> Unit)? = null
    private var anchor: View? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    init {
        body.addView(
            activity.tvText(title, 22f, TvStyle.textStrong, bold = true)
                .apply { setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0) }
        )
        content.setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0)
        contentScroll.addView(content)
        body.addView(contentHost, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(items)
        body.addView(listHost, LinearLayout.LayoutParams(-1, -2))
        actions.setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0)
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))
        // 内容区可能在建好之后才被填、也可能被清空重填（手机输入 / 备份恢复那两处会 removeAllViews）。
        // 因此这里**不用 visibility 开关**：把宿主设成 GONE 之后它的子控件不再被测量，
        // 这条布局回调也就不会再触发，清空后重填的内容会永远不显示。
        // 改成始终可见、只按内容数量调间距——内容为空时 [content] 的自然高度是 0，
        // 宿主跟着是 0 高，同样不留空槽。
        content.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val column = view as? LinearLayout ?: return@addOnLayoutChangeListener
            val params = contentHost.layoutParams as? LinearLayout.LayoutParams ?: return@addOnLayoutChangeListener
            val wanted = if (column.childCount > 0) dp(TvStyle.GAP_BLOCK) else 0
            if (params.topMargin != wanted) {
                params.topMargin = wanted
                contentHost.layoutParams = params
            }
            // 上下焦点余量**只在内容里真有可聚焦控件时**才留（见 [contentScroll]）：
            // 纯文字弹窗（更新日志、开源许可、二次确认）留了也是白占高度。
            // 封顶不在这里做——那件事已经交给 [ContentHost] 的测量（见那里的注释）。
            val bleed = if (column.anyFocusable()) dp(TvStyle.FOCUS_BLEED) else 0
            if (contentScroll.paddingTop != bleed || contentScroll.paddingBottom != bleed) {
                contentScroll.setPadding(0, bleed, 0, bleed)
            }
        }
    }

    /** 一段说明文字。多段就多调一次，行距与行高由同一套令牌决定。 */
    fun message(text: String, size: Float = 16f, color: Int = TvStyle.textDim): TvDialog {
        content.addView(
            activity.tvText(text, size, color).apply { setLineSpacing(dp(TvStyle.GAP_TIGHT).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { if (content.childCount > 0) topMargin = dp(TvStyle.GAP_INLINE) }
        )
        return this
    }

    /** 列表型选项：整行左对齐的常规按钮，[selected] 的那一项用强调色标出。 */
    fun choices(labels: List<String>, selected: Int = -1, onChoice: (Int) -> Unit): TvDialog {
        labels.forEachIndexed { index, label ->
            val item = TvButton(activity, label, ButtonSize.NORMAL, ButtonTone.SECONDARY, alignStart = true, action = {
                dismiss()
                onChoice(index)
            })
            item.setSelectedState(index == selected)
            item.contentDescription = label + if (index == selected) "，当前选择" else ""
            items.addView(
                item,
                LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(TvStyle.GAP_INLINE) }
            )
        }
        if (labels.isNotEmpty()) {
            scroll.visibility = View.VISIBLE
            (listHost.layoutParams as LinearLayout.LayoutParams).topMargin = dp(TvStyle.GAP_BLOCK)
            // 列表滚动的可视高度要把滚动容器自身的焦点余量（上下各 FOCUS_BLEED）一起算进去，
            // 否则第一项 / 最后一项选中时的底色会被列表裁掉一条边。
            // 每一项都是常规档按钮，高度含柔光带；间距按 GAP_INLINE + 两条柔光带算，
            // 否则列表高度会低估，第一项 / 最后一项的柔光与焦点环会被列表裁掉。
            val itemHeight = dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)
            val itemGap = dp(TvStyle.GAP_INLINE + 2 * TvStyle.SOFT_BAND)
            listHeight = labels.size * itemHeight + (labels.size - 1) * itemGap +
                2 * dp(TvStyle.FOCUS_BLEED_VERTICAL)
        }
        return this
    }

    /**
     * 底部动作按钮。默认点完就关（[keepOpen] 用于「检查更新」这种点完还要留在框里刷新状态的）。
     * 返回按钮本身，调用方可以后续改文案或禁用。
     */
    fun button(label: String, tone: ButtonTone = ButtonTone.SECONDARY, keepOpen: Boolean = false, run: () -> Unit = {}): TvButton {
        val view = TvButton(activity, label, ButtonSize.NORMAL, tone, action = {
            if (!keepOpen) dismiss()
            run()
        })
        actions.addView(
            view,
            LinearLayout.LayoutParams(-2, -2).apply { if (actions.childCount > 0) leftMargin = dp(TvStyle.GAP_INLINE) }
        )
        if (actions.childCount == 1) (actions.layoutParams as LinearLayout.LayoutParams).topMargin = dp(TvStyle.GAP_GROUP)
        buttons += view
        return view
    }

    fun show(anchor: View? = null, onDismiss: () -> Unit = {}) {
        if (isShowing) return
        this.anchor = anchor
        dismissed = onDismiss
        val metrics = activity.resources.displayMetrics
        val next = AlertDialog.Builder(activity).setView(body).create()
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                val done = dismissed
                dismissed = null
                done?.invoke()
            }
            // 关闭后**只把焦点还给 anchor**，且仅在「当前没人持有焦点」时还：
            // 全站的焦点原则是不主动抢焦点（见 MainActivity 里的说明），
            // 关掉弹窗时如果焦点已经被别处接走（例如内容重建后落到第一张卡），就不要夺回来。
            val current = activity.window?.decorView?.findFocus()
            if (anchor?.isAttachedToWindow == true && current == null) anchor.requestFocus()
        }
        dialog = next
        next.show()
        next.window?.apply {
            // 弹窗就是一块大号凸起面板：圆角 24dp、柔光带 9dp、投影更深一档。
            setBackgroundDrawable(
                TvStyle.soft(
                    activity, TvStyle.surfaceHigh, TvStyle.RADIUS_DIALOG, TvStyle.SOFT_BAND_LARGE,
                    SoftStyle.RAISED, depth = 1.3f
                )
            )
            // 背景压暗是柔和的：新拟态靠同色明度差表达层次，压得太黑面板就看不出是浮层了。
            setDimAmount(.66f)
            setLayout(
                (metrics.widthPixels * .58f).toInt().coerceIn(dp(420), dp(860)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // 列表超过半屏就滚动，避免弹窗被长列表顶出画面。
        // 列表与内容区共用 [SCROLLABLE_MAX_FRACTION] 这一份预算（见 [contentCap]）：
        // 两块都在时各分一半，只有一块时那一块独占，加起来始终在一屏之内。
        if (listHeight > 0) {
            val limit = (metrics.heightPixels * SCROLLABLE_MAX_FRACTION).toInt()
            listHost.layoutParams = listHost.layoutParams.apply { height = listHeight.coerceAtMost(limit) }
        }
        // 焦点落在第一项（列表优先），因此「确认」永远不会是默认落点，误触不会造成破坏。
        body.post {
            if (!isShowing) return@post
            (if (items.childCount > 0) items.getChildAt(0) else buttons.firstOrNull())?.requestFocus()
            // 封顶由 [ContentHost] 的测量负责，这里只补一次焦点接线兜底
            // （内容为空时测量里不会报「溢出」，也就不会有人来接线）。
            wireContentFocus(content.height > contentCap())
        }
    }

    /**
     * 内容区可用的高度上限（见 [ContentHost]）。
     *
     * 内容区与列表区共用 [SCROLLABLE_MAX_FRACTION] 这一份预算：另一块已经占了高度时，
     * 这一块只能用剩下的部分；两块都短就各自按自然高度摆，谁都不封顶。
     */
    private fun contentCap(): Int {
        val metrics = activity.resources.displayMetrics
        val budget = (metrics.heightPixels * SCROLLABLE_MAX_FRACTION).toInt()
        val used = if (listHeight > 0) listHost.layoutParams.height.coerceAtLeast(0) else 0
        return (budget - used).coerceAtLeast((metrics.heightPixels * .18f).toInt())
    }

    /**
     * 内容区溢出时的焦点接线。
     *
     * **只有真的溢出时才让内容区可聚焦**：那时按钮行按上键能进日志区上下滚动、
     * 按下键回按钮行；短内容不参与焦点链，行为与以前完全一致。
     * 内容里有可聚焦控件时（备份恢复弹窗那个开关），焦点交给那个控件自己——
     * 让滚动容器抢焦点会把那个控件挡在焦点链之外（`ScrollView` 会自动把获得焦点的子控件滚进可视区）。
     */
    private fun wireContentFocus(overflow: Boolean) {
        val innerFocusable = content.anyFocusable()
        val scrollFocusable = overflow && !innerFocusable
        contentScroll.isFocusable = scrollFocusable
        contentScroll.isFocusableInTouchMode = scrollFocusable
        if (!overflow) return
        if (contentScroll.id == View.NO_ID) contentScroll.id = View.generateViewId()
        val up = if (scrollFocusable) contentScroll else content.firstFocusable() ?: contentScroll
        buttons.forEach { it.nextFocusUpId = up.id }
        contentScroll.nextFocusDownId = buttons.firstOrNull()?.id ?: contentScroll.id
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}

/** 子树里有没有可聚焦的控件（跳过不可见的）。 */
private fun View.anyFocusable(): Boolean {
    if (visibility != View.VISIBLE) return false
    if (isFocusable) return true
    val group = this as? ViewGroup ?: return false
    return (0 until group.childCount).any { group.getChildAt(it).anyFocusable() }
}

/** 子树里第一个可聚焦的控件（跳过不可见的），没有就返回 null。 */
private fun View.firstFocusable(): View? {
    if (visibility != View.VISIBLE) return null
    if (isFocusable) return this
    val group = this as? ViewGroup ?: return null
    for (i in 0 until group.childCount) group.getChildAt(i).firstFocusable()?.let { return it }
    return null
}
