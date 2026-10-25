// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hgtv.star.core.Series
import com.hgtv.star.core.SeriesSeasons

internal fun Context.tvDp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

/** 柔光带的像素值：控件自己的内边距，也是投影 / 高光能画到的范围。 */
internal fun Context.softBand() = tvDp(TvStyle.SOFT_BAND)

/** 大面板（弹窗、设置分组、播放器底座）的柔光带。 */
internal fun Context.softBandLarge() = tvDp(TvStyle.SOFT_BAND_LARGE)

internal fun Context.tvText(
    value: String,
    size: Float,
    color: Int = TvStyle.text,
    bold: Boolean = false
): TextView = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    includeFontPadding = false
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

/**
 * 行 / 列容器：一律允许子控件画到自己边界之外。
 *
 * 焦点放大会让控件溢出槽位约 [TvStyle.FOCUS_BLEED]（见该常量的推导），
 * 因此中间任何一层行 / 列都不能裁剪——一旦裁剪，
 * 就会把贴在它边缘的那张卡片 / 那个按钮的焦点描边切掉。
 * 唯一负责裁剪的是滚动容器的外壳，见 [tvScrollHost]。
 */
internal fun <T : ViewGroup> T.focusOverflow(): T = apply {
    clipChildren = false
    clipToPadding = false
}

/**
 * 滚动容器：四边各留 [TvStyle.FOCUS_BLEED]，**自己不做裁剪**。
 *
 * 三件事缺一不可：
 *
 * 1. **余量留在容器内部**（内边距），不能靠外面的 padding——外面加 padding 只是把裁剪边界一起挪走。
 *    四边都要留：第一行控件放大后的焦点底色会画到内容顶边之上，最后一行会画到底边之下，
 *    只补左右时就会出现「左边框补回来了、上边框还是被切平」；纵向按 [TvStyle.FOCUS_BLEED_VERTICAL] 留，
 *    因为海报卡是竖的，向上溢出的绝对值比横向大。
 * 2. **容器自己 `clipChildren = false`**。这个开关管的是「子控件会不会被裁到子控件自己的边界上」，
 *    打开它 = 把列表裁在列表自己的边界上。列表本来就有整份内容那么高，裁了等于没裁，
 *    却正好把第一行 / 最左边那张卡放大后画到列表边界之外的那条描边切掉——
 *    这就是「最左边的选中框只剩一半、上边框看不见」的真正原因。
 * 3. `clipToPadding = false`：不额外裁到内边距框，第 1 条的余量才真的可用。
 *
 * 把滚动内容关在可视区里的那一道裁剪由 [tvScrollHost] 提供，两者不能互相替代。
 * 一句话：余量做在容器内部，裁剪做在容器外面。
 *
 * 新拟态之后投影 / 高光不再画到控件边界之外（见 [SoftDrawable]），
 * 因此这条余量现在只管「焦点放大 1.05 倍溢出的 2.5%」这一件事，规则不变。
 */
internal fun ScrollView.focusBleed(): ScrollView = apply {
    clipChildren = false
    clipToPadding = false
    val bleed = context.tvDp(TvStyle.FOCUS_BLEED)
    val bleedVertical = context.tvDp(TvStyle.FOCUS_BLEED_VERTICAL)
    setPadding(
        paddingLeft.coerceAtLeast(bleed),
        paddingTop.coerceAtLeast(bleedVertical),
        paddingRight.coerceAtLeast(bleed),
        paddingBottom.coerceAtLeast(bleedVertical)
    )
}

