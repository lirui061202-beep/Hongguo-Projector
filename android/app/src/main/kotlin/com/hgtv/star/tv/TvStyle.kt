// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 电视端设计令牌：新拟态（Neumorphism / Soft UI）。
 *
 * 与上一版「深色 + 描边 + 强调色块」的设计系统相比，规则换成四条：
 *
 * 1. **全站只有一块材质**。底色 [background]、凸起面 [surface]、凹陷面 [well] 是同一个色相的
 *    三档明度（差值只有 3–5%），层次由**柔光**表达：右下深投影 + 左上浅高光（见 [SoftDrawable]）。
 *    不再用 1dp 描边去勾卡片轮廓——描边会把「软」的观感打回「扁平 + 线框」。
 * 2. **圆角整体放大一档**：卡片 12–14dp、胶囊 26dp、弹窗 24dp。新拟态的柔和来自
 *    「大圆角 + 柔光」，圆角小一半、投影再软也还是硬边。
 * 3. **每个控件四周留 [SOFT_BAND] 的透明带**，投影 / 高光画在这条带里（[SoftDrawable] 的实现细节）。
 *    因此组件不再靠「父级不裁剪」来显示投影，也不会和滚动容器的焦点余量互相争空间：
 *    焦点余量管的是「放大 1.05 倍溢出的那 2.5%」，柔光带管的是「投影和高光」。
 * 4. **状态只靠「凹 / 凸 / 亮」三个台阶表达，不再画任何带颜色的选框**
 *    （2026-10-07 主题改版，见下）：
 *    - 常态 = 凸起面 [surface]；
 *    - 选中 = **凹进去**（[selectedWell] + [SoftStyle.INSET]，开关 / 选项 / 设置项 / 主题行）
 *      或 **抬一档 + 更深的柔光**（[selectedFace]，顶部当前页图标）；
 *      选集里的当前集是**常亮的亮面板**（[lit]，与主操作同一档，见 [EpisodeCell]）；
 *    - 焦点 = 被打亮的 [lit] 亮面板 + [onLit] 近黑字 + [litGlow] 光晕（[SoftStyle.RAISED]）；
 *    - 主操作 = 常亮的亮面板 [lit]。
 *    强调色 [accent] 因此退到只做「线」与「小色块」：进度条、名次星标。
 *
 * 低内存设备保留全部视觉层级，只把过渡时长压到 0，并降低柔光强度（[Soft.quality]）。
 *
 * ## 主题（2026-10-07 改版）：十四套材质 + 去掉选框的颜色
 *
 * 上一版是「灰蓝材质 + 高饱和品牌蓝 `#5980FF`」，用户的原话是「边框是蓝色，和整体风格完全不搭」；
 * 换成石墨 + 香槟金之后，问题只解决了一半——**焦点框与选中项仍然是一圈带颜色的线**。
 * 本轮把两件事一起做掉：
 *
 * 1. **选框不再有颜色**。焦点不再画强调色细环，选中不再画强调色细环、也不再用强调色写字：
 *    焦点就是「被打亮」，选中就是「被按进去 / 被抬起来」，全部由柔光表达（见上面第 4 条）。
 *    强调色只剩进度条、名次星标两处「线与小色块」（区块标题圆点已于 2026-10-12 去掉）。
 * 2. **材质可选**。设置 → 主题外观里有十四套新拟态材质（12 浅 + 2 深，一行 4 个芯片），
 *    [TvTheme] 每套只写三个色值（背景与元素底色 / 亮部阴影色 / 暗部阴影色），其余档位全部派生。
 *
 * | 主题 | 背景与元素底色 | 亮部阴影色（左上） | 暗部阴影色（右下） | 适用场景 |
 * | --- | --- | --- | --- | --- |
 * | [TvTheme.SOFT_GRAY] 经典浅灰 | `#E0E5EC` | `#FFFFFF` | `#A3B1C6` | 音乐播放器、智能家居控制台、计算器工具 |
 * | [TvTheme.CREAMY_MILK] 优雅奶白 | `#F0F3F4` | `#FFFFFF` | `#D1D9E6` | 极简笔记应用、生活记录、白噪音软件 |
 * | [TvTheme.DEEP_DARK] 深邃暗调 | `#2E3238` | `#383D44` | `#24272C` | 科技感仪表盘、车载 UI、暗黑模式切换 |
 * | [TvTheme.COOL_MINT] 高级柔冷 | `#DEE4E7` | `#FFFFFF` | `#B8C4C7` | 医疗健康、天气应用、轻量级办公工具 |
 *
 * 切换主题只改色值，不改任何几何：`TvStyle.applyTheme()` 之后由调用方重建当前页面。
 * 柔光位图里烤着上一套材质的投影色，因此 [applyTheme] 必须丢掉 [Soft] 的缓存
 * （缓存 key 里也带上了主题，见 [themeId]）。
 *
 * 十四套的文字基色是「浅色主题用深墨、深色主题用暖白」，所以 [text] / [muted] 这类令牌
 * 在两个方向上都能直接用。唯一不跟主题走的是**播放界面**：它压在视频上，
 * 永远用 [playerText] 的暖白（见下面「播放界面」一节）。
 *
 * 对比度（`tools/theme-preview.py` 逐套打印）：十二套浅色的正文 7.8–9.6:1、两套深色 10.6–11.1:1，
 * 全部过 WCAG AA；最低一档辅助文字 3.1–5.3:1；强调色只做线，最低的象牙沙 3.3:1 也过了非文字 3:1。
 * 十四套排成 7 列 × 2 行并排比过（`python3 tools/theme-preview.py` → `docs/preview/themes.png`）。
 */
