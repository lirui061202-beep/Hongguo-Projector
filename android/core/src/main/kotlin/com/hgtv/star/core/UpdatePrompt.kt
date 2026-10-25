// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 「暂不更新」的记忆。
 *
 * 记的不是「永远不再提示」，而是**当时安装的 versionCode**：
 * 用户点过一次「暂不更新」后，这个版本上不再主动弹窗；
 * 一旦设备上装上了任何新版本（versionCode 变了 —— 手动安装、系统安装器覆盖、
 * 或用户自己从 Releases 下载都算），提示就自动恢复。
 */
data class UpdatePrompt(val declined: Boolean = false, val declinedAt: Long = -1L) {

    /** 当前安装的 [installedCode] 是否已被用户选过「暂不更新」。 */
    fun suppressed(installedCode: Long) = declined && declinedAt == installedCode

    /** 用户点了「暂不更新」：记住当时装的版本。 */
    fun decline(installedCode: Long) = UpdatePrompt(declined = true, declinedAt = installedCode)

    /** 每次进入应用时调用：装上新版本后自动恢复提示。 */
    fun refresh(installedCode: Long) =
        if (declined && declinedAt != installedCode) UpdatePrompt() else this
}
