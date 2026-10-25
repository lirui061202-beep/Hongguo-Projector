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
import com.hgtv.star.tv.TvStyle

// 本文件是从 MainActivity.kt 拆出来的「全站常量与静态表（标签下标、栅格列数、面板行号、阈值……）」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/** 远端长列表（排行榜页的四个榜单、搜索结果）底部「展示更多」按钮的 tag，同时用作「焦点留在该按钮上」的标记。 */
internal const val LOAD_MORE="load:more"
/**
 * 顶部四个页面入口的标签下标。
 *
 * 圆形入口的顺序是 **搜索 → 分类 → 榜单 → 设置 → 个人信息**（`nav` 的下标 0..4，
 * 而 [MainActivity.navEntry] 用 `nav[tab-1]` 取，所以这五个标签从 1 开始编号）。
 * 首页是 0：品牌「红果TV」是标识不是按钮，不占入口位。
 */
internal const val TAB_HOME=0
internal const val TAB_SEARCH=1
internal const val TAB_CATEGORY=2
internal const val TAB_RANK=3
internal const val TAB_SETTINGS=4
/**
 * 「个人信息」页：设置按钮右边的那一个入口。
 *
 * 顺序是 **搜索 → 分类 → 榜单 → 设置 → 个人信息**（`nav` 下标 0..4），
 * 它显示的是账号侧的数据（收藏 / 历史 / 点赞 / 预约），与本机记录是两套东西。
 */
internal const val TAB_PROFILE=5

/**
 * 「本机文件导入账号参数」的请求码（[Activity.startActivityForResult]）。
 *
 * 用系统文件选择器（`ACTION_OPEN_DOCUMENT`）而不是自己做浏览界面：
 * 电视上的文件来源五花八门（U 盘 / 下载 / 文件管理应用），交给系统那一套最省事，
 * 也能让用户直接选中 `cookieStore.xml`。
 */
internal const val REQUEST_ACCOUNT_FILE=0x51

/**
 * 「我的」页的四个栏目：收藏 / 历史 / 点赞 / 预约。
 *
 * 与首页那三栏（历史记录 / 我的收藏 / 稍后观看）**不是同一份数据**：这里是账号侧的，
 * 走 App 接口（见 `ContentRepository.accountData`），本机记录不受影响。
 */
internal val ACCOUNT_TABS = listOf(
"history" to "历史",
"favorites" to "收藏",
"likes" to "点赞",
"reserves" to "预约"
)

/**
 * 页头随滚动收起时，「列表算回到最上边」的阈值（dp）。
 *
 * 小于等于它就是展开：留一点余量是因为 ScrollView 在内容刚好一屏、或刚布局完时
 * 会有几像素的偏移，严格按 0 判断会让页头在顶部来回抖。
 */
internal const val HEADER_SHOW_THRESHOLD=12

/** 需要登录态的功能在未导入凭据时的统一提示。 */
internal const val SIGN_IN_TOAST="需要先在「我的」页导入账号参数（sessionid）"

/** 「我的」页导航栏里那个圆形头像的直径（与工具条同一行，不能比圆形入口大）。 */
internal const val PROFILE_AVATAR=40

/** 剧卡栅格一行放几张：与 [HomeScreen.slotWidth] 的六列一致，追加出来的行不会比首屏宽一档。 */
internal const val GRID_COLUMNS=6

/**
 * 芯片行（分类页的二级分类、搜索页的提示词）的固定高度（dp）。
 *
 * 紧凑档按钮底板 40dp + 两条柔光带 12dp = 52dp，上下各留 8dp 给焦点放大后的描边。
 * 这两行**必须固定高度**：换一级分类时小类的数量会变（8 / 24 / 8），
 * 提示词更是随输入实时增减；高度跟着内容走的话下面的卡片行会跟着上下跳。
 */
internal const val CHIP_ROW_HEIGHT=68

/**
 * 搜索框的提示文案与它的字号。
 *
 * 正文是 20sp 粗体，提示跟着那么大会被截断成半句（「输入剧名或关键词（遥控器选字 / 外接…」）；
 * 提示单独降到 [SEARCH_HINT_TEXT]，整句「怎么打字」的说明就都放得下了。
 */
internal const val SEARCH_HINT="输入剧名或关键词 · 遥控器方向键选字，也可外接键盘"
internal const val SEARCH_HINT_TEXT=12f

/**
 * 搜索页导航栏里输入框与两个按钮的档位。
 *
 * 导航栏那一行还挤着四个圆形入口，输入框与按钮按常规档（48dp 底板 / 16sp）摆会太占地方：
 * 输入框收到 [SEARCH_INPUT_HEIGHT]、按钮用紧凑档（40dp / 15sp），
 * 整行因此矮一档、也短一档，给标题与圆形入口让出宽度。
 * 输入框正文跟着按钮字号走（[SEARCH_INPUT_TEXT]），一行里的字读起来才是一个体系。
 */
internal const val SEARCH_INPUT_HEIGHT=TvStyle.BUTTON_COMPACT
internal const val SEARCH_INPUT_TEXT=17f

/**
 * 搜索页导航栏里输入框的固定宽度（dp）。
 *
 * 原来输入框吃满中间那段弹性空间（一屏上千 dp），搜索框长得像一整条横幅；
 * 现在给一个固定宽度，省下来的宽度让给「搜索 / 历史」与右边那排圆形入口之间的空白——
 * 那一组控件靠左聚在一起，右边那排入口仍然贴在右边，中间留出距离。
 */
