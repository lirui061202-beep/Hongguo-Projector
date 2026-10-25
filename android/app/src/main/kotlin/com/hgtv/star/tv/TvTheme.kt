// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Color

/**
 * 主题：十四套新拟态材质（设置 → 主题外观）。
 *
 * 一套新拟态材质只需要三个色值就能完整描述：**背景与元素底色**、
 * **亮部阴影色（左上高光）**、**暗部阴影色（右下投影）**。
 * 因此每个主题只写这三个值（[base] / [light] / [dark]），其余档位全部由它们派生：
 *
 * | 派生 | 算法 | 用途 |
 * | --- | --- | --- |
 * | [surface] 凸起面 | 底色 → 亮部阴影色 45% | 按钮、卡片、设置项的常态 |
 * | [surfaceHigh] 抬升面 | 底色 → 亮部阴影色 72% | 焦点容器、设置分组、抬一档的选中态（当前页图标） |
 * | [well] 凹陷面 | 底色 → 暗部阴影色 45% | 搜索框、海报井、**被按进去的选中态** |
 * | [wellDeep] 深凹槽 | 底色 → 暗部阴影色 72% | 禁用态、进度条轨道 |
 * | [shadowDark] / [shadowLight] | 表里两档阴影色 | 柔光位图的右下投影 / 左上高光 |
 * | [text] 四级 | [ink] 的 100 / 84 / 70 / 62% | 标题 / 区块标题 / 正文 / 辅助 |
 *
 * 「同色 = 没有对比」这条电视端的教训仍然成立（见 [TvStyle] 的说明），
 * 所以凸起面 / 凹陷面不是「和底色一模一样 + 只靠投影分层」，而是各挪一档明度，
 * 差值仍然很小（约 3–5%），层次主要还是柔光在说。
 *
 * 派生而不是照抄的理由：十四套主题共用一套规则，换色时只改三个色值，
 * 不会出现「深色主题的凹陷面按 45% 混、浅色主题的按 30% 混」这种各写各的。
 * 因此「再加一套主题」= 在枚举里加一行（三个色值 + 文字基色 + 强调色 + 提示色），
 * 几何、状态语言、按钮档位一行都不用改；`tools/theme-preview.py` 会自动把它并排画出来，
 * 并按 WCAG 公式跑一遍对比度自检（低于门槛直接报错退出）。
 *
 * ## 十四套材质
 *
 * | 主题 | 背景与元素底色 | 亮部阴影色（左上） | 暗部阴影色（右下） | 气质 / 适用场景 |
 * | --- | --- | --- | --- | --- |
 * | 经典浅灰 Soft Gray | `#E0E5EC` | `#FFFFFF` | `#A3B1C6` | 音乐播放器、智能家居控制台、计算器工具 |
 * | 优雅奶白 Creamy Milk | `#F0F3F4` | `#FFFFFF` | `#D1D9E6` | 极简笔记应用、生活记录、白噪音软件 |
 * | 深邃暗调 Deep Dark | `#2E3238` | `#383D44` | `#24272C` | 科技感仪表盘、车载 UI、暗黑模式切换（默认） |
 * | 高级柔冷 Cool Mint | `#DEE4E7` | `#FFFFFF` | `#B8C4C7` | 医疗健康、天气应用、轻量级办公工具 |
 * | 瓷白 Porcelain | `#E8EBE7` | `#FBFCFA` | `#C2C7C1` | 极简、奢华 |
 * | 月岩灰 Moonstone | `#DDE2E5` | `#F8FAFB` | `#B7C0C5` | 冷静、现代 |
 * | 象牙沙 Ivory Sand | `#E9E4DA` | `#FCFAF4` | `#C6BFB2` | 高端、温暖 |
 * | 银鼠尾草 Silver Sage | `#DCE3DE` | `#F7FBF8` | `#B3BDB7` | 精致、自然 |
 * | 蓝雾 Blue Haze | `#DCE4EA` | `#F7FBFE` | `#B2BEC8` | 科技、克制 |
 * | 香槟丝绸 Champagne Silk | `#E8E1D5` | `#FDF9F1` | `#C5BAA9` | 酒店、奢侈品 |
 * | 灰雾玫瑰 Dusty Mauve | `#E4DEE1` | `#FAF7F9` | `#BCB2B8` | 高级、柔雅 |
 * | 苍岩橄榄 Pale Olive | `#DFE1D8` | `#F8F9F1` | `#B9BDAE` | 建筑、艺术 |
 * | 石墨 Graphite | `#34383D` | `#41464D` | `#25282C` | 专业、硬朗 |
 * | 午夜蓝 Midnight Blue | `#30363F` | `#3D4550` | `#242930` | 科技、旗舰 |
 *
 * 十二套浅色 / 两套深色（[isDark]）走的是同一套派生公式，只是柔光强度与凹陷内阴影的配方不同：
 * 深色主题的投影取 62% 强度，浅色主题取 72%（表里给的暗部阴影色本身就是一块中灰）。
 * 浅色主题的**焦点面就是该主题的亮部阴影色**（`#FBFCFA` 这类近白），深色主题则用一档冷/暖白：
 * 浅色底上「打亮」的余地只到白为止，这是浅色新拟态的固有限制（见 docs/UI_NEUMORPHISM.md 第 7 节）。
 *
 * 文字基色 [ink] 是「浅色主题用深墨、深色主题用暖白」，因此 [TvStyle.text] 这类
 * 令牌在两个方向上都能直接用；只有**播放界面**例外——它压在视频上，
 * 永远用 [TvStyle.playerText] 的暖白，不跟主题走（见 [TvStyle] 的播放器一节）。
 */
