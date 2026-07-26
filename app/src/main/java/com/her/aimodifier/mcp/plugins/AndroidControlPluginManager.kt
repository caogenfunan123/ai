package com.her.aimodifier.mcp.plugins

import com.her.aimodifier.mcp.core.McpPlugin

/**
 * MCP 插件注册管理器。
 *
 * - 维护已注册插件列表
 * - 提供 [find] / [allPlugins] 查询接口供 [com.her.aimodifier.mcp.core.McpClient] 使用
 *
 * 在 [com.her.aimodifier.di.ServiceLocator.init] 中调用 [register] 注册两个内置插件。
 */
class AndroidControlPluginManager {

    private val plugins: MutableMap<String, McpPlugin> = linkedMapOf()

    fun register(plugin: McpPlugin) {
        plugins[plugin.pluginId] = plugin
    }

    fun unregister(pluginId: String) {
        plugins.remove(pluginId)
    }

    fun find(pluginId: String): McpPlugin? = plugins[pluginId]

    fun allPlugins(): List<McpPlugin> = plugins.values.toList()

    fun listPluginIds(): List<String> = plugins.keys.toList()
}