internal const val SEARCH_INPUT_WIDTH=300

/**
 * 「搜索 / 历史」两枚按钮的最小宽度（dp）。
 *
 * 全站按钮默认最小宽度是 [TvStyle.BUTTON_MIN_WIDTH]（92dp），那是给「收藏」「重试」这类
 * 整行按钮用的；这两个按钮只有两个字，按默认宽度摆出来会各占一大块，
 * 与输入框凑在一起就把导航栏占满了。这里只对这两枚放开最小宽度，让它们按文字宽收紧。
 */
internal const val SEARCH_BUTTON_MIN_WIDTH=0

/** 搜索提示词一次取几条（接口上限 20，不传按 10）。 */
internal const val SUGGEST_COUNT=10

/** 输入停顿多久才去取提示词：每敲一个字都发请求会把接口打满，也会让这一行一直闪。 */
internal const val SUGGEST_DEBOUNCE=220L

/**
 * 首帧（还没量到视口高度时）至少物化几行。
 *
 * 只是「先铺几行再说」的起点：第一帧布局完成后 [MainActivity.syncGridWindow] 会按真实
 * 视口高度重算窗口，多出来的行进回收池。
 */
internal const val GRID_WINDOW_MIN_ROWS=4

/**
 * 窗口最多物化几行（硬上限）。
 *
 * 视口算出来的窗口本来就只有一屏多两行；这个上限是给「必须包含某一行」那条兜底规则用的：
 * 万一要落到的行离视口很远（恢复的焦点、跳转），**不能**把中间那几百行一次性物化出来，
 * 而是先把列表滚过去、再按新的视口算窗口（见 `MainActivity.focusGridIndex`）。
 */
internal const val GRID_WINDOW_MAX_ROWS=12

/** 远端长列表最多向下追加到第几页（与 [ContentRepository.home] 的 `require(page in 1..100)` 对齐）。 */
internal const val MAX_CATALOG_PAGE=100

/**
 * 设置页「主题外观」一行放几个主题芯片。
 * 14 套材质排成 4 行（4+4+4+2），比「一行一个主题」省下三分之二的高度，
 * 芯片宽度按屏宽等分，中文名 + 英文名在 720p 画布上也放得下。
 */
internal const val THEME_GRID_COLUMNS=4

/**
 * 设置页「按键设置」那一栏一行放几格。
 *
 * 六个键（上 / 下 / 左 / 右 / 菜单 / 确定）排成两列三行：每一格都要写出「键名 · 当前动作」，
 * 一行放四格（跟着主题网格走）只会把文案挤成省略号，而两列正好让「上 / 下」「左 / 右」
 * 「菜单 / 确定」各自成行——用户找某一个键时按它旁边的那个键去数就行。
 */
internal const val KEY_GRID_COLUMNS=2

/**
 * 播放设置面板的行号。
 *
 * 面板里好几处「改完就地重画这一行、再把焦点按回同一格」的调用都按行号寻址，
 * 中间插一行就会全部错位（改了清晰度结果选中态落在倍速上）。写死数字的代价已经出现过一次，
 * 因此行号收敛成常量，以后插行只改这里。
 *
 * 注意「弹幕速度 / 弹幕大小 / 弹幕透明度」三行在**隐藏弹幕**时不存在（整行不添加），
 * 所以「定时」的行号不是固定的：用 [sleepRow] 现算，不要再写死 7。
 */
internal const val MENU_ROW_QUALITY=0
internal const val MENU_ROW_SPEED=1
internal const val MENU_ROW_FRAME=2
internal const val MENU_ROW_DANMAKU=3
internal const val MENU_ROW_DANMAKU_SPEED=4
internal const val MENU_ROW_SCALE=5
internal const val MENU_ROW_ALPHA=6

/**
 * 「定时」那一行的下标。
 *
 * 弹幕可见时它在三条可调项之后（[MENU_ROW_ALPHA] + 1），隐藏时那三行根本不添加，
 * 它直接跟在「弹幕」后面（[MENU_ROW_DANMAKU] + 1）。判定与设置页同一条规则
 * （[DanmakuMode.showsStyle]），只是这里要显式传进来——它是 Activity 的字段。
 */
internal fun sleepRow(showsStyle: Boolean)=if(showsStyle) MENU_ROW_ALPHA+1 else MENU_ROW_DANMAKU+1

/**
 * 列表滚动位置最多记住多少页。
 * key 里带着搜索词与页码，用户每搜一个新词就是一个新 key，不设上限就是一条只涨不跌的内存曲线。
 */
internal const val SCROLL_MEMORY=24

/**
 * 首页「再按一次退出」的确认窗口。
 *
 * 第一次按返回只弹一条提示并记下时刻，窗口内再按一次才真的退出；超时之后再按就重新算第一次
 * （提示也再弹一遍）。取 2 秒是为了和 [TvTools.toast] 的显示时长（1.9 秒）对齐：
 * 提示还在屏幕上时按第二次一定算数，用户不必去猜「几秒内有效」。
 */
internal const val EXIT_CONFIRM_WINDOW_MS=2000L

/** 首页按第一次返回时的提示（走 [TvTools.toast]，不吃焦点、不吃按键）。 */
internal const val EXIT_TOAST="再按一次返回键退出"