object TvStyle {
    // —— 主题 ——

    /**
     * 当前主题。默认 [TvTheme.DEEP_DARK]（材质原本的那一套），
     * 由 `MainActivity.onCreate` 按本机保存的偏好覆盖，之后只经 [applyTheme] 改动。
     */
    internal var theme: TvTheme = TvTheme.fallback
        private set

    /**
     * 柔光缓存用的主题标识。
     *
     * [SoftDrawable] 把投影 / 高光**预渲染成位图**，位图里烤着当时的材质色，
     * 因此缓存 key 必须带上主题，否则换主题后会画上一套材质的投影。
     */
    internal val themeId: Int get() = theme.ordinal

    /**
     * 切换主题：只改色值，不动任何几何（圆角、柔光带、按钮档位都不变）。
     *
     * 两个副作用是必须的：丢掉柔光位图缓存（里面烤着旧材质的投影色），
     * 并由调用方重建当前页面（已经建好的 View 上挂着旧的背景 Drawable）。
     */
    internal fun applyTheme(value: TvTheme) {
        if (theme == value) return
        theme = value
        Soft.trim()
    }

    // —— 材质：同一块底色的三档明度（数值全部来自当前主题） ——

    /**
     * 页面底色。新拟态要求「底板与控件同色」，这也是凸起面的基准色。
     *
     * 主题把它连同两档阴影色一起给定，其余档位由 [TvTheme] 派生——
     * 上一版这里是一串写死的石墨色值，换主题就得逐条改，漏一条就会出现
     * 「背景是浅色、卡片还是深色」的混搭。
     */
    val background: Int get() = theme.base

    /** 凸起面：比底色亮一档，投影 / 高光由 [SoftDrawable] 负责。 */
    val surface: Int get() = theme.surface

    /** 抬升面：焦点容器、设置分组、抬一档的选中态（顶部当前页图标）。 */
    val surfaceHigh: Int get() = theme.surfaceHigh

    /** 凹陷面：搜索框、海报井、以及**被按进去的选中态**。 */
    val well: Int get() = theme.well

    /** 更深的凹槽：禁用态、播放器进度轨道。 */
    val wellDeep: Int get() = theme.wellDeep