internal enum class TvTheme(
    /** 设置里显示的名字（中文在前、英文在后，主题芯片上就是这一个字符串）。 */
    val label: String,
    /** 气质 / 适用场景，设置页里选中项下面那一行说明。 */
    val scene: String,
    /** 落盘用的稳定字符串：改了显示名也不会丢用户的选择。 */
    val storedValue: String,
    /** 背景与元素底色。 */
    val base: Int,
    /** 亮部阴影色：左上高光，也是「被抬起来」的那一档材质。 */
    val light: Int,
    /** 暗部阴影色：右下投影，也是「压进去」的那一档材质。 */
    val dark: Int,
    /** 焦点面：整块材质里最亮的一档。 */
    val lit: Int,
    /** 亮面上的字。 */
    val onLit: Int,
    /** 文字基色：浅色主题是深墨，深色主题是暖白。 */
    val ink: Int,
    /** 强调色：只做线与小色块（进度条、名次星标），不再画任何选框。 */
    val accent: Int,
    /** 提示色：评分星标、榜单前三。 */
    val tips: Int,
    /** 浅色主题还是深色主题：只影响柔光的强度与凹陷内阴影的配方。 */
    val isDark: Boolean
) {
    /** 经典浅灰：最中性的一套，蓝灰底色配一块冷调石板强调色。 */
    SOFT_GRAY(
        "经典浅灰 Soft Gray", "音乐播放器、智能家居控制台、计算器工具", "soft-gray",
        base = 0xFFE0E5EC.toInt(), light = 0xFFFFFFFF.toInt(), dark = 0xFFA3B1C6.toInt(),
        lit = 0xFFFFFFFF.toInt(), onLit = 0xFF2B3444.toInt(), ink = 0xFF39435A.toInt(),
        accent = 0xFF5F6E8C.toInt(), tips = 0xFFC8871F.toInt(), isDark = false
    ),

    /** 优雅奶白：底色再暖一档、再亮一档，强调色是低饱和的暖棕。 */
    CREAMY_MILK(
        "优雅奶白 Creamy Milk", "极简笔记应用、生活记录、白噪音软件", "creamy-milk",
        base = 0xFFF0F3F4.toInt(), light = 0xFFFFFFFF.toInt(), dark = 0xFFD1D9E6.toInt(),
        lit = 0xFFFFFFFF.toInt(), onLit = 0xFF3B4250.toInt(), ink = 0xFF3B4250.toInt(),
        accent = 0xFF8F7355.toInt(), tips = 0xFFC08A2A.toInt(), isDark = false
    ),

    /** 深邃暗调：原来的石墨 + 香槟金，电视在暗环境里最舒服的一套，也是默认值。 */
    DEEP_DARK(
        "深邃暗调 Deep Dark", "科技感仪表盘、车载 UI、暗黑模式切换", "deep-dark",
        base = 0xFF2E3238.toInt(), light = 0xFF383D44.toInt(), dark = 0xFF24272C.toInt(),
        lit = 0xFFF0EAE0.toInt(), onLit = 0xFF1A1612.toInt(), ink = 0xFFF4F3F1.toInt(),
        accent = 0xFFDFB77C.toInt(), tips = 0xFFFFC24B.toInt(), isDark = true
    ),

    /** 高级柔冷：青灰底色配墨绿强调色，冷而不蓝。 */
    COOL_MINT(
        "高级柔冷 Cool Mint", "医疗健康、天气应用、轻量级办公工具", "cool-mint",
        base = 0xFFDEE4E7.toInt(), light = 0xFFFFFFFF.toInt(), dark = 0xFFB8C4C7.toInt(),
        lit = 0xFFFFFFFF.toInt(), onLit = 0xFF2F3A40.toInt(), ink = 0xFF37424A.toInt(),
        accent = 0xFF4E7F79.toInt(), tips = 0xFFC08A2A.toInt(), isDark = false
    ),

    // —— 2026-10-07 追加的十套：八套浅色 + 两套深色，色值来自设计稿的三列表 ——
    // 焦点面（lit）在浅色主题里就是该主题的亮部阴影色：底色到纯白的余地只有 1.1–1.3:1，
    // 再往上没有空间，因此不另造一档「比高光更亮」的白。

    /** 瓷白：冷调近白，气质「极简、奢华」。 */
    PORCELAIN(
        "瓷白 Porcelain", "极简、奢华", "porcelain",
        base = 0xFFE8EBE7.toInt(), light = 0xFFFBFCFA.toInt(), dark = 0xFFC2C7C1.toInt(),
        lit = 0xFFFBFCFA.toInt(), onLit = 0xFF262C29.toInt(), ink = 0xFF343B37.toInt(),
        accent = 0xFF6E7A72.toInt(), tips = 0xFFB8862B.toInt(), isDark = false
    ),

    /** 月岩灰：冷静的中性灰，气质「冷静、现代」。 */
    MOONSTONE(
        "月岩灰 Moonstone", "冷静、现代", "moonstone",
        base = 0xFFDDE2E5.toInt(), light = 0xFFF8FAFB.toInt(), dark = 0xFFB7C0C5.toInt(),
        lit = 0xFFF8FAFB.toInt(), onLit = 0xFF242B31.toInt(), ink = 0xFF333A3F.toInt(),
        accent = 0xFF5C6A73.toInt(), tips = 0xFFB8862B.toInt(), isDark = false
    ),

    /** 象牙沙：暖白偏砂，气质「高端、温暖」。 */
    IVORY_SAND(
        "象牙沙 Ivory Sand", "高端、温暖", "ivory-sand",
        base = 0xFFE9E4DA.toInt(), light = 0xFFFCFAF4.toInt(), dark = 0xFFC6BFB2.toInt(),
        lit = 0xFFFCFAF4.toInt(), onLit = 0xFF2C2820.toInt(), ink = 0xFF3B362D.toInt(),
        accent = 0xFF8A7A5E.toInt(), tips = 0xFFB07C1F.toInt(), isDark = false
    ),

    /** 银鼠尾草：灰绿，气质「精致、自然」。 */
    SILVER_SAGE(
        "银鼠尾草 Silver Sage", "精致、自然", "silver-sage",
        base = 0xFFDCE3DE.toInt(), light = 0xFFF7FBF8.toInt(), dark = 0xFFB3BDB7.toInt(),
        lit = 0xFFF7FBF8.toInt(), onLit = 0xFF232A25.toInt(), ink = 0xFF333B36.toInt(),
        accent = 0xFF5E7A68.toInt(), tips = 0xFFA87C22.toInt(), isDark = false
    ),

    /** 蓝雾：淡蓝灰，气质「科技、克制」。 */
    BLUE_HAZE(
        "蓝雾 Blue Haze", "科技、克制", "blue-haze",
        base = 0xFFDCE4EA.toInt(), light = 0xFFF7FBFE.toInt(), dark = 0xFFB2BEC8.toInt(),
        lit = 0xFFF7FBFE.toInt(), onLit = 0xFF20272E.toInt(), ink = 0xFF2F3944.toInt(),
        accent = 0xFF4F6B87.toInt(), tips = 0xFFB8862B.toInt(), isDark = false
    ),

    /** 香槟丝绸：暖金米，气质「酒店、奢侈品」。 */
    CHAMPAGNE_SILK(
        "香槟丝绸 Champagne Silk", "酒店、奢侈品", "champagne-silk",
        base = 0xFFE8E1D5.toInt(), light = 0xFFFDF9F1.toInt(), dark = 0xFFC5BAA9.toInt(),
        lit = 0xFFFDF9F1.toInt(), onLit = 0xFF2E2818.toInt(), ink = 0xFF3B352A.toInt(),
        accent = 0xFF8A7551.toInt(), tips = 0xFFB07C1F.toInt(), isDark = false
    ),

    /** 灰雾玫瑰：灰紫粉，气质「高级、柔雅」。 */
    DUSTY_MAUVE(
        "灰雾玫瑰 Dusty Mauve", "高级、柔雅", "dusty-mauve",
        base = 0xFFE4DEE1.toInt(), light = 0xFFFAF7F9.toInt(), dark = 0xFFBCB2B8.toInt(),
        lit = 0xFFFAF7F9.toInt(), onLit = 0xFF2A2329.toInt(), ink = 0xFF3B3339.toInt(),
        accent = 0xFF7D6273.toInt(), tips = 0xFFB8862B.toInt(), isDark = false
    ),

    /** 苍岩橄榄：灰橄榄，气质「建筑、艺术」。 */
    PALE_OLIVE(
        "苍岩橄榄 Pale Olive", "建筑、艺术", "pale-olive",
        base = 0xFFDFE1D8.toInt(), light = 0xFFF8F9F1.toInt(), dark = 0xFFB9BDAE.toInt(),
        lit = 0xFFF8F9F1.toInt(), onLit = 0xFF262A20.toInt(), ink = 0xFF373A31.toInt(),
        accent = 0xFF6B7050.toInt(), tips = 0xFFA87C22.toInt(), isDark = false
    ),

    /** 石墨：中性深灰 + 钢蓝强调，气质「专业、硬朗」。 */
    GRAPHITE(
        "石墨 Graphite", "专业、硬朗", "graphite",
        base = 0xFF34383D.toInt(), light = 0xFF41464D.toInt(), dark = 0xFF25282C.toInt(),
        lit = 0xFFEDEFF2.toInt(), onLit = 0xFF14181C.toInt(), ink = 0xFFF4F3F1.toInt(),
        accent = 0xFF9FBFD8.toInt(), tips = 0xFFFFC24B.toInt(), isDark = true
    ),

    /** 午夜蓝：偏蓝深灰 + 冰蓝强调，气质「科技、旗舰」。 */
    MIDNIGHT_BLUE(
        "午夜蓝 Midnight Blue", "科技、旗舰", "midnight-blue",
        base = 0xFF30363F.toInt(), light = 0xFF3D4550.toInt(), dark = 0xFF242930.toInt(),
        lit = 0xFFEFF3F8.toInt(), onLit = 0xFF14181D.toInt(), ink = 0xFFF2F4F7.toInt(),
        accent = 0xFF86C7E8.toInt(), tips = 0xFFFFC24B.toInt(), isDark = true
    );

    /** 凸起面：按钮、卡片、设置项的常态。 */
    val surface: Int get() = mix(base, light, .45f)

    /** 抬升面：焦点容器、设置分组、抬一档的选中态（顶部当前页图标）。 */
    val surfaceHigh: Int get() = mix(base, light, .72f)

    /** 凹陷面：搜索框、海报井，以及**被按进去的选中态**。 */
    val well: Int get() = mix(base, dark, .45f)

    /** 更深的凹槽：禁用态、播放器进度轨道。 */
    val wellDeep: Int get() = mix(base, dark, .72f)

    /**
     * 右下深投影。
     *
     * 深色主题取 62%：底色本身就暗，再淡就看不出「凸起」；浅色主题取 72%，
     * 表里给的暗部阴影色（`#A3B1C6` 这类）本来就是一块中灰，压到七成才有厚度。
     */
    val shadowDark: Int get() = alpha(dark, if (isDark) .62f else .72f)

    /** 左上高光：同一档强度的亮部阴影色。 */
    val shadowLight: Int get() = alpha(light, if (isDark) .62f else .72f)

    /** 凹陷槽的左上内阴影。 */
    val innerDark: Int get() = alpha(dark, if (isDark) .50f else .72f)

    /** 凹陷槽的右下内阴影：浅色主题上白色要够亮才看得出「槽底」。 */
    val innerLight: Int get() = alpha(light, if (isDark) .10f else .50f)

    /** 焦点面外的一圈光晕：跟着焦点面的颜色走，深色主题是暖白光晕。 */
    val litGlow: Int get() = alpha(lit, if (isDark) .20f else .35f)

    // 文字四级的 alpha。
    //
    // 2026-10-07 主题改版把三级调高了一档（80/65/40 → 84/70/62）：底色换成表里给的值之后，
    // 旧的三级在**两个方向上都**掉到了 3:1 以下——浅色主题里 40% 深墨压在 #E0E5EC 上只有 2.1:1，
    // 深色主题里 40% 暖白压在 #2E3238 上也只有 2.1:1（旧底色 #1F2024 更暗，同样一档还有 3.6:1）。
    // 现在最低一档在两个方向上都过 3:1，而四级之间的台阶仍然分得开（浅色 7.8/5.2/3.7/3.1，
    // 深色 11.6/8.7/6.6/5.5），数值由 `tools/theme-preview.py` 的自检打印。

    /** 正文白 / 深墨。 */
    val text: Int get() = ink

    /** 84%：区块标题。 */
    val textStrong: Int get() = alpha(ink, .84f)

    /** 70%：卡片标题、正文。 */
    val textDim: Int get() = alpha(ink, .70f)

    /** 62%：辅助说明（最低一档，两个方向都过 3:1）。 */
    val muted: Int get() = alpha(ink, .62f)

    /** 必须画一条线的地方（二维码衬底边框、分隔线）。 */
    val outline: Int get() = alpha(ink, .10f)

    /** 分组分隔线。 */
    val separator: Int get() = alpha(ink, .08f)

    /**
     * 无色焦点线：45% 文字基色，深浅两个方向都成立。
     *
     * 只有**凹陷控件**（搜索框）用得上：凹陷的焦点没法靠「打亮」表达，
     * 只能把槽压得更深、再让这条中性细线加深。它不是选框的颜色，
     * 是「输入框的边界」，与主题色无关。
     */
    val focusLine: Int get() = alpha(ink, .45f)

    companion object {
        /** 读不到偏好时用深色：电视多在暗环境里看，也是这套材质的原始版本。 */
        val fallback: TvTheme = DEEP_DARK

        /** 按落盘字符串取主题；未知值（降级、手改配置）一律退回 [fallback]。 */
        fun of(value: String?): TvTheme =
            TvTheme.entries.firstOrNull { it.storedValue == value } ?: fallback
    }
}

