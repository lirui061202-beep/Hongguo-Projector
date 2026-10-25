// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** The projector home browses short dramas through the same API as search.
 * RankBoard values are retained only as existing shelf/cache keys, not ranking claims.
 */
object ProjectorHome {
    val queries: Map<RankBoard,String> = linkedMapOf(
        RankBoard.HONGGUO to "短剧",
        RankBoard.REAL to "逆袭"
    )

    fun title(board: RankBoard): String = if(board==RankBoard.HONGGUO) "发现短剧" else "逆袭短剧"

    fun load(fetch: (String)->CatalogPage): Map<RankBoard,CatalogPage> {
        val scope=RequestScope.current()
        val pool=Executors.newFixedThreadPool(queries.size) { task ->
            Thread(task,"projector-home").apply { isDaemon=true }
        }
        return try {
            val futures=queries.map { (key,query) ->
                key to pool.submit(Callable {
                    RequestScope.withScope(scope) { runCatching { fetch(query) }.getOrNull() }
                })
            }
            val result=linkedMapOf<RankBoard,CatalogPage>()
            futures.forEach { (key,future) ->
                future.get()?.takeIf { it.items.isNotEmpty() }?.let { result[key]=it }
            }
            if(result.isEmpty()) throw IOException("暂时无法读取短剧，请重试或搜索剧名")
            result
        } finally {
            pool.shutdownNow()
        }
    }
}
