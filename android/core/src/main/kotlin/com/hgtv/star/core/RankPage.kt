// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/**
 * 榜单页解析：四个热播榜（[RankBoard]）共用这一份实现，只有路由与 `rankKey` 不同。
 *
 * 响应有两种形态，先试第一种、失败再退回第二种：
 *
 * 1. **序列化数据**：HTML 里的 `<script data-fn-name data-fn-args>`，
 *    `data-fn-name` 为 `r`（且 `data-fn-args[1] == "content"`）或 `mergeLoaderData`
 *    （`routerDataFnName == "p"`），`data-fn-args[0]` 为 `rank_<route>/page`。
 *    校验：`isSuccess` 为真、`pagination.pageNum == 请求页`、`totalPages >= 1`。
 * 2. **已渲染的 DOM 兜底**：`article[aria-labelledby^=rank-title-]`（`rank-title-{seriesId}` 即 ID），
 *    名次取 `[class*=badge-number-]`，热度取 `p[class*=metrics-]`，
 *    分页取 `nav[aria-label=榜单分页]`（`[aria-current=page]` 必须等于请求页）。
 *
 * 漫剧榜实测走的是第二种（它的 `mergeLoaderData` 用 `routerDataFnName="s"`，
 * 与其余三个榜的 `p` 不同），因此两条路都必须留着。
 *
 * 只读页面里的序列化数据，**从不执行页面脚本**。
 */
internal object RankPage {
    fun parse(html: String, page: Int, board: RankBoard): CatalogPage {
        val routeKey = "rank_${board.route}/page"
        try {
            val document = Jsoup.parse(html)
            val updatedText = runCatching {
                ContentRepository.extractRouter(html).optJSONObject("loaderData")
                    ?.optJSONObject(routeKey)?.str("updatedText")
            }.getOrNull().orEmpty()
            for (tag in document.select("script[data-fn-name][data-fn-args]")) {
                val name = tag.attr("data-fn-name")
                if (name != "r" && name != "mergeLoaderData") continue
                val args = JSONArray(tag.attr("data-fn-args"))
                if (args.optString(0) != routeKey) continue
                val raw = if (name == "r" && args.optString(1) == "content") args.opt(2) else if (name == "mergeLoaderData") {
                    args.optJSONArray(1)?.objects()?.firstOrNull { it.str("key") == "content" && it.str("routerDataFnName") == "p" }
                        ?.optJSONArray("routerDataFnArgs")?.opt(0)
                } else null
                val payload = when (raw) { is JSONObject -> raw; is String -> JSONObject(raw); else -> continue }
                if (!payload.optBoolean("isSuccess")) throw IOException(board.label + "暂不可用")
                val rows = payload.optJSONArray("rankList") ?: throw IOException(board.label + "格式已变化")
                val pagination = payload.optJSONObject("pagination") ?: throw IOException(board.label + "分页信息缺失")
                val totalPages = pagination.optInt("totalPages", 0)
                if (totalPages < 1 || pagination.optInt("pageNum", page) != page) throw IOException(board.label + "分页信息异常")
                val positions = linkedMapOf<String, RankPosition>()
                val items = rows.objects().map { row ->
                    val count = row.optInt("episodeCount", row.optJSONArray("episodeVids")?.length() ?: 0)
                    val tags = row.optJSONArray("tags") ?: JSONArray()
                    val id = row.str("seriesId").ifEmpty { row.str("id") }
                    if (id !in positions) positions[id] = RankPosition(row.optInt("rank").takeIf { it > 0 }, row.str("heatText"))
                    Series(id, row.str("title"), cleanUrl(row.str("cover")),
                        row.str("description"), if (count > 0) "全 " + count + " 集" else row.str("heatText"),
                        (0 until minOf(tags.length(), 5)).joinToString(" · ") { tags.optString(it) })
                }.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
                return CatalogPage(items, items.isNotEmpty() && page < minOf(totalPages, 100),
                    ComicRanking(positions.filterKeys { id -> items.any { it.id == id } }, minOf(totalPages, 100), updatedText))
            }
            return renderedPage(document, html, page, board)
        } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException(board.label + "格式已变化", e) }
    }

    /** 已渲染的 DOM 兜底：榜单卡片与分页都从页面上读，并校验路由与页码。 */
    private fun renderedPage(document: Document, html: String, page: Int, board: RankBoard): CatalogPage {
        val route = ContentRepository.extractRouter(html).optJSONObject("loaderData")
            ?.optJSONObject("rank_" + board.route + "/page") ?: throw IOException("页面没有" + board.label + "数据")
        if (route.str("rankKey") != board.storedValue || route.optInt("pageNum") != page) throw IOException(board.label + "分页信息异常")
        val positions = linkedMapOf<String, RankPosition>()
        val items = document.select("article[aria-labelledby^=rank-title-]").mapNotNull { article ->
            val heading = article.attr("aria-labelledby")
            val id = heading.removePrefix("rank-title-")
            val title = article.getElementById(heading)?.text().orEmpty()
            if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
            if (id !in positions) positions[id] = RankPosition(
                article.selectFirst("[class*=badge-number-]")?.text()?.toIntOrNull()?.takeIf { it > 0 },
                article.selectFirst("p[class*=metrics-]")?.text().orEmpty())
            Series(id, title, article.selectFirst("img[src]")?.attr("src").orEmpty(),
                article.selectFirst("p[class*=description-]")?.text().orEmpty(),
                article.selectFirst("p[class*=metrics-]")?.text().orEmpty(),
                article.select("p[class*=categories-] > span").take(5).joinToString(" · ") { it.text() })
        }.distinctBy { it.id }
        if (items.isEmpty()) throw IOException("页面没有" + board.label + "数据")
        val paging = document.selectFirst("nav[aria-label=榜单分页]") ?: throw IOException(board.label + "分页信息缺失")
        if (paging.selectFirst("[aria-current=page]")?.text()?.toIntOrNull() != page) throw IOException(board.label + "分页信息异常")
        val lastPage = maxOf(page, paging.select("a[href]").mapNotNull { it.attr("href").substringAfter("?page=", "").substringBefore('&').toIntOrNull() }.maxOrNull() ?: page)
        return CatalogPage(items, page < minOf(lastPage, 100), ComicRanking(positions, minOf(lastPage, 100), route.str("updatedText")))
    }
}
