// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class CategoryCatalogTest {
    /** 分类页的三个大类与官网三本目录一一对应。 */
    @Test fun `three groups map to the three site catalogs`() {
        // 大类名与榜单页那四枚芯片同一套写法（漫剧 / 短剧 / 真人 / AI）。
        assertEquals(listOf("漫剧","真人","AI剧"),CategoryCatalog.groups.map { it.label })
        assertEquals(listOf("comic-drama","real-drama","ai-drama"),CategoryCatalog.groups.map { it.path })
    }

    /**
     * 每个大类下面的小类：第一个固定是「全部」（路径就是大类本身），
     * 之后是官网标签行里的那些子类，路径是 `大类/子类`。数量按官网实测钉住
     * （漫剧 8、真人 24、AI剧 8，加上「全部」）。
     */
    @Test fun `each group lists all plus its own sub tags`() {
        CategoryCatalog.groups.forEach { group ->
            assertEquals("全部",group.tags.first().label)
            assertEquals(group.path,group.tags.first().path)
            group.tags.drop(1).forEach { tag ->
                assertTrue(tag.path.startsWith(group.path+"/"))
                assertNotEquals("全部",tag.label)
            }
        }
        assertEquals(listOf(9,25,9),CategoryCatalog.groups.map { it.tags.size })
    }

    /** 官网标签行的中文名与路径逐条对上（抽查每条目录的头尾几条）。 */
    @Test fun `sub tags match the site labels`() {
        val comic=CategoryCatalog.groups[0]
        assertEquals(listOf("全部","脑洞","玄幻","剧情","末世","豪门","奇幻","科幻","冒险"),comic.tags.map { it.label })
        assertEquals(listOf("全部","爱情","年代","逆袭"),CategoryCatalog.groups[1].tags.take(4).map { it.label })
        assertEquals(listOf("综艺","剧情"),CategoryCatalog.groups[1].tags.takeLast(2).map { it.label })
        assertEquals(listOf("全部","脑洞","玄幻","剧情","末世","豪门","奇幻","科幻","冒险"),CategoryCatalog.groups[2].tags.map { it.label })
        assertEquals("real-drama/action-adventure",CategoryCatalog.groups[1].tags.first { it.label=="动作冒险" }.path)
        assertEquals("comic-drama/wealthy-family",comic.tags.first { it.label=="豪门" }.path)
    }

    /** 路径在同一份目录里唯一，否则选中的高亮会同时亮起两枚芯片。 */
    @Test fun `paths are unique inside every group`() {
        CategoryCatalog.groups.forEach { group ->
            val paths=group.tags.map { it.path }
            assertEquals(paths.size,paths.distinct().size)
        }
    }

    /** 默认选中「漫剧 · 全部」；反查与文案都按这一条走。 */
    @Test fun `default selection and describe`() {
        assertEquals("comic-drama",CategoryCatalog.defaultTag.path)
        assertEquals("漫剧 · 全部",CategoryCatalog.describe("comic-drama"))
        assertEquals("漫剧 · 玄幻",CategoryCatalog.describe("comic-drama/fantasy"))
        assertEquals("真人 · 动作冒险",CategoryCatalog.describe("real-drama/action-adventure"))
        assertEquals("AI剧 · 末世",CategoryCatalog.describe("ai-drama/apocalypse"))
        assertEquals(CategoryCatalog.groups[1],CategoryCatalog.groupOf("real-drama/variety"))
        assertNull(CategoryCatalog.groupOf("unknown"))
        // 认不出的路径原样返回，界面不会显示空文案。
        assertEquals("unknown",CategoryCatalog.describe("unknown"))
    }
}
