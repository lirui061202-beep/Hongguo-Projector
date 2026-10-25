// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 分类页里的一个小类。[path] 是官网 `/category/{path}` 的路由段（`全部` 就是大类本身）。
 *
 * [path] 与 [label] 都来自官网自己的标签行（`div.pc-tags-*` 里的 `a[href]`），
 * 因此中文名与站点一致；站点改了标签只需要改这一张表。
 */
data class CategoryTag(val label: String, val path: String)

/** 分类页里的一个大类：大类名 + 它下面的小类（第一个固定是「全部」）。 */
data class CategoryGroup(val label: String, val path: String, val tags: List<CategoryTag>)

/**
 * 分类页的三本目录。结构与官网一致（三本目录各自一套标签）：
 *
 * | 大类 | 目录 | 小类数 |
 * | --- | --- | --- |
 * | 漫剧 | `/category/comic-drama` | 全部 + 8 |
 * | 真人 | `/category/real-drama` | 全部 + 24 |
 * | AI剧 | `/category/ai-drama` | 全部 + 8 |
 *
 * 每本目录都是 24 部一页（真人的少数小类不足一页），`pagination.totalPages` 由服务端给出。
 */
object CategoryCatalog {
    private fun tags(path: String, vararg entries: Pair<String,String>) =
        listOf(CategoryTag("全部",path)) + entries.map { CategoryTag(it.first,"$path/${it.second}") }

    val groups: List<CategoryGroup> = listOf(
        CategoryGroup("漫剧","comic-drama",tags("comic-drama",
            "脑洞" to "creative","玄幻" to "fantasy","剧情" to "drama","末世" to "apocalypse",
            "豪门" to "wealthy-family","奇幻" to "wonder","科幻" to "sci-fi","冒险" to "adventure")),
        // 大类名写「真人」而不是「真人剧」：与榜单页那四枚芯片（漫剧 / 短剧 / 真人 / AI）同一套写法，
        // 两个页面上同一个片库分类的名字因此一致。
        CategoryGroup("真人","real-drama",tags("real-drama",
            "爱情" to "romance","年代" to "period","逆袭" to "comeback","传奇" to "legend",
            "成长" to "growth","家庭" to "family","家族" to "clan","萌宝" to "cute-kids",
            "悬疑" to "suspense","惊悚" to "thriller","恐怖" to "horror","志怪" to "supernatural",
            "古装" to "costume","玄幻" to "fantasy","奇幻" to "wonder","都市" to "urban",
            "青春" to "youth","喜剧" to "comedy","科幻" to "sci-fi","灾难" to "disaster",
            "动作冒险" to "action-adventure","战争" to "war","综艺" to "variety","剧情" to "drama")),
        CategoryGroup("AI剧","ai-drama",tags("ai-drama",
            "脑洞" to "creative","玄幻" to "fantasy","剧情" to "drama","末世" to "apocalypse",
            "豪门" to "wealthy-family","奇幻" to "wonder","科幻" to "sci-fi","冒险" to "adventure"))
    )

    /** 默认选中：第一组的「全部」。 */
    val defaultTag: CategoryTag get() = groups.first().tags.first()

    /** 按路径反查它属于哪一组（界面从缓存里恢复选择时用）。 */
    fun groupOf(path: String) = groups.firstOrNull { group -> group.tags.any { it.path==path } }

    /** 当前选择的显示文案，例如「漫剧 · 玄幻」。 */
    fun describe(path: String): String {
        val group = groupOf(path) ?: return path
        val tag = group.tags.firstOrNull { it.path==path } ?: return group.label
        return if (tag.label=="全部") "${group.label} · 全部" else "${group.label} · ${tag.label}"
    }
}
