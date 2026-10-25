package com.hgtv.star.core
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class SignerTest {
    @Test fun matchesPinnedJavaScriptOracleAcrossTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing.json")!!.readText())
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i); val ts=row.getInt("ts"); val url=row.getString("url"); val query=url.substringAfter('?')
            val body=if(row.isNull("body")) null else row.getString("body").toByteArray()
            var seed=17L
            // `random` 必须用具名参数：`Signer` 的第三个参数是设备档案，
            // 尾随 lambda 会被当成它（编译期报类型不匹配）。
            val signer=Signer(clock={ts.toLong()*1000},random={ seed=(seed*1664525+1013904223) and 0xffffffffL; seed/4294967296.0 })
            assertEquals("branch $i",0,signer.branch(query,body,ts))
            assertEquals("hash $i",row.getString("hash"),signer.hashF13(sm3(query.toByteArray()),body?.let{digest("MD5",it)}?:ByteArray(16),ts).hex())
            assertEquals("gorgon $i",row.getString("gorgon"),signer.gorgon(query,body,ts,42131))
            assertEquals("helios $i",row.getString("helios"),signer.helios(ts))
            assertEquals("medusa $i",row.getString("medusa"),signer.medusa(url,body,ts))
        }
    }
}