/** 两色线性混合：[ratio] 为 0 取 [from]，为 1 取 [to]。 */
private fun mix(from: Int, to: Int, ratio: Float): Int = Color.rgb(
    (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt(),
    (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt(),
    (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt()
)

/** 同一个色相、换一档不透明度。 */
private fun alpha(color: Int, ratio: Float): Int =
    (color and 0x00FFFFFF) or (((ratio * 255f).toInt().coerceIn(0, 255)) shl 24)

/**
 * 主题偏好的落盘：一个独立的小 SharedPreferences 文件。
 *
 * 主题是纯界面偏好，因此**不进** `com.hgtv.star.core` 的 `Library`：
 * 那里装的是内容与观看记录，还要参与手机备份的合并与冲突处理，
 * 让一套配色跟着备份一起在设备之间搬家没有意义。
 *
 * 读取是同步的，因为它必须在 `MainActivity.onCreate` 建视图之前完成——
 * 一个只有一项的小文件，代价是一次很短的磁盘读。
 */
internal object TvThemeStore {
    private const val FILE = "native-theme-v1"
    private const val KEY = "theme"

    fun load(context: Context): TvTheme = context
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getString(KEY, null)
        .let(TvTheme::of)

    fun save(context: Context, theme: TvTheme) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, theme.storedValue)
            .apply()
    }
}
