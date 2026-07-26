package com.her.aimodifier.container.toolchain

import com.her.aimodifier.data.pref.EncryptedPrefs

/**
 * 工具版本管理器（最终定稿）。
 *
 * 支持用户手动切换工具多版本：
 * - Frida 多版本
 * - Gradle 多版本
 * - NDK 多版本
 *
 * 当前选中版本持久化于 [EncryptedPrefs]，工具下载时由 [MirrorConfig] 拼装版本化 URL。
 */
class ToolchainVersionManager(
    private val downloadService: ToolchainDownloadService,
    private val prefs: EncryptedPrefs
) {

    /** 列出某工具的所有可选版本 */
    fun availableVersions(toolName: String): List<String> =
        downloadService.toolVersions()[toolName] ?: emptyList()

    /** 当前选中的版本 */
    fun selectedVersion(toolName: String): String? = prefs.getSelectedToolVersion(toolName)

    /** 切换版本（持久化，下次 prepare_task 时按该版本下载） */
    fun selectVersion(toolName: String, version: String): Boolean {
        val available = availableVersions(toolName)
        if (available.isNotEmpty() && version !in available) return false
        prefs.setSelectedToolVersion(toolName, version)
        return true
    }

    /** 列出所有支持版本切换的工具 */
    fun versionedTools(): Map<String, List<String>> = downloadService.toolVersions()
}