    /** 纯黑：播放器根背景、海报压暗层。不跟主题走（画面上的东西永远压黑）。 */
    val black = Color.rgb(0x0A, 0x0B, 0x0F)

    /**
     * 焦点面：整个材质里最亮的一档。
     *
     * 焦点**不是**「换成强调色实心块」，也**不再带一圈强调色细环**（2026-10-07 主题改版）：
     * 新拟态里「被打亮」才是焦点的母语——同一块材质被推到最亮的一档，
     * 外面再补一层 [litGlow] 光晕，3 米外一眼就能认出来。
     * 浅色主题里它就是白色，深色主题里是暖白 `#F0EAE0`。
     */
    val lit: Int get() = theme.lit

    /** 亮面板上的字：浅色主题是深墨，深色主题是暖近黑。 */
    val onLit: Int get() = theme.onLit

    /** 亮面板外的一圈柔光，让「打亮」看起来是发光的而不是贴纸。 */
    val litGlow: Int get() = theme.litGlow

    /**
     * 抬一档的选中面：给「本来就在凹陷背板里」的控件用（顶部当前页图标）。
     * 选集里的当前集比它更高一档，直接用常亮的 [lit]（见 [EpisodeCell]）。
     *
     * 它们周围已经是凹的，再凹一层就分不清了，所以改用「抬一档 + 更深的柔光」。
     * 其余选中态一律用 [selectedWell]。
     */
    val selectedFace: Int get() = theme.surfaceHigh

    /**
     * 按进去的选中面：开关、选项、设置项、主题行都用它。
     *
     * 这是本轮「去掉选框颜色」之后选中态的主要表达：在一堆凸起的按钮里，
     * 被选中的那一个**凹下去**，凹凸对比比任何一圈彩色描边都清楚，而且不带颜色。
     */
    val selectedWell: Int get() = theme.well

    // —— 柔光 ——

    /** 右下深投影（`setShadowLayer` 的颜色，alpha 就是强度）。 */
    val shadowDark: Int get() = theme.shadowDark

    /** 左上浅高光。 */
    val shadowLight: Int get() = theme.shadowLight

    /** 凹陷内阴影：左上偏深、右下偏亮。 */
    val innerDark: Int get() = theme.innerDark
    val innerLight: Int get() = theme.innerLight

    /** 描边：只留给「必须画一条线」的地方（分隔线、二维码白框），不再是卡片轮廓。 */
    val outline: Int get() = theme.outline

    /** 分组分隔线。 */
    val separator: Int get() = theme.separator

    /**
     * 无色焦点线：只在**凹陷控件**（搜索框）上补一条边界。
     *
     * 凹陷的焦点没法靠「打亮」表达，只能把槽压得更深、再让这条 45% 文字基色的细线加深。
     * 它是中性色，不是选框的颜色。
     */
    val focusLine: Int get() = theme.focusLine

    // —— 强调 ——

    /**
     * 强调色：**只做线与小色块**——进度条、名次星标。
     *
     * 上一版它还负责画焦点环与选中项的文字，观感就是「整体风格里贴了一圈别的颜色」。
     * 本轮把选框交给柔光之后，强调色不再出现在任何焦点 / 选中态上。
     * 十四套各有一支，全部过非文字 3:1：浅色主题走低饱和的自然色（石板 / 钢蓝 / 青瓷 /
     * 鼠尾草 / 橄榄 / 玫瑰灰 / 砂金 / 暖棕 / 香槟金 / 墨绿），深色主题走冷调金属（钢蓝 / 冰蓝）。
     */
    val accent: Int get() = theme.accent

    /** 提示色，只用于评分星标与榜单前三。 */
    val tips: Int get() = theme.tips

    // —— 文字四级（浅色主题是深墨、深色主题是暖白） ——

    /** 正文。 */
    val text: Int get() = theme.text

    /** 80%：区块标题。 */
    val textStrong: Int get() = theme.textStrong

    /** 65%：卡片标题、正文。 */
    val textDim: Int get() = theme.textDim

