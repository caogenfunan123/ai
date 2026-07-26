package com.her.aimodifier.business

import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.mcp.core.McpClient

/**
 * 模块编译服务（最终定稿）。
 *
 * 系统联动规则：禁止直接操作 PRoot，全部通过 MCP 调度。
 *
 * 支持两类模块：
 * - Magisk 模块：通过 magiskboot + avbtool + busybox 构建 zip
 * - KernelSU 模块：通过 ksud + ndk + jdk + sepolicy-inject 构建
 */
class ModuleBuildService(mcpClient: McpClient) {

    private val magiskTask = MagiskModuleTask(mcpClient)
    private val ksuTask = KsuModuleTask(mcpClient)

    fun magisk(): MagiskModuleTask = magiskTask
    fun ksu(): KsuModuleTask = ksuTask
}

class MagiskModuleTask(mcpClient: McpClient) :
    McpBasedTask(mcpClient, AppConstants.TaskIds.MAGISK_MOD) {

    /**
     * 构建 Magisk 模块 zip。
     *
     * @param moduleDir 模块源码目录（包含 module.prop / system/ 等）
     * @param outputZip 输出 zip 路径
     */
    suspend fun build(
        moduleDir: String,
        outputZip: String,
        executionId: String = ""
    ): McpCallResult {
        val cmd = buildString {
            append("cd ").append(moduleDir).append(" && ")
            append("zip -r ").append(outputZip).append(" . -x '*.git*' && ")
            append("echo '模块打包完成：").append(outputZip).append("'")
        }
        return execute(executionId, cmd, moduleDir)
    }
}

class KsuModuleTask(mcpClient: McpClient) :
    McpBasedTask(mcpClient, AppConstants.TaskIds.KSU_MODULE_BUILD) {

    /**
     * 构建 KernelSU 模块。
     *
     * @param moduleDir 模块源码目录
     * @param outputZip 输出 zip 路径
     */
    suspend fun build(
        moduleDir: String,
        outputZip: String,
        executionId: String = ""
    ): McpCallResult {
        val cmd = buildString {
            append("cd ").append(moduleDir).append(" && ")
            append("ksud module package -o ").append(outputZip).append(" . && ")
            append("echo 'KSU 模块打包完成：").append(outputZip).append("'")
        }
        return execute(executionId, cmd, moduleDir)
    }
}
