package com.her.aimodifier.mcp.core

/**
 * MCP 插件接口。
 *
 * 每个具体插件实现本接口，注册到 [com.her.aimodifier.mcp.plugins.AndroidControlPluginManager]。
 *
 * 约定：
 * - [pluginId] 全局唯一，对应 [com.her.aimodifier.base.constants.AppConstants.McpPlugins]
 * - [listTools] 返回该插件暴露的所有工具
 * - [call] 接收工具名（短名，不含 pluginId 前缀）+ 参数，返回结果
 */
interface McpPlugin {

    val pluginId: String

    /** 插件显示名 */
    val displayName: String

    /** 列出本插件提供的所有工具 */
    fun listTools(): List<McpTool>

    /**
     * 调用插件工具。
     *
     * @param toolName 工具短名（不含 pluginId 前缀）
     * @param arguments 参数 map（值已按 [McpParam.type] 转换为对应类型）
     */
    suspend fun call(toolName: String, arguments: Map<String, Any?>): McpCallResult
}
