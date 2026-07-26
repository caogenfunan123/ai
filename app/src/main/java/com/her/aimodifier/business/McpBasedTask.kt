package com.her.aimodifier.business

import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.mcp.core.McpClient
import com.her.aimodifier.utils.LogStream

/**
 * 业务模块基类（最终定稿）。
 *
 * 系统联动规则：所有内部模块（BuildEngine / Frida / 抓包 / 模块编译）
 * 禁止直接操作 PRoot，必须全部通过 MCP 调度。
 *
 * 本基类封装 MCP 强制调用流程：
 *   check_env → prepare_task(task_id) → run_command → 任务结束
 *
 * 子类只需提供 [taskId] 与业务命令拼装逻辑。
 */
abstract class McpBasedTask(
    protected val mcpClient: McpClient,
    protected val taskId: String
) {

    /** 日志流句柄（每次执行新建） */
    protected lateinit var log: LogStream.Handle
        private set

    /** 当前执行ID（业务侧生成，用于日志流区分） */
    protected var executionId: String = ""
        private set

    /**
     * MCP 强制流程：check_env → prepare_task → 业务命令 → 完成。
     *
     * @param executionId 业务侧传入的执行ID（用于日志流区分），空则自动生成
     * @param command 业务命令（已含完整路径）
     * @param cwd 工作目录
     */
    suspend fun execute(
        executionId: String = "",
        command: String,
        cwd: String? = null
    ): McpCallResult {
        this.executionId = executionId.ifEmpty { "${taskId}_${System.currentTimeMillis()}" }
        log = LogStream.subscribe(this.executionId)
        log.info("开始任务：$taskId (execution=${this.executionId})")

        // 1. check_env
        log.info("调用 MCP: toolchain_check_env")
        val envResult = mcpClient.mcpCall("${AppConstants.McpPlugins.TOOLCHAIN_MANAGER}.${AppConstants.ToolchainMethods.CHECK_ENV}")
        if (envResult is McpCallResult.Error) {
            log.error("环境检测失败：${envResult.message}")
            LogStream.close(this.executionId)
            return envResult
        }
        (envResult as? McpCallResult.Success)?.let { log.info(it.result) }

        // 2. prepare_task
        log.info("调用 MCP: toolchain_prepare_task task_id=$taskId")
        val prepareResult = mcpClient.mcpCall(
            "${AppConstants.McpPlugins.TOOLCHAIN_MANAGER}.${AppConstants.ToolchainMethods.PREPARE_TASK}",
            mapOf("taskId" to taskId)
        )
        if (prepareResult is McpCallResult.Error) {
            log.error("工具链准备失败：${prepareResult.message}")
            LogStream.close(this.executionId)
            return prepareResult
        }
        (prepareResult as? McpCallResult.Success)?.let { log.info(it.result) }

        // 3. 业务命令
        log.info("调用 MCP: toolchain_run_command")
        log.info("命令：$command")
        val runResult = mcpClient.mcpCallStream(
            "${AppConstants.McpPlugins.TOOLCHAIN_MANAGER}.${AppConstants.ToolchainMethods.RUN_COMMAND}",
            mapOf("command" to command, "cwd" to cwd)
        )
        runResult.collect { line -> log.emit(line) }

        log.info("任务结束：$taskId")
        LogStream.close(this.executionId)
        return McpCallResult.Success("任务执行完成", mapOf("executionId" to this.executionId))
    }
}
