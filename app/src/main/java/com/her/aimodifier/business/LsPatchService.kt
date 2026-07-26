package com.her.aimodifier.business

import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.mcp.core.McpClient

/**
 * LSPatch 免Root 重打包服务（最终定稿）。
 *
 * 系统联动规则：禁止直接操作 PRoot，全部通过 MCP 调度。
 *
 * 用 lspatch.jar 把 LSPosed Hook 逻辑嵌入普通 APK，生成免 Root 安装的独立 APK。
 */
class LsPatchService(mcpClient: McpClient) :
    McpBasedTask(mcpClient, AppConstants.TaskIds.LSPATCH_REPACK) {

    /**
     * 重打包 APK。
     *
     * @param inputApk 原始 APK 路径
     * @param outputApk 输出 APK 路径
     * @param modules 集成的 Xposed 模块（多个用逗号分隔）
     * @param localMode true=local模式 false=manager模式
     */
    suspend fun repack(
        inputApk: String,
        outputApk: String,
        modules: List<String> = emptyList(),
        localMode: Boolean = true,
        executionId: String = ""
    ): McpCallResult {
        val cmd = buildString {
            append("java -jar /opt/toolchain/lspatch/lspatch.jar")
            append(" ").append(inputApk)
            append(" -o ").append(outputApk)
            append(" -l ").append(if (localMode) "0" else "1")
            if (modules.isNotEmpty()) {
                append(" -m ").append(modules.joinToString(","))
            }
        }
        return execute(executionId, cmd, null)
    }
}