    /** 40%：辅助说明、次要按钮文字。 */
    val muted: Int get() = theme.muted

    // —— 播放界面：不跟主题走 ——
    // 播放时这些字与线一直压在视频画面上，浅色主题下如果跟着变成深色字就会完全看不见。
    // 因此播放界面固定用暖白 + 黑压暗层，与主题无关（见 docs/UI_NEUMORPHISM.md 的适配清单）。

    /** 播放界面的文字：永远暖白。 */
    val playerText = Color.rgb(0xF4, 0xF3, 0xF1)

    /** 播放界面的次级文字：65% 暖白。 */
    val playerDim = 0xA6F4F3F1.toInt()

    /** 播放进度条轨道：18% 暖白。 */
    val playerLine = 0x2EF4F3F1.toInt()

    /** 75% 黑：时间角标底。 */
    val overlay = 0xBF000000.toInt()

    /** 60% 黑：播放器控制条。 */
    val track = 0x99000000.toInt()

    // —— 比例 ——

    /** 海报宽高比（宽 / 高），对应宫格的 357:477（≈3:4）。 */
    const val POSTER_ASPECT = 3f / 4f

    /** 横版卡宽高比（宽 / 高）。 */
    const val WIDE_ASPECT = 16f / 9f

    /** 分集卡宽高比。 */
    const val EPISODE_ASPECT = 200f / 116f

    // —— 圆角：新拟态整体放大一档 ——

    const val RADIUS_POSTER = 12
    const val RADIUS_CARD = 14
    const val RADIUS_PILL = 26

    /** 模态框面板的圆角：比卡片大、比胶囊小，一眼能看出是浮层。 */
    const val RADIUS_DIALOG = 24
    const val RING = 2

    /** 焦点描边圆角。 */
    const val RING_RADIUS = 14

    // —— 柔光带宽 ——
    // 控件四周为投影 / 高光预留的透明带，也是控件自身的内边距。
    // 5dp 的带里能放下「偏移 1.7dp + 模糊 4.8dp」的投影，观感是柔和的，同时不会把
    // 一屏 6 张的海报架撑得过宽；弹窗、设置分组、播放器底座这类大面板用 9dp。

    const val SOFT_BAND = 6

    /** 大面板的柔光带宽。 */
    const val SOFT_BAND_LARGE = 10

    const val FOCUS_SCALE = 1.05f
    const val PRESS_SCALE = .97f
    const val DURATION = 200L

    /**
     * 焦点余量：放大 [FOCUS_SCALE] 后，控件会画出自身槽位 (FOCUS_SCALE-1)/2 ≈ 2.5%，
     * 175dp 的海报卡就是 4.4dp。**滚动容器必须左右各留这么多内边距**，
     * 否则最左 / 最右那一张的焦点描边会被滚动容器自己裁掉——这正是「第一张卡片左边框看不见」的原因。
     */
    const val FOCUS_BLEED = 7

    /**
     * 纵向焦点余量：比横向大一档，因为卡片是竖的。
     *
     * 放大 [FOCUS_SCALE] 会让控件向上 / 向下各画出自身高度的 (FOCUS_SCALE-1)/2 = 2.5%，
     * 再加上 [RING] 描边有一半画在边界外，一张 288dp 高的海报卡就要 0.025×288 + 1 ≈ 8.2dp；
     * 4K 画布（1920dp 宽）上卡片约 410dp 高，需要约 11.3dp。取 12dp 覆盖到约 440dp 的卡片。
     */
    const val FOCUS_BLEED_VERTICAL = 12

    // —— 按钮 ——
    // 全站按钮只有三档高度，横向内边距与字号跟着档位走；圆角一律胶囊（RADIUS_PILL）。
    // 这里的高度是**底板高度**，控件实际高度还要加上四周的柔光带（见 paintButton）。

    /** 紧凑档：菜单选项、行内文字按钮（更多 ›）。 */
    const val BUTTON_COMPACT = 40

