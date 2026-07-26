package com.her.aimodifier.base.navigation

/**
 * 所有页面路由常量。
 *
 * 使用字符串路由（navigation-compose），部分页面接收参数。
 */
object Destinations {

    const val MAIN = "main"

    /** AI 对话主窗口，可选 workspaceId 参数 */
    const val CHAT = "chat?workspaceId={workspaceId}"
    fun chat(workspaceId: String?) = if (workspaceId == null) "chat?workspaceId=" else "chat?workspaceId=$workspaceId"

    /** 工作区管理 */
    const val WORKSPACE = "workspace"

    /** AI 中转站配置 */
    const val AI_SETTING = "ai_setting?workspaceId={workspaceId}"
    fun aiSetting(workspaceId: String? = null) =
        if (workspaceId == null) "ai_setting?workspaceId=" else "ai_setting?workspaceId=$workspaceId"

    /** 本地 GGUF 模型 */
    const val LOCAL_MODEL = "local_model"

    /** 工具箱（容器、工具链） */
    const val TOOLBOX = "toolbox"

    /** MCP 插件查看面板 */
    const val MCP_PANEL = "mcp_panel"

    /** AI 系统提示词（全局 Prompt Memory）*/
    const val PROMPT_MEMORY = "prompt_memory"

    /** 配置导入/导出 */
    const val CONFIG_EXPORT = "config_export"

    const val ARG_WORKSPACE_ID = "workspaceId"
}