/**
 * 滚动容器的外壳：**裁剪边界由它提供**。
 *
 * 裁剪边界取的是**直接父级**的 `clipChildren`，不是控件自己的：硬件绘制路径里
 * `View.setDisplayListProperties()` 就是
 * `renderNode.setClipToBounds(mParent instanceof ViewGroup && ((ViewGroup) mParent).getClipChildren())`，
 * 软件绘制路径同样只看 `parentFlags & FLAG_CLIP_CHILDREN`。
 * 所以「滚动内容不许画到可视区之外」这件事只能由滚动容器的父级来做：
 * 父级裁剪 = 滚动容器被裁到自己的边界上 = 上滚的卡片不可能再盖住页头那一排按钮。
 *
 * 外壳里只放滚动容器一个子控件，因此打开裁剪不会碰到任何按钮的焦点放大；
 * 焦点余量仍然由滚动容器自己的内边距负责（见 [focusBleed]）。
 */
internal fun Context.tvScrollHost(scroll: View): FrameLayout = FrameLayout(this).apply {
    clipChildren = true
    clipToPadding = true
    addView(scroll, FrameLayout.LayoutParams(-1, -1))
}

/**
 * 把一个**长期持有**的控件重新挂到新容器上：先把它从旧父级里摘出来，再挂。
 *
 * 视图树里一个控件只能有一个父级，`ViewGroup.addView()` 遇到已经有父级的子控件会直接抛
 * `IllegalStateException: The specified child already has a parent. You must call removeView()
 * on the child's parent first.`。列表整体重建时（`contents.removeAllViews()`）只把**直接**
 * 子控件从树上摘下来，行里面的按钮仍然挂在旧行上，于是「第一次重建没事、第二次必崩」——
 * 触发时机取决于收藏更新检查、长按管理这类异步事件，表现出来就是偶发崩溃。
 *
 * 结论：凡是复用一个长期持有的按钮（例如首页的「刷新热门」），一律走这里重新挂载。
 */
internal fun ViewGroup.rehost(view: View, params: ViewGroup.LayoutParams? = null): View {
    (view.parent as? ViewGroup)?.removeView(view)
    if (params == null) addView(view) else addView(view, params)
    return view
}

internal fun Context.tvColumn() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    focusOverflow()
}

internal fun Context.tvRow() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    focusOverflow()
}

/** 圆角用 outline 裁剪，而不是叠加遮罩位图：不额外分配内存，缩放后依然锐利。 */
internal fun roundCorners(view: View, radiusDp: Int = TvStyle.RADIUS_POSTER) {
    view.clipToOutline = true
    view.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(target: View, outline: Outline) {
            outline.setRoundRect(
                0,
                0,
                target.width,
                target.height,
                radiusDp * target.resources.displayMetrics.density
            )
        }
    }
    view.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ -> target.invalidateOutline() }
}

/** 粗体正文，卡片标题与按钮共用同一种字重节奏。 */
internal fun TextView.applyTitleStyle() {
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
}

/**
 * 按钮尺寸档：高度、横向内边距、字号、最小宽度四件事绑在一起，调用方只选档位，
 * 不再自己拼 padding。全站只有这三档（40 / 48 / 56dp 的**底板**高度）。
 */
internal enum class ButtonSize(val height: Int, val pad: Int, val text: Float) {
    COMPACT(TvStyle.BUTTON_COMPACT, TvStyle.BUTTON_PAD_COMPACT, TvStyle.BUTTON_TEXT_COMPACT),
    NORMAL(TvStyle.BUTTON_NORMAL, TvStyle.BUTTON_PAD_NORMAL, TvStyle.BUTTON_TEXT_NORMAL),
    HERO(TvStyle.BUTTON_HERO, TvStyle.BUTTON_PAD_HERO, TvStyle.BUTTON_TEXT_HERO)
}

/**
 * 按钮语义。整个应用只有这三种外观，任何新按钮都必须从里面挑一种。
 */
internal enum class ButtonTone {
    /** 主操作：常亮的亮面板（一屏最多一个）。 */
    PRIMARY,

    /** 常规操作：凸起面板 + 白字。返回、分页、重试、收藏、刷新。 */
    SECONDARY,

    /** 文字按钮：无底，只有字色与字重。更多 ›、播放器状态项。 */
    GHOST
}