    /** 默认档：返回、分页、重试、收藏、刷新这类常规操作。 */
    const val BUTTON_NORMAL = 48

    /** 主操作档：详情页「开始观看」这类页面里唯一的强调按钮。 */
    const val BUTTON_HERO = 56

    const val BUTTON_PAD_COMPACT = 18
    const val BUTTON_PAD_NORMAL = 28
    const val BUTTON_PAD_HERO = 36

    const val BUTTON_TEXT_COMPACT = 15f
    const val BUTTON_TEXT_NORMAL = 16f
    const val BUTTON_TEXT_HERO = 17f

    /**
     * 海报卡角标的字号：**右下角状态角标与右上角季号共用这一档**。
     *
     * 两枚角标贴在同一个井口的两角，字号不一致会读成两套体系；2026-10-14 起从 18sp 收到 12sp
     * （与卡片状态行同档），不再盖过剧名与封面本身。
     */
    const val POSTER_BADGE_TEXT = 12f

    /** 按钮最小宽度：短文案（「收藏」「重试」）也不会瘦成一条。 */
    const val BUTTON_MIN_WIDTH = 92

    /**
     * 顶部导航栏的高度。
     *
     * 这一条**不画底板**（没有边框、也没有可以按进去的面板），里面最高的一件是圆形图标入口
     * （[BUTTON_NORMAL] + 2 × [SOFT_BAND] = 60dp），上下各留 14dp 就是 88dp；
     * 文字与图标都按内容线（`pageInset()` 78dp）对齐。页头收展动画直接改这一行的高度，
     * 因此它必须是固定值。
     */
    const val TOOLBAR_HEIGHT = 88

    /** 同一行按钮之间、行内控件之间的间距。 */
    const val GAP_INLINE = 12

    /** 网格单元格、密集排布的间距。 */
    const val GAP_TIGHT = 8

    /** 区块之间、按钮行与正文之间的间距。 */
    const val GAP_BLOCK = 16

    /** 大分组之间的间距。 */
    const val GAP_GROUP = 24

    /** 禁用态整体透明度，三档按钮一致。 */
    const val DISABLED_ALPHA = .4f

    /** 低内存设备在 onCreate 中置 false，焦点切换立即生效、不排队动画。 */
    var animate = true

    // —— 形状工厂 ——

    /**
     * 新拟态底板：凸起 / 凹陷 / 只描边三种形态，投影与内阴影由 [SoftDrawable] 绘制。
     * 所有控件的背景都应该走这里，不要再自己拼 [shape]。
     */
    internal fun soft(
        context: Context,
        fill: Int = surface,
        radius: Int = RADIUS_CARD,
        band: Int = SOFT_BAND,
        style: SoftStyle = SoftStyle.RAISED,
        ring: Int = Color.TRANSPARENT,
        ringWidth: Int = 0,
        glow: Int = Color.TRANSPARENT,
        depth: Float = 1f
    ) = SoftDrawable(
        fill = fill,
        radiusPx = radius * context.resources.displayMetrics.density,
        bandPx = band * context.resources.displayMetrics.density,
        style = style,
        ringColor = ring,
        ringWidthPx = ringWidth * context.resources.displayMetrics.density,
        glowColor = glow,
        depth = depth
    )

    /** 凹陷槽：搜索框、海报井、进度轨道。 */
    internal fun well(
        context: Context,
        fill: Int = well,
        radius: Int = RADIUS_CARD,
        band: Int = SOFT_BAND,
        ring: Int = Color.TRANSPARENT,
        ringWidth: Int = 0,
        depth: Float = 1f
    ) = soft(context, fill, radius, band, SoftStyle.INSET, ring, ringWidth, Color.TRANSPARENT, depth)

    /** 只有一圈描边的前景（焦点环），不画投影、不画底。 */
    internal fun ring(
        context: Context,
        color: Int = accent,
        radius: Int = RING_RADIUS,
        band: Int = SOFT_BAND,
        width: Int = RING
    ) = soft(context, Color.TRANSPARENT, radius, band, SoftStyle.RING, color, width)

