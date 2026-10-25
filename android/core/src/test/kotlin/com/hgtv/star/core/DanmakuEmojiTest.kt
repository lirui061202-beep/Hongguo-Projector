package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DanmakuEmojiTest {
    @Test fun `bracket tags become glyphs in the three bracket styles`() {
        assertEquals("前面高能😂",DanmakuEmoji.displayText("前面高能[笑哭]"))
        assertEquals("👍这剧真好看",DanmakuEmoji.displayText("【赞】这剧真好看"))
        assertEquals("🐶",DanmakuEmoji.displayText("［狗头］"))
    }

    @Test fun `unknown tags and plain brackets stay as typed`() {
        // 认不出的名字原样保留：不能把用户打的字吃掉。
        assertEquals("[不存在]高能",DanmakuEmoji.displayText("[不存在]高能"))
        assertEquals("看到第[3]集",DanmakuEmoji.displayText("看到第[3]集"))
        // 名字里不允许出现括号：`[前面[笑哭]` 里的外层括号认不出名字，但里层的 `[笑哭]`
        // 仍然是一个合法标签，照常换掉（参考实现同样是这个结果）。
        assertEquals("[前面😂",DanmakuEmoji.displayText("[前面[笑哭]"))
        // 换行把标签截断：跨行的方括号不会被连起来当成一个名字。
        assertEquals("[笑哭\n]高能",DanmakuEmoji.displayText("[笑哭\n]高能"))
        assertEquals("[笑哭\n]高能",DanmakuEmoji.displayText("[笑哭\n]高能"))
    }

    @Test fun `names are case insensitive`() {
        assertEquals(DanmakuEmoji.glyph("OK"),DanmakuEmoji.glyph("ok"))
        assertEquals("👌",DanmakuEmoji.displayText("[OK]"))
    }

    @Test fun `a line splits into text and emoji parts`() {
        val parts=DanmakuEmoji.parts("前面高能[笑哭]快跑")
        assertEquals(listOf("前面高能","😂","快跑"),parts.map { it.text })
        assertEquals(listOf(null,"1f602.webp",null),parts.map { it.asset })
    }

    @Test fun `a line without tags is one text part`() {
        val parts=DanmakuEmoji.parts("这剧真好看")
        assertEquals(1,parts.size)
        assertNull(parts.single().asset)
    }

    @Test fun `multi cluster emoji stay in one part`() {
        // ✌️ 带变体选择符、😮‍💨 用 ZWJ 连：必须整簇查表，不能拆成半个表情。
        val victory=DanmakuEmoji.parts("[胜利]")
        assertEquals(listOf("✌️"),victory.map { it.text })
        assertEquals(listOf("270c-fe0f.webp"),victory.map { it.asset })
        val sigh=DanmakuEmoji.parts("[叹气]")
        assertEquals(listOf("😮‍💨"),sigh.map { it.text })
        assertEquals(listOf("1f62e-200d-1f4a8.webp"),sigh.map { it.asset })
    }

    @Test fun `plain emoji already in the text is drawn as an image too`() {
        // 正文里直接打出来的 emoji 也能认出来：表按字形给，不要求先有标签。
        val parts=DanmakuEmoji.parts("哈哈😂")
        assertEquals(listOf("哈哈","😂"),parts.map { it.text })
        assertEquals("1f602.webp",parts[1].asset)
    }

    @Test fun `two adjacent emoji are two parts`() {
        val parts=DanmakuEmoji.parts("[戳手手]")
        assertEquals(listOf("👉","👈"),parts.map { it.text })
        assertEquals(listOf("1f449.webp","1f448.webp"),parts.map { it.asset })
    }

    @Test fun `platform tags that the reference table missed are covered`() {
        // 60 部剧、1.3 万条弹幕里实际出现的名字（见 CHANGELOG.md 的 0.15.2）。参考实现的表里没有这些，
        // `[哭]`、`[笑]` 这类最高频的标签以前画不出来——这一条把它们钉住，以后删表会立刻红。
        val platformTags=listOf("哭","笑","没看够","探究","吃瓜","敬礼","求爆更","顶帖","什么","你细品","重拳出击","学会了")
        platformTags.forEach { name ->
            val glyph=DanmakuEmoji.glyph(name)
            assertTrue("平台在用的标签 $name 又没字形了",glyph!=null)
            assertTrue("平台在用的标签 $name 没有图片",DanmakuEmoji.assetName(glyph!!)!=null)
            // 标签整条走一遍：必须换成表情，不能原样留着方括号。
            assertEquals(DanmakuEmoji.displayText("[$name]"),glyph)
        }
    }

    @Test fun `every tag resolves to a glyph that has an image on disk`() {
        assertTrue("标签表不该是空的",DanmakuEmoji.tagCount>0)
        assertTrue("标签表太小（${DanmakuEmoji.tagCount} 条）",DanmakuEmoji.tagCount>=200)
        assertTrue("图片表不该是空的",DanmakuEmoji.assetCount>0)
        val directory=assetDirectory()
        val onDisk=directory.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".webp") }.toSet()
        assertTrue("找不到表情图片目录：${directory.absolutePath}",onDisk.size>0)
        // 每一个标签都要翻出字形，字形里出现的每个表情都要有图、图也要真的在磁盘上。
        // 有的标签是**并排两个表情**（`戳手手` → 👉👈），所以按 [DanmakuEmoji.parts] 逐段查，
        // 不假设一个标签只对应一张图。
        val used=mutableSetOf<String>()
        DanmakuEmoji.tagNames.forEach { name ->
            val glyph=DanmakuEmoji.glyph(name)
            assertTrue("标签 $name 没有字形",glyph!=null)
            val parts=DanmakuEmoji.parts("[$name]")
            assertTrue("标签 $name 没有切出任何片段",parts.isNotEmpty())
            assertEquals("标签 $name 切出的文字不对：${parts.map { it.text }}",glyph,parts.joinToString("") { it.text })
            parts.forEach { part ->
                val asset=part.asset ?: throw AssertionError("标签 $name 的片段 ${part.text} 没有图片")
                assertTrue("图片缺失：$asset（$name）",onDisk.contains(asset))
                used+=asset
            }
        }
        // 反过来也不能有死资源：磁盘上的每张图都要有标签用得到。
        assertEquals("有图片没有任何标签用得到",emptySet<String>(),onDisk-used)
    }

    /** 表情图片目录：core 的单测跑在 `core/` 下，图片在 app 模块的 assets 里。 */
    private fun assetDirectory(): File =
        listOf(File("../app/src/main/assets/danmaku_emoji"),File("app/src/main/assets/danmaku_emoji"))
            .firstOrNull { it.isDirectory } ?: File("../app/src/main/assets/danmaku_emoji")
}