/**
 * 按钮绘制：尺寸、圆角、底板、字色、缩放只有这一份实现。
 *
 * 状态只有五种，全部靠「凹 / 凸 / 亮」三个台阶表达，**没有一圈带颜色的选框**：
 * - 常态 = 凸起面板（[TvStyle.surface]，右下深投影 + 左上高光）+ 正文色；
 * - 选中 = **按进去**（[TvStyle.selectedWell] + [SoftStyle.INSET]，槽的左上更深、右下更亮）+ 正文色；
 * - 焦点 = 亮面板（[TvStyle.lit] + [TvStyle.onLit] 近黑字）+ [TvStyle.litGlow] 光晕；
 * - 主操作 = 常亮的亮面板（不用点也知道这是本屏的主操作）；
 * - 禁用 = 压平的凹槽 + 30% 文字色。
 *
 * 焦点与选中都不再画强调色细环、也不再用强调色写字：上一版那圈环是「整体风格里唯一一块
 * 带颜色的东西」，观感就是「选框的颜色和整体风格不搭」。现在**选中靠凹、焦点靠亮**，
 * 两者都是材质自己的语言，换主题也不必重新调环色（2026-10-07 主题改版）。
 *
 * 控件高度 = 底板高度 + 两侧柔光带（[TvStyle.SOFT_BAND]），内边距同理，
 * 因此调用方给的 `pad` 只是「文字到面板边缘」的距离，投影永远有自己的地方。
 */
internal fun paintButton(
    view: TextView,
    size: ButtonSize,
    tone: ButtonTone,
    focused: Boolean,
    selected: Boolean = false,
    enabled: Boolean = true,
    pad: Int = size.pad
) {
    val context = view.context
    val dp = { value: Number -> context.tvDp(value) }
    val band = context.softBand()
    val pill = TvStyle.RADIUS_PILL
    view.textSize = size.text
    view.setPadding(dp(pad) + band, band, dp(pad) + band, band)
    view.minimumHeight = dp(size.height + 2 * TvStyle.SOFT_BAND)
    // 文字按钮按内容宽收紧，其余按钮留一个最小宽度，短文案（「收藏」）才不会瘦成一条。
    view.minWidth = if (tone == ButtonTone.GHOST) 0 else dp(TvStyle.BUTTON_MIN_WIDTH) + band * 2
    view.setTypeface(view.typeface, Typeface.BOLD)
    view.setTextColor(
        when {
            !enabled -> TvStyle.muted
            focused -> TvStyle.onLit
            tone == ButtonTone.PRIMARY -> TvStyle.onLit
            tone == ButtonTone.GHOST -> TvStyle.textDim
            else -> TvStyle.text
        }
    )
    view.background = when {
        // 焦点：打亮 + 光晕。没有细环——焦点面本身就是整块材质里最亮的一档。
        focused -> TvStyle.soft(
            context, TvStyle.lit, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED,
            glow = TvStyle.litGlow, depth = 1.2f
        )
        !enabled -> TvStyle.soft(context, TvStyle.well, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED, depth = .45f)
        // 选中：按进去。凹凸对比完全由柔光表达，不带任何颜色。
        selected -> TvStyle.well(context, TvStyle.selectedWell, pill, TvStyle.SOFT_BAND, depth = 1.15f)
        // 主操作：常亮的亮面板。它和焦点态的区别就是少了那一圈细环与外发光。
        tone == ButtonTone.PRIMARY -> TvStyle.soft(context, TvStyle.lit, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED, depth = 1.1f)
        tone == ButtonTone.SECONDARY -> TvStyle.soft(context, TvStyle.surface, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED)
        else -> TvStyle.soft(context, Color.TRANSPARENT, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED)
    }
    view.alpha = if (enabled) 1f else TvStyle.DISABLED_ALPHA
}