    /** 纯色圆角底，保留给进度条分段、角标压暗层这类不需要柔光的小色块。 */
    fun shape(
        context: Context,
        color: Int,
        border: Int = Color.TRANSPARENT,
        radius: Int = RADIUS_POSTER,
        stroke: Int = 1
    ) = GradientDrawable().apply {
        val density = context.resources.displayMetrics.density
        setColor(color)
        cornerRadius = radius * density
        if (stroke > 0) setStroke((stroke * density).toInt(), border)
    }

    /**
     * 焦点反馈：放大到 1.05。位移会打乱行内对齐，因此只做缩放。
     * 焦点态的形状变化由控件自己通过 [soft] 完成（打亮的亮面 + 强调色细环 + 外发光）。
     */
    fun focus(view: View, focused: Boolean, scale: Float = FOCUS_SCALE, duration: Long = DURATION) {
        view.animate().cancel()
        view.animate()
            .scaleX(if (focused) scale else 1f)
            .scaleY(if (focused) scale else 1f)
            .setDuration(if (animate) duration else 0L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 竖向渐变，用于海报文字浮层、推荐位底部与播放器控制层。 */
    fun gradient(
        top: Int,
        bottom: Int,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM
    ) = GradientDrawable(orientation, intArrayOf(top, bottom))

    /**
     * 播放器底部控制面板的三段渐变：压在画面上自然暗下去，而不是盖一块不透明的浮层。
     */
    fun panel(
        top: Int = Color.TRANSPARENT,
        middle: Int = 0xA6000000.toInt(),
        bottom: Int = 0xF0000000.toInt()
    ) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(top, middle, bottom)
    )

    /**
     * 播放界面浮层的背板渐变：**播放设置面板与选集面板共用这一份**。
     *
     * 两处原来是各写各的（设置面板 透明→60%→88% 黑，选集 透明→90% 黑），
     * 上半段差得肉眼可见（面板中点 60% 黑 vs 45% 黑），同一个播放界面里两块浮层一深一浅。
     * 收敛成一个函数之后，改一处两处同时生效，不会再飘开。
     */
    fun overlayPanel() = panel(Color.TRANSPARENT, 0x99000000.toInt(), 0xE0000000.toInt())

    /** 只圆底部的纯色形状：贴在海报底边的进度条不能是方角。 */
    fun bottomRounded(context: Context, color: Int, radius: Int = RADIUS_POSTER) = GradientDrawable().apply {
        setColor(color)
        val r = radius * context.resources.displayMetrics.density
        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
    }

    /** 只圆左下角：进度填充的起点与海报圆角对齐。 */
    fun bottomLeftRounded(context: Context, color: Int, radius: Int = RADIUS_POSTER) = GradientDrawable().apply {
        setColor(color)
        val r = radius * context.resources.displayMetrics.density
        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r)
    }

    /**
     * 只圆**顶部**两角的竖向渐变：右上角那枚季号角标的压暗层。
     *
     * 参数与 [scrim] 同序（先上后下），只是圆角换到上边：角标贴在井口上角，
     * 该圆的是它的上缘，下缘要与封面无缝衔接。
     */
    fun scrimTop(context: Context, top: Int, bottom: Int, radius: Int = RADIUS_POSTER) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(top, bottom)
    ).apply {
        val r = radius * context.resources.displayMetrics.density
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    /** 只圆底部的竖向渐变：角标背后的压暗层，上缘要与封面无缝衔接。 */
    fun scrim(context: Context, top: Int, bottom: Int, radius: Int = RADIUS_CARD) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(top, bottom)
    ).apply {
        val r = radius * context.resources.displayMetrics.density
        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
    }

    fun scrimOverlay(topAlpha: Int, bottomAlpha: Int) =
        gradient(Color.argb(topAlpha, 0, 0, 0), Color.argb(bottomAlpha, 0, 0, 0))
}
