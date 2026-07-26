package com.her.aimodifier.mcp.core

import kotlinx.serialization.Serializable

/**
 * MCP 工具元数据定义。
 *
 * 一个 MCP 插件可暴露多个 tool，每个 tool 有：
 * - 唯一名（plugin.tool 命名）
 * - 描述（供 AI 模型 function calling 选择）
 * - 参数 schema（JSON Schema 简化版）
 */
@Serializable
data class McpTool(
    /** 完整工具名，约定格式："<pluginId>.<toolName>" */
    val name: String,
    /** 简短描述，AI 选择工具时参考 */
    val description: String,
    /** 参数 schema（key=参数名，value=类型/描述） */
    val inputSchema: Map<String, McpParam> = emptyMap()
)

@Serializable
data class McpParam(
    val type: String,            // string / number / boolean / array / object
    val description: String = "",
    val required: Boolean = false,
    val default: String? = null,
    val enum: List<String>? = null
)