/**
 * 全站唯一的按钮实现。尺寸走 [ButtonSize]、外观走 [ButtonTone]，
 * 焦点反馈（打亮 + 外发光 + 放大）与间距（外层用 [TvStyle.GAP_INLINE]）都不再由调用方决定。
 *
 * [alignStart] 用于设置页那种整行左对齐的列表项；其余按钮文字居中。
 */
internal open class TvButton(
    context: Context,
    label: String,
    private val size: ButtonSize = ButtonSize.NORMAL,
    private var tone: ButtonTone = ButtonTone.SECONDARY,
    private val alignStart: Boolean = false,
    private val action: () -> Unit = {},
    /**
     * 覆盖 [ButtonSize] 自带的左右内边距。
     *
     * 简介页那一行是四个**等宽**按钮，每个的可用宽度比整行按钮窄得多（还要再扣掉两条柔光带），
     * 用 HERO 档的 36dp 内边距会把「继续第 12 集」这类长文案挤成省略号。
     * 这一行因此用更小的内边距把文字宽度让出来，字号与高度不变。
     */
    private val pad: Int? = null
) : TextView(context) {
    private var selected = false

    /**
     * 额外的焦点回调（首页要靠它记住最后停留的入口）。
     * 底色、字色与缩放由 [TvButton] 自己完成，调用方不要覆盖 OnFocusChangeListener。
     */
    var onFocus: ((Boolean) -> Unit)? = null

    init {
        text = label
        gravity = if (alignStart) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        paint(false)
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            paint(focused)
            TvStyle.focus(view, focused)
            onFocus?.invoke(focused)
        }
    }

    /** 选中态：按进去（凹），与常态的凸起形成纯柔光对比。 */
    fun setSelectedState(value: Boolean) {
        if (selected == value) return
        selected = value
        paint(hasFocus())
    }

    /** 就地改文案（「检查更新」→「正在检查…」这类）。 */
    fun setLabel(value: String) {
        if (text.toString() != value) text = value
    }

    /** 选中态由外部数据改写后重画一次（播放器菜单的选项就是这样）。 */
    fun refresh() = paint(hasFocus())

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        isFocusable = enabled
        paint(hasFocus())
    }

    private fun paint(focused: Boolean) =
        paintButton(this, size, tone, focused, selected, isEnabled, pad ?: size.pad)
}

/**
 * 顶部导航的圆形图标入口（搜索 / 排行榜 / 设置）。
 * 常态是凸起的同色圆盘，焦点是打亮的圆盘 + 光晕，当前页是抬一档的面（图标与常态同为正文色，
 * 靠「抬起来」而不是靠颜色区分）。
 *
 * 控件尺寸由柔光带撑开：调用方给的 48dp 是**底板**直径，
 * 因此这里自己保证最小边长 = 底板 + 两条柔光带。
 */
internal class IconButton(
    context: Context,
    iconRes: Int,
    contentLabel: String,
    private val action: () -> Unit
) : FrameLayout(context) {
    private val dp = { value: Number -> context.tvDp(value) }
    private val icon = ImageView(context)
    private var active = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        contentDescription = contentLabel
        icon.setImageResource(iconRes)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        minimumWidth = dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)
        minimumHeight = dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)
        paint(false)
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused ->
            paint(focused)
            TvStyle.focus(this, focused)
        }
    }

    fun setActive(value: Boolean) {
        active = value
        paint(false)
    }

    private fun paint(focused: Boolean) {
        val pill = TvStyle.RADIUS_PILL
        background = when {
            focused -> TvStyle.soft(
                context, TvStyle.lit, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED,
                glow = TvStyle.litGlow, depth = 1.2f
            )
            // 当前页：抬一档 + 更深的柔光（周围是凸起的常态，抬起来就是「我在这里」）。
            active -> TvStyle.soft(
                context, TvStyle.selectedFace, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED,
                depth = 1.3f
            )
            else -> TvStyle.soft(context, TvStyle.surface, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED)
        }
        icon.setColorFilter(
            when {
                focused -> TvStyle.onLit
                else -> TvStyle.text
            }
        )
    }
}

