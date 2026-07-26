package com.her.aimodifier.business

import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.mcp.core.McpClient

/**
 * Frida Hook 服务（最终定稿）。
 *
 * 系统联动规则：禁止直接操作 PRoot，全部通过 MCP 调度。
 *
 * 流程：
 * 1. check_env
 * 2. prepare_task("frida_hook") 自动补齐 frida-server / frida-tools / objection
 * 3. run_command 启动 frida-server 后台进程
 * 4. run_command 执行 frida -U -l <script> -f <package>
 */
class FridaService(mcpClient: McpClient) :
    McpBasedTask(mcpClient, AppConstants.TaskIds.FRIDA_HOOK) {

    /** 启动 frida-server（容器内后台） */
    suspend fun startServer(executionId: String = ""): McpCallResult {
        val cmd = "frida-server -l 127.0.0.1:27042 &"
        return execute(executionId, cmd, null)
    }

    /**
     * 附加到目标应用并加载脚本。
     *
     * @param scriptPath JS 脚本路径
     * @param packageName 目标应用包名
     * @param spawn true=spawn模式 false=attach模式
     */
    suspend fun attach(
        scriptPath: String,
        packageName: String,
        spawn: Boolean = true,
        executionId: String = ""
    ): McpCallResult {
        val cmd = buildString {
            append("frida -U")
            if (spawn) append(" -f ").append(packageName) else append(" -n ").append(packageName)
            append(" -l ").append(scriptPath)
            append(" --no-pause")
        }
        return execute(executionId, cmd, null)
    }

    /** 用 objection 启动目标应用 */
    suspend fun objectionExplore(
        packageName: String,
        executionId: String = ""
    ): McpCallResult {
        val cmd = "objection -g $packageName explore"
        return execute(executionId, cmd, null)
    }
}
