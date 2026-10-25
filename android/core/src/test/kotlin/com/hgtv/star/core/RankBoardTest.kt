// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class RankBoardTest {
    /**
     * 首页七个栏目与顺序是需求钉死的：
     * 历史记录 / 我的收藏 / 稍后观看 / 热播漫剧 / 热播短剧 / 热播真人 / 热播AI。
     */
    @Test fun `home columns follow the requested order and wording`() {
        assertEquals(listOf("历史记录","我的收藏","稍后观看","热播漫剧","热播短剧","热播真人","热播AI"),
            HomeLayout.columns.map { it.title })
        assertEquals(listOf("热播漫剧","热播短剧","热播真人","热播AI"),RankBoard.entries.map { it.homeTitle })
    }

    /** 前三个栏目是本机记录（没有榜单），后四个各对应一个榜单，顺序与 [RankBoard] 一致。 */
    @Test fun `local columns carry no board and rank columns map one to one`() {
        assertNull(HomeLayout.board("history")); assertNull(HomeLayout.board("favorites")); assertNull(HomeLayout.board("later"))
        assertEquals(RankBoard.entries,HomeLayout.columns.mapNotNull { it.board })
        assertEquals(RankBoard.entries.map { "rank-${it.storedValue}" },HomeLayout.columns.drop(3).map { it.key })
    }

    /** 剧架 key 是焦点记忆与缓存键的一部分：必须唯一、稳定，且能反查回栏目名与榜单。 */
    @Test fun `column keys are unique and resolve back to title and board`() {
        val keys=HomeLayout.columns.map { it.key }
        assertEquals(keys.size,keys.distinct().size)
        HomeLayout.columns.forEach { column ->
            assertEquals(column.title,HomeLayout.title(column.key))
            assertEquals(column.board,HomeLayout.board(column.key))
        }
        // 认不出的 key 退回自身，界面不会显示空标题。
        assertEquals("unknown",HomeLayout.title("unknown"))
    }

    /**
     * 榜单页的四个按钮文案是需求钉死的短名：漫剧 / 短剧 / 真人 / AI；
     * 页面主标题固定「热播榜」，不跟着按钮变（见界面代码）。
     */
    @Test fun `rank switcher uses the four short labels`() {
        assertEquals(listOf("漫剧","短剧","真人","AI剧"),RankBoard.entries.map { it.label })
        assertEquals(listOf("hot-comic-drama","hot-drama","hot-real-drama","hot-ai-drama"),RankBoard.entries.map { it.route })
    }

    /** 四个榜单的路由与 `rankKey` 一一对应；`rankKey` 同时是本机缓存的键，写错就会串数据。 */
    @Test fun `routes and rank keys are distinct and stable`() {
        assertEquals(listOf("hot-comic-drama","hot-drama","hot-real-drama","hot-ai-drama"),RankBoard.entries.map { it.route })
        assertEquals(listOf("comic","hongguo","real","ai"),RankBoard.entries.map { it.storedValue })
        assertEquals(RankBoard.entries.size,RankBoard.entries.map { it.route }.distinct().size)
        assertEquals(RankBoard.entries.size,RankBoard.entries.map { it.storedValue }.distinct().size)
    }

    /** 旧本机设置 / 缓存里的键读进来要认得；认不出的键退回默认（漫剧榜）。 */
    @Test fun `stored keys round trip and unknown values fall back`() {
        RankBoard.entries.forEach { assertEquals(it,RankBoard.fromStored(it.storedValue)) }
        assertEquals(RankBoard.COMIC,RankBoard.fromStored(null))
        assertEquals(RankBoard.COMIC,RankBoard.fromStored("unknown"))
    }

    /** 来源行必须点名是哪一个榜，四个榜不能写成同一句。 */
    @Test fun `each board names its own source`() {
        assertEquals(RankBoard.entries.size,RankBoard.entries.map { it.source }.distinct().size)
        RankBoard.entries.forEach { assertTrue(it.source.startsWith("来源：红果")) }
    }
}