/**
 * 海报底边那条已播进度条。
 *
 * 原先用 `LinearLayout + 两个 weight 子 View` 表达「已播 / 未播」：一张卡多两个 View，
 * 而且每次改进度都要写 layoutParams、两次 requestLayout。这里改成自绘——
 * 一张卡只占一个 View，改进度只是重画（[percent] 变了才 invalidate）。
 */
internal class PosterProgress(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius = context.tvDp(TvStyle.RADIUS_POSTER).toFloat()
    private val track = RectF()

    /** 已播百分比（0..100）；只有真的变了才重画。 */
    var percent: Int = 0
        set(value) {
            val next = value.coerceIn(0, 100)
            if (next == field) return
            field = next
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        track.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        // 底槽：只圆下面两角（与海报圆角对齐）。
        paint.color = TvStyle.wellDeep
        canvas.drawRoundRect(track, radius, radius, paint)
        // 已播段：只圆左下角。
        val filled = width * (percent / 100f)
        if (filled > 0f) {
            paint.color = TvStyle.accent
            canvas.drawRoundRect(RectF(0f, 0f, filled, height.toFloat()), radius, radius, paint)
        }
    }
}

/**
 * 竖版海报卡：一块凸起底板，里面嵌着海报井（内暗角）、角标、进度条与两行文字。
 *
 * 卡片的焦点态和按钮不同：卡里是图片，底板换不成亮面，因此焦点由「抬一档 + 更深的柔光
 * + 一圈光晕 + 剧名转全白」表达。2026-10-07 主题改版去掉了原来那圈 3dp 强调色细环——
 * 焦点只剩材质自己的凹凸与光晕，不带颜色。
 *
 * 新拟态的处理：
 * - 整卡是一块 [TvStyle.surface] 凸起面板（圆角 [TvStyle.RADIUS_CARD]），焦点时换成抬升面 + 强调色细环 + 外发光；
 * - 海报四周留 4dp 内衬，井口用 [SoftStyle.SHADE] 压一圈暗角，读起来像「嵌进底板」而不是「贴上去」；
 * - 进度条底槽换成更深的凹槽色，已播部分仍是强调色。
 *
 * 卡片自己带 [TvStyle.SOFT_BAND] 的柔光带（就是它的内边距），投影因此不会跑出控件边界。
 */
