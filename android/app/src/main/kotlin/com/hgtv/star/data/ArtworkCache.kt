// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 封面磁盘缓存：存的是**已经缩到卡片尺寸的 WebP**，不是原图。
 *
 * 为什么不是原图：海报源图是 640×854 左右的 JPEG，而卡片上的海报只有约 280×357。
 * 存原图的话每次命中都要「读 100 KB + 解 640×854 的 JPEG + 再缩一次」，
 * 弱 CPU 上一张 10~40 ms，一屏 24 张就是几百毫秒，用户滚动速度永远快于它。
 * 改成落盘前先缩到目标尺寸再编码成 WebP（一张 15~25 KB），命中时解的就是小图（2~4 ms）。
 *
 * 代价是「同一张封面在不同尺寸的槽位里会各存一份」，因此 key 里带上目标尺寸。
 * 但海报槽位在一台设备上是固定的那几种，实际条目数不会因此膨胀多少，
 * 而整份目录（几百部剧）的封面都进得了这个预算——来回滚动零网络、零大解码。
 *
 * 所有调用都跑在图片工作线程上。
 */
class ArtworkCache(private val directory: File) {
    companion object {
        /** 单条上限：目标尺寸的 WebP 远小于它，超过就是异常数据，不落盘。 */
        private const val MAX_ENTRY_BYTES = 512 * 1024

        /** 总量与条数上限。按每张约 20 KB 算，48 MiB 能装下两千多张，足够整份目录。 */
        private const val MAX_TOTAL_BYTES = 48L * 1024 * 1024
        private const val MAX_ENTRIES = 3000
    }

    private fun file(key: String) = File(
        directory,
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    )

    @Synchronized fun get(key: String): ByteArray? = runCatching {
        val f = file(key)
        // 有效期拉长到 30 天：封面基本不变，早先的 7 天会让长列表「放几天回来又要重下」。
        if (!f.isFile || f.length() !in 1..MAX_ENTRY_BYTES ||
            System.currentTimeMillis() - f.lastModified() !in 0..30L * 24 * 60 * 60 * 1000) null
        else f.readBytes()
    }.getOrNull()

    @Synchronized fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return
        runCatching {
            directory.mkdirs()
            val dest = file(key)
            val temp = File(directory, "${dest.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(dest)) temp.delete()
            val files = directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
            var size = 0L
            files.forEachIndexed { index, f ->
                size += f.length()
                if (size > MAX_TOTAL_BYTES || index >= MAX_ENTRIES) f.delete()
            }
        }
    }
}
