// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 官网的四个热播榜。首页的四个「热播…」剧架与排行榜页的四个榜单**是同一份数据**：
 * 剧架的行末「查看全部」直接进对应的榜单页，不再另做一套「热门集合页」。
 *
 * | 榜单 | 按钮 / 来源行 | 路由 | `rankKey` | 首页栏目 |
 * | --- | --- | --- | --- | --- |
 * | [COMIC] | 漫剧 | `/rank/hot-comic-drama` | `comic` | 热播漫剧 |
 * | [HONGGUO] | 短剧 | `/rank/hot-drama` | `hongguo` | 热播短剧 |
 * | [REAL] | 真人 | `/rank/hot-real-drama` | `real` | 热播真人 |
 * | [AI] | AI剧 | `/rank/hot-ai-drama` | `ai` | 热播AI |
 *
 * [label] 是榜单页四个切换按钮的文案（很短：漫剧 / 短剧 / 真人 / AI剧），页面主标题固定写「热播榜」；
 * [homeTitle] 是首页那一栏的栏目名；[source] 是来源行（写官网自己的榜名）。
 * 三处都从这一个枚举取，改名字只改这里。
 *
 * [storedValue] 同时是 `rankKey`、本机首页缓存的键后缀，以及 [HomeLayout] 里剧架 key 的后缀；
 * 枚举顺序就是界面顺序（首页四栏、榜单页四个切换芯片）。
 */
enum class RankBoard(
    val storedValue: String,
    /** 榜单按钮的文案（短）：漫剧 / 短剧 / 真人 / AI剧。榜单页主标题固定是「热播榜」。 */
    val label: String,
    /** 首页栏目名。 */
    val homeTitle: String,
    val route: String,
    val source: String
) {
    COMIC("comic", "漫剧", "热播漫剧", "hot-comic-drama", "来源：红果漫剧热播榜"),
    HONGGUO("hongguo", "短剧", "热播短剧", "hot-drama", "来源：红果热播榜 · 全站综合热度"),
    REAL("real", "真人", "热播真人", "hot-real-drama", "来源：红果真人剧热播榜"),
    // 「AI剧」与分类页那个大类的写法一致（分类页是 漫剧 / 真人 / AI剧）。
    AI("ai", "AI剧", "热播AI", "hot-ai-drama", "来源：红果 AI 剧热播榜");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: COMIC
    }
}