internal class PosterCard(
    context: Context,
    posterHeight: Int,
    private val loadCover: (ImageView, String) -> Unit
) : LinearLayout(context) {
    private val dp = { value: Number -> context.tvDp(value) }
    private val band = context.softBand()
    /**
     * 这一张卡**现在**是谁。
     *
     * 分类页的栅格是固定窗口 + 行复用（见 `MainActivity.syncGridWindow`）：卡片会被滚出窗口、
     * 回收、再拿去显示别的剧集，因此监听器里不能再捕获某一次的 item，
     * 一律通过这个字段取「当前绑定的是哪一部」。
     */
    var series: Series? = null
    val image = ImageView(context)
    // 卡片下方的剧名与状态：字号比正文小一档，一屏六列时不会盖过海报本身。
    val title = context.tvText("", 14f, TvStyle.textDim, bold = true)
    val status = context.tvText("", 12f, TvStyle.textDim)
    private val poster = FrameLayout(context)
    /** 右下角角标：名次 / 平台自己的角标 / 同系列的「第 N 季」/「正在看」。 */
    private val badge = TextView(context)
    /**
     * 右上角角标：这一部是**第几季**。
     *
     * 季号从剧名里读（见 [SeriesSeasons.badge]）：列表接口里没有 `season_index`
     *（只有详情页的「相关作品」那一栏才有），剧名是列表侧唯一的来源，
     * 因此没有写季号的剧名按第 1 季标，让每张卡都能一眼读出季数。
     * 与右下角那枚共用一套字号、字重、压暗层与内边距，只是一左一右。
     */
    private val season = TextView(context)
    /** 已播进度条：自绘，一张卡只占一个 View（原先的 track/fill/rest 是三个）。 */
    private val progress = PosterProgress(context)
    private var percent = -1
    private var cover = ""

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        setPadding(band, band, band, band)

        poster.clipChildren = true
        poster.clipToPadding = true
        roundCorners(poster, TvStyle.RADIUS_POSTER)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // 海报自己的圆角裁剪**不再单独挂一个 outline**：`poster` 已经 clipToOutline，
        // 子控件画在它的画布里，同样被裁成圆角。一张卡少一个 OutlineProvider 与一层硬件裁剪。
        poster.addView(image, FrameLayout.LayoutParams(-1, -1))

        // 井口暗角：压在封面上的一层，让海报看起来嵌进底板。
        poster.addView(
            View(context).apply {
                background = TvStyle.soft(context, Color.TRANSPARENT, 0, 0, SoftStyle.SHADE)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        // 进度条：一个自绘 View 画「底槽 + 已播段」，替代原来的 track/fill/rest 三个 View。
        progress.visibility = View.GONE
        poster.addView(progress, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM))

        // 两枚角标共用一套样式：同样的字号、字重、压暗层与内边距，只是一左一右。
        // 压在封面上，因此**不跟主题**：浅色主题下 TvStyle.text 是深墨，
        // 压在黑压暗层上会完全看不见（同播放界面的规则）。
        listOf(badge to false, season to true).forEach { (view, top) ->
            view.textSize = TvStyle.POSTER_BADGE_TEXT
            view.setTextColor(TvStyle.playerText)
            view.includeFontPadding = false
            view.setTypeface(view.typeface, Typeface.BOLD)
            view.gravity = if (top) Gravity.TOP or Gravity.END else Gravity.BOTTOM or Gravity.END
            // 压暗层就是角标自己的背景（原先是一个单独的 View 压在下面）：
            // 少一个 View，而且不用 `setShadowLayer`——它在 API 26/27 上会把文字整块拉进软件层再上传位图。
            // 圆角与压暗方向都朝**贴边的那一侧**：下角标最深的一头在下、上角标最深的一头在上，
            // 两枚读起来才像同一口井的两个角。
            view.background = if (top) TvStyle.scrimTop(context, 0x99000000.toInt(), Color.TRANSPARENT)
                else TvStyle.scrim(context, Color.TRANSPARENT, 0x99000000.toInt())
            view.setPadding(dp(6), dp(3), dp(8), dp(3))
            view.visibility = View.GONE
        }
        poster.addView(
            badge,
            FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM).apply {
                leftMargin = dp(5)
                rightMargin = dp(5)
                bottomMargin = dp(5)
            }
        )
        // 右上角那一枚只占内容宽度（不拉满整行）：它是一枚小标签，不该在封面上铺出一条暗带。
        poster.addView(
            season,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(5)
                topMargin = dp(5)
            }
        )

        // 海报井比底板再内缩 4dp：井口才有「嵌进去」的厚度。
        addView(
            poster,
            LinearLayout.LayoutParams(-1, posterHeight).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                topMargin = dp(2)
            }
        )

        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.setPadding(dp(9), dp(8), dp(9), 0)
        status.maxLines = 1
        status.ellipsize = TextUtils.TruncateAt.END
        status.setPadding(dp(9), dp(4), dp(9), dp(3))
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(status, LinearLayout.LayoutParams(-1, -2))

        applyFocus(false)
    }

    /**
     * 绑定一张卡。
     *
     * [seasonBadge] 为 true 时在右上角标出季号（由剧名推出来，见 [SeriesSeasons.badge]）。
     * 详情页的「相关剧集」那一块把它关掉：那一条剧架的右下角已经在写季号了
     * （见 `MainActivity` 里的相关剧集分支），同一张卡的两个角写同一句话是重复。
     */
    fun bind(item: Series, statusText: String, badgeText: String, progressPercent: Int?, rankTop: Boolean, seasonBadge: Boolean = true) {
        series = item
        title.text = item.title
        status.text = statusText
        val seasonText = if (seasonBadge) SeriesSeasons.badge(item.title) else ""
        season.text = seasonText
        season.visibility = if (seasonText.isBlank()) View.GONE else View.VISIBLE
        contentDescription = listOf(badgeText, seasonText, item.title, statusText)
            .filter { it.isNotBlank() }.joinToString("，")
        if (badgeText.isBlank()) {
            badge.visibility = View.GONE
        } else {
            badge.visibility = View.VISIBLE
            badge.text = badgeText
            badge.setTextColor(if (rankTop) TvStyle.tips else TvStyle.playerText)
        }
        setProgress(progressPercent)
        setCover(item.cover)
    }

    fun setCover(url: String) {
        if (url == cover) return
        cover = url
        image.setImageDrawable(null)
        // 空 URL 也要通知加载器：它会把这这张图原来的请求解绑、取消排队。
        // 少了这一步，滚出窗口（或最后一行不满）的卡片会继续为一张看不见的图占着队列与网络。
        loadCover(image, url)
    }

    fun setStatus(value: String) {
        if (status.text.toString() != value) status.text = value
    }

    fun setProgress(value: Int?) {
        val next = value?.coerceIn(0, 100) ?: -1
        if (next == percent) return
        percent = next
        progress.visibility = if (next < 0) View.GONE else View.VISIBLE
        if (next >= 0) progress.percent = next
    }

    fun resizePoster(height: Int) {
        val params = poster.layoutParams
        if (params.height == height) return
        params.height = height
        poster.layoutParams = params
    }

    fun setFocused(focused: Boolean) = applyFocus(focused)

    private fun applyFocus(focused: Boolean) {
        background = TvStyle.soft(
            context,
            if (focused) TvStyle.surfaceHigh else TvStyle.surface,
            TvStyle.RADIUS_CARD,
            TvStyle.SOFT_BAND,
            SoftStyle.RAISED,
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = if (focused) 1.35f else 1f
        )
        title.setTextColor(if (focused) TvStyle.text else TvStyle.textDim)
        status.setTextColor(if (focused) TvStyle.textStrong else TvStyle.textDim)
        TvStyle.focus(this, focused)
    }
}

