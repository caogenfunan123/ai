package com.her.aimodifier.mcp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * MCP 基础通信封装。
 *
 * 对应 JSON-RPC 风格的两个核心方法：
 * - [mcpListTools]：枚举所有已注册插件的所有工具
 * - [mcpCall]：调用具体工具
 *
 * 插件来源由 [com.her.aimodifier.mcp.plugins.AndroidControlPluginManager] 管理；
 * 本类只负责路由和参数解构。
 */
class McpClient(
    private val pluginManager: com.her.aimodifier.mcp.plugins.AndroidControlPluginManager =
        com.her.aimodifier.mcp.plugins.AndroidControlPluginManager()
) {

    /** 由 ServiceLocator 注入实际 pluginManager 后调用 */
    fun attachPluginManager(pm: com.her.aimodifier.mcp.plugins.AndroidControlPluginManager) {
        attachedPluginManager = pm
    }

    @Volatile
    private var attachedPluginManager: com.her.aimodifier.mcp.plugins.AndroidControlPluginManager? = null

    private val effectiveManager: com.her.aimodifier.mcp.plugins.AndroidControlPluginManager
        get() = attachedPluginManager ?: pluginManager

    private val _callHistory = mutableListOf<McpCallRecord>()
    private val callHistoryMaxSize = 20

    /** 获取最近的调用历史记录（最多 20 条） */
    fun getCallHistory(): List<McpCallRecord> = synchronized(_callHistory) {
        _callHistory.toList()
    }

    /** 清空调用历史 */
    fun clearCallHistory() = synchronized(_callHistory) {
        _callHistory.clear()
    }

    private fun recordCall(record: McpCallRecord) = synchronized(_callHistory) {
        _callHistory.add(0, record)
        if (_callHistory.size > callHistoryMaxSize) {
            _callHistory.dropLast(_callHistory.size - callHistoryMaxSize)
        }
    }

    /** 枚举所有插件暴露的所有工具 */
    fun mcpListTools(): List<McpTool> {
        return effectiveManager.allPlugins().flatMap { plugin ->
            plugin.listTools().map { tool ->
                tool.copy(name = "${plugin.pluginId}.${tool.name}")
            }
        }
    }

    /**
     * 调用工具。
     *
     * @param fullToolName 完整工具名 "<pluginId>.<toolName>"
     * @param arguments 参数 map
     */
    suspend fun mcpCall(
        fullToolName: String,
        arguments: Map<String, Any?> = emptyMap()
    ): McpCallResult {
        val startTime = System.currentTimeMillis()
        val argsSnapshot = arguments.toMap()

        val parts = fullToolName.split(".", limit = 2)
        if (parts.size != 2) {
            val duration = System.currentTimeMillis() - startTime
            recordCall(
                McpCallRecord(
                    timestamp = startTime,
                    toolName = fullToolName,
                    arguments = argsSnapshot,
                    result = "[INVALID_TOOL_NAME] 工具名格式错误",
                    durationMs = duration,
                    success = false
                )
            )
            return McpCallResult.Error(
                code = "INVALID_TOOL_NAME",
                message = "工具名格式错误，应为 <pluginId>.<toolName>，实际：$fullToolName"
            )
        }
        val (pluginId, toolName) = parts
        val plugin = effectiveManager.find(pluginId)
            ?: run {
                val duration = System.currentTimeMillis() - startTime
                recordCall(
                    McpCallRecord(
                        timestamp = startTime,
                        toolName = fullToolName,
                        arguments = argsSnapshot,
                        result = "[PLUGIN_NOT_FOUND] 未找到插件：$pluginId",
                        durationMs = duration,
                        success = false
                    )
                )
                McpCallResult.Error(
                    code = "PLUGIN_NOT_FOUND",
                    message = "未找到插件：$pluginId"
                )
            }

        if (plugin !is McpPlugin) {
            val duration = System.currentTimeMillis() - startTime
            recordCall(
                McpCallRecord(
                    timestamp = startTime,
                    toolName = fullToolName,
                    arguments = argsSnapshot,
                    result = "[PLUGIN_NOT_FOUND] 插件类型不匹配",
                    durationMs = duration,
                    success = false
                )
            )
            return McpCallResult.Error(
                code = "PLUGIN_NOT_FOUND",
                message = "插件 $pluginId 类型不匹配"
            )
        }

        val tool = plugin.listTools().firstOrNull { it.name == toolName }
            ?: run {
                val duration = System.currentTimeMillis() - startTime
                recordCall(
                    McpCallRecord(
                        timestamp = startTime,
                        toolName = fullToolName,
                        arguments = argsSnapshot,
                        result = "[TOOL_NOT_FOUND] 插件 $pluginId 未提供工具 $toolName",
                        durationMs = duration,
                        success = false
                    )
                )
                McpCallResult.Error(
                    code = "TOOL_NOT_FOUND",
                    message = "插件 $pluginId 未提供工具 $toolName"
                )
            }

        val missing = tool.inputSchema.filterValues { it.required }
            .filterKeys { key -> arguments[key] == null }
        if (missing.isNotEmpty()) {
            val duration = System.currentTimeMillis() - startTime
            recordCall(
                McpCallRecord(
                    timestamp = startTime,
                    toolName = fullToolName,
                    arguments = argsSnapshot,
                    result = "[MISSING_ARGUMENTS] 缺少必填参数：${missing.keys.joinToString(", ")}",
                    durationMs = duration,
                    success = false
                )
            )
            return McpCallResult.Error(
                code = "MISSING_ARGUMENTS",
                message = "缺少必填参数：${missing.keys.joinToString(", ")}"
            )
        }

        val result = runCatching { plugin.call(toolName, arguments) }
        val duration = System.currentTimeMillis() - startTime

        val callRecord = when (val r = result.getOrNull()) {
            is McpCallResult.Success -> McpCallRecord(
                timestamp = startTime,
                toolName = fullToolName,
                arguments = argsSnapshot,
                result = r.result,
                durationMs = duration,
                success = true
            )
            is McpCallResult.Error -> McpCallRecord(
                timestamp = startTime,
                toolName = fullToolName,
                arguments = argsSnapshot,
                result = "[${r.code}] ${r.message}",
                durationMs = duration,
                success = false
            )
            is McpCallResult.Stream -> McpCallRecord(
                timestamp = startTime,
                toolName = fullToolName,
                arguments = argsSnapshot,
                result = "[STREAM] 流式结果",
                durationMs = duration,
                success = true
            )
            null -> McpCallRecord(
                timestamp = startTime,
                toolName = fullToolName,
                arguments = argsSnapshot,
                result = "[PLUGIN_EXECUTION_ERROR] ${result.exceptionOrNull()?.message ?: "插件执行异常"}",
                durationMs = duration,
                success = false
            )
        }
        recordCall(callRecord)

        return result.getOrElse { e ->
            McpCallResult.Error(
                code = "PLUGIN_EXECUTION_ERROR",
                message = e.message ?: "插件执行异常",
                cause = e.stackTraceToString()
            )
        }
    }

    /** 流式调用的便捷封装：当结果为 [McpCallResult.Stream] 时直接订阅 */
    fun mcpCallStream(
        fullToolName: String,
        arguments: Map<String, Any?> = emptyMap()
    ): Flow<String> = flow {
        when (val result = mcpCall(fullToolName, arguments)) {
            is McpCallResult.Stream -> result.chunks.collect { emit(it) }
            is McpCallResult.Success -> emit(result.result)
            is McpCallResult.Error -> emit("[ERROR ${result.code}] ${result.message}")
        }
    }
}

/** MCP 调用历史记录 */
data class McpCallRecord(
    val timestamp: Long,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val result: String,
    val durationMs: Long,
    val success: Boolean
)