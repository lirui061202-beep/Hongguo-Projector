// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class CardMenuTest {
    private fun labels(rows: List<CardMenuItem>) = rows.map { it.label }
    private fun actions(rows: List<CardMenuItem>) = rows.map { it.action }

    @Test fun `home history shelf offers deleting this record`() {
        val rows = CardMenu.items(hasProgress = true, favorite = false, queued = false, inHistory = true, hotList = false, historyCollection = false)
        assertEquals(listOf("继续观看", "收藏这部剧", "加入稍后观看", "查看剧集详情", "删除记录"), labels(rows))
        assertEquals(CardAction.DELETE_HISTORY, rows.last().action)
    }

    @Test fun `history collection keeps delete and manage adjacent`() {
        val rows = CardMenu.items(hasProgress = true, favorite = true, queued = true, inHistory = true, hotList = false, historyCollection = true)
        assertEquals(listOf("继续观看", "取消收藏", "移出稍后观看", "查看剧集详情", "删除记录", "管理历史记录"), labels(rows))
        assertEquals(listOf(CardAction.DELETE_HISTORY, CardAction.MANAGE_HISTORY), actions(rows).takeLast(2))
        // 本机记录页不再出现只对热门列表有意义的项：文案与动作一一对应，不会再有下标错位。
        assertFalse(actions(rows).contains(CardAction.HIDE_FROM_HOT))
    }

    @Test fun `hot list keeps hiding and local shelves do not offer it`() {
        val hot = CardMenu.items(hasProgress = false, favorite = false, queued = false, inHistory = false, hotList = true, historyCollection = false)
        assertEquals(listOf("直接播放", "收藏这部剧", "加入稍后观看", "查看剧集详情", "不在热播榜中显示"), labels(hot))
        assertEquals(CardAction.HIDE_FROM_HOT, hot.last().action)

        val favorites = CardMenu.items(hasProgress = false, favorite = true, queued = false, inHistory = false, hotList = false, historyCollection = false)
        assertEquals(listOf("直接播放", "取消收藏", "加入稍后观看", "查看剧集详情"), labels(favorites))
        assertFalse(favorites.any { it.action == CardAction.DELETE_HISTORY || it.action == CardAction.HIDE_FROM_HOT })
    }

    @Test fun `label follows state while action stays fixed`() {
        val resumed = CardMenu.items(hasProgress = true, favorite = true, queued = true, inHistory = false, hotList = false, historyCollection = false)
        val fresh = CardMenu.items(hasProgress = false, favorite = false, queued = false, inHistory = false, hotList = false, historyCollection = false)
        assertEquals(labels(fresh), listOf("直接播放", "收藏这部剧", "加入稍后观看", "查看剧集详情"))
        assertEquals(actions(resumed), actions(fresh))
    }
}