/**
 * 分区标题：一行粗体文字，从内容线开始。
 *
 * 2026-10-12 起标题前不再有强调色圆点：整页的节奏交给「标题—剧架」的间距本身，
 * 标题只留字，不再带任何装饰（`hint` 仍是可选的一段灰字说明）。
 *
 * 标题行也不挂「更多 ›」文字入口：剧架本身的行末已经有「查看全部」底板，
 * 行内按右键也直达集合页，标题再写一次是重复的。
 */
internal class SectionHeading(
    context: Context,
    title: String,
    hint: String = ""
) : LinearLayout(context) {
    private val dp = { value: Number -> context.tvDp(value) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(18), 0, dp(10))
        addView(context.tvText(title, 24f, TvStyle.textStrong, bold = true), LinearLayout.LayoutParams(-2, -2))
        if (hint.isNotBlank())
            addView(
                context.tvText(hint, 14f, TvStyle.muted),
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) }
            )
        addView(View(context), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
    }
}

/**
 * 互斥开关（排行榜页的四个榜单、分类页的大类与小类）：就是统一按钮的选中态——按进去的凹槽。
 *
 * [size] 让同一套控件能表达层级：分类页的**大类**用默认档，**小类**用紧凑档——
 * 二级分类比一级分类整体小一档（高度 48→40dp、字号 16→15sp、左右内边距 28→18dp），
 * 这样一眼能看出「这是大类下面的子类」，而不是两行一样大的按钮。
 */
