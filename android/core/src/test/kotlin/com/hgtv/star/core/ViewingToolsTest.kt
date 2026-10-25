package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class ViewingToolsTest {
    @Test fun `jump validates the exact range without clamping`() {
        assertEquals(0,EpisodeTarget.parse("1",536)); assertEquals(535,EpisodeTarget.parse("536",536))
        for(value in listOf("0","537","-1","1.5","1e2","999999999999999","")) assertNull(value,EpisodeTarget.parse(value,536))
        assertNull(EpisodeTarget.parse("1",0)); assertEquals(103,EpisodeTarget.parse(" 0104 ",536))
    }
    @Test fun `search history is bounded and deduplicated by keyword only`() {
        var rows=emptyList<RecentSearch>()
        repeat(30) { rows=SearchHistory.remember(rows,"词$it") }
        assertEquals(20,rows.size); assertEquals("词29",rows.first().query)
        rows=SearchHistory.remember(rows," 词15 ")
        assertEquals("词15",rows.first().query); assertEquals(1,rows.count { it.query=="词15" })
        // 顶部那两个「漫剧 / 短剧」开关已经去掉：同一个词不再按类型各记一条。
        rows=SearchHistory.remember(rows,"词15")
        assertEquals(1,rows.count { it.query=="词15" })
        assertEquals(rows,SearchHistory.decode(SearchHistory.encode(rows)))
        assertEquals(rows,SearchHistory.remember(rows," "))
        assertEquals(rows,SearchHistory.remember(rows,"x".repeat(81)))
    }
    /**
     * 旧版本的搜索历史是 `{"query":…,"type":"comic"}`：多出来的 `type` 直接忽略，
     * 缺 `query` 或长度越界的条目丢掉。解码必须是「能救多少救多少」，一条坏记录不能清空整份历史。
     */
    @Test fun `corrupt search history is recoverable and legacy type field is ignored`() {
        assertTrue(SearchHistory.decode("broken json").isEmpty())
        val rows=SearchHistory.decode("""[null,{"query":"","type":"comic"},{"query":"a","type":"unknown"},{"query":"a","type":"comic"},{"query":"a"}]""")
        assertEquals(listOf(RecentSearch("a")),rows)
    }
}
