package com.her.aimodifier.mcp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * MCP 工具调用结果。
 *
 * 三种形态：
 * - [Success]：单次同步结果
 * - [Error]：失败（含可读 message + 可选 cause）
 * - [Stream]：流式结果（如命令实时日志），通过 Flow 推送 chunk
 */
sealed interface McpCallResult {

    @Serializable
    data class Success(
        val result: String,
        val metadata: Map<String, String> = emptyMap()
    ) : McpCallResult

    @Serializable
    data class Error(
        val code: String,
        val message: String,
        val cause: String? = null
    ) : McpCallResult

    /** 流式结果，调用方订阅 [chunks] 收取增量输出 */
    data class Stream(val chunks: Flow<String>) : McpCallResult
}
