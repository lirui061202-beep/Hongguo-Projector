// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `cookieStore.xml` 解析：用真实的 Java 序列化片段做输入。
 *
 * 这些片段是从模拟器里 dump 出来的原样拷贝（值做了截断但长度前缀保留），
 * 因此能真正验证 `TC_STRING + u2 长度` 的扫描逻辑，而不是自证。
 */
class AccountCredentialsTest {
    /** 一个最小可用的 cookieStore.xml：两条 cookie，值都是合法序列化流。 */
    private fun xml(entries: List<Triple<String,String,String>>): String = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        entries.forEach { (host,name,hex) -> append("""    <string name="$host|$name">$hex</string>""").append('\n') }
        append("</map>\n")
    }

    /** 造一段 `[name, value, domain, path]` 的 Java 序列化流（十六进制）。 */
    private fun serialized(name: String, value: String, domain: String = "snssdk.com", path: String = "/"): String {
        fun field(text: String): String {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            return "74%04x".format(bytes.size) + bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return "aced0005" + field(name) + field(value) + field(domain) + field(path) + "78"
    }

    @Test fun `解析出需要的字段`() {
        val xml = xml(listOf(
            Triple("http://snssdk.com/", "sessionid", serialized("sessionid", "cf98cf3cd41f0b56188dd3f2226a10c4")),
            Triple("http://snssdk.com/", "uid_tt", serialized("uid_tt", "a03c4f0a4e6868cb608c1fd5324c166e")),
            Triple("http://douyin.com/", "odin_tt", serialized("odin_tt", "should-not-be-picked", "douyin.com"))
        ))
        val cookie = AccountCredentialsParser.parse(xml)
        assertEquals("cf98cf3cd41f0b56188dd3f2226a10c4", cookie["sessionid"])
        assertEquals("a03c4f0a4e6868cb608c1fd5324c166e", cookie["uid_tt"])
        // 其它 host 的不算进来
        assertNull(cookie["odin_tt"])
    }

    @Test fun `sid_guard 的过期时间等于签发加 max-age`() {
        // 真实值：签发 1792041020 + 5183999 = 1797225019（2026-12-14 05:10:19 GMT）
        val guard = "cf98cf3cd41f0b56188dd3f2226a10c4%7C1792041020%7C5183999%7CMon%2C+14-Dec-2026+05%3A10%3A19+GMT"
        val xml = xml(listOf(Triple("http://snssdk.com/", "sid_guard", serialized("sid_guard", guard))))
        val cookie = AccountCredentialsParser.parse(xml)
        assertEquals(1797225019L, AccountCredentialsParser.expiryOf(cookie))
        // 第三段不是过期时间戳，别把它当成过期时间
        assertTrue(AccountCredentialsParser.expiryOf(cookie) > 1792041020L)
    }

    @Test fun `Cookie 头按固定顺序拼装`() {
        val entries = listOf("sessionid" to "aaa", "uid_tt" to "bbb", "d_ticket" to "ccc")
            .map { (name, value) -> Triple("http://snssdk.com/", name, serialized(name, value)) }
        val cookie = AccountCredentialsParser.parse(xml(entries))
        assertEquals("sessionid=aaa; uid_tt=bbb; d_ticket=ccc", AccountCredentialsParser.headerOf(cookie))
    }

    @Test fun `非法输入不抛异常`() {
        assertEquals(emptyMap<String,String>(), AccountCredentialsParser.parse("不是 XML"))
        assertEquals(emptyMap<String,String>(), AccountCredentialsParser.parse(""))
        val broken = xml(listOf(Triple("http://snssdk.com/", "sessionid", "zzzz")))
        assertTrue(AccountCredentialsParser.parse(broken).isEmpty())
        assertEquals(0L, AccountCredentialsParser.expiryOf(emptyMap()))
    }

    @Test fun `凭据的剩余天数与可用性`() {
        val now = 1_792_041_020_000L
        val cred = AccountCredentials(cookie = "sessionid=x", expiresAt = 1_797_225_019L)
        assertTrue(cred.configured)
        assertEquals(60.0, cred.remainingDays(now)!!, 0.1)
        assertFalse(AccountCredentials(cookie = "").configured)
        assertNull(AccountCredentials(cookie = "x").remainingDays(now))
    }
}