internal class ToggleChip(
    context: Context,
    label: String,
    active: Boolean,
    size: ButtonSize = ButtonSize.NORMAL,
    action: () -> Unit
) : TvButton(context, label, size, ButtonTone.SECONDARY, action = action) {
    init { setSelectedState(active) }

    fun setActive(value: Boolean) = setSelectedState(value)
}

/**
 * 选集单元格：网格里的方块，高度、字号、焦点反馈与按钮共用同一套令牌。
 *
 * 常态是凸起的小面板；当前集是**常亮的亮面板**（和主操作同一档：一屏里那块「本来就是它」的亮色），
 * 焦点是亮面板 + 光晕。
 *
 * 为什么当前集不用「按进去」：网格背板本身就是一块凹陷面板，槽里再凹一层就分不清了；
 * 也不用「抬一档」——3% 的明度差在 20 个格子里读不出来，而「你在看第几集」是这张网格
 * 唯一必须一眼看到的信号。因此它借用主操作那一档（常亮 + 亮面上的字），
 * 与旧版的「金环 + 金字」一样醒目，但不带颜色。
 */
internal class EpisodeCell(
    context: Context,
    label: String,
    private val current: Boolean,
    action: () -> Unit
) : TextView(context) {
    private val dp = { value: Number -> context.tvDp(value) }

    init {
        text = label
        textSize = TvStyle.BUTTON_TEXT_NORMAL
        gravity = Gravity.CENTER
        applyTitleStyle()
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        minimumHeight = dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)
        paint(false)
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            paint(focused)
            TvStyle.focus(view, focused)
        }
    }

    private fun paint(focused: Boolean) {
        setTextColor(if(focused) Color.BLACK else if(current) Color.rgb(255,211,110) else Color.WHITE)
        setTypeface(null,if(focused || current) Typeface.BOLD else Typeface.NORMAL)
        background=android.graphics.drawable.GradientDrawable().apply {
            cornerRadius=dp(8).toFloat()
            setColor(if(focused) Color.rgb(255,211,110) else Color.rgb(44,49,60))
            setStroke(dp(if(focused) 3 else 1),if(focused) Color.WHITE else if(this@EpisodeCell.current) Color.rgb(255,211,110) else Color.rgb(70,77,90))
        }
    }

}

/**
 * 播放进度条（对应播放器控制层，**不套新拟态**）。
 *
 * 4dp 圆角轨道 + 18% 白已播进度 + 10dp 圆形滑块：这是播放界面的原始样式，
 * 播放时它压在画面上，任何柔光底板都会和画面抢注意力，因此这里保持原样。
 * 电视端左右键已经负责 seek，这里只做显示，因此直接自绘一个 View，
 * 比套一层 SeekBar 省掉三份 drawable，也不会抢焦点。
 */
internal class SeekIndicator(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = { value: Number -> context.tvDp(value) }

    /** 0f–1f 的播放进度，越界自动收敛。 */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val middle = height / 2f
        val trackHeight = dp(4).toFloat().coerceAtLeast(1f)
        val radius = trackHeight / 2f
        val top = middle - radius
        val bottom = middle + radius
        paint.color = TvStyle.playerLine
        canvas.drawRoundRect(0f, top, width.toFloat(), bottom, radius, radius, paint)
        val knobRadius = dp(5).toFloat()
        val edge = width.toFloat() - knobRadius
        val played = (width * progress).coerceIn(0f, width.toFloat())
        paint.color = TvStyle.playerText
        if (played > radius) canvas.drawRoundRect(0f, top, played, bottom, radius, radius, paint)
        if (width > 0) canvas.drawCircle(played.coerceIn(knobRadius, edge), middle, knobRadius, paint)
    }
}
