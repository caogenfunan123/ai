package com.her.aimodifier.base.constants

/**
 * 全局常量（最终定稿版）。
 */
object AppConstants {

    /** 应用数据库文件名（SQLCipher 加密） */
    const val DB_NAME = "aimodifier.db"

    /** DataStore 配置文件名 */
    const val PREFERENCES_NAME = "aimodifier_prefs"

    /** 加密偏好（兼容旧式 SharedPreferences 加密） */
    const val ENCRYPTED_PREF_NAME = "ai_config"

    /** 通知渠道 */
    object NotificationChannels {
        const val DOWNLOAD = "channel_download"
        const val LONG_TASK = "channel_long_task"
    }

    /** MCP 内置插件 ID（最终定稿） */
    object McpPlugins {
        const val TOOLCHAIN_MANAGER = "android.toolchain_manager"
        const val AI_MODEL_MANAGER = "ai.model.manager"
    }

    /** MCP 工具方法名（android.toolchain_manager 暴露的5个最终方法） */
    object ToolchainMethods {
        const val CHECK_ENV = "toolchain_check_env"
        const val PREPARE_TASK = "toolchain_prepare_task"
        const val RUN_COMMAND = "toolchain_run_command"
        const val SNAPSHOT = "toolchain_snapshot"
        const val CLEAN = "toolchain_clean"
    }

    /** AI 路由策略超时（毫秒），超时后云端请求降级到本地 */
    const val AI_CLOUD_TIMEOUT_MS = 30_000L

    /** 模型列表缓存有效期（毫秒） */
    const val MODEL_LIST_TTL_MS = 24 * 60 * 60 * 1000L

    /** 单条 AI 会话消息最大保留条数（默认） */
    const val DEFAULT_CONTEXT_LENGTH = 8192

    /** 默认 temperature */
    const val DEFAULT_TEMPERATURE = 0.7f

    /** Workspace 来源类型 */
    object WorkspaceSource {
        const val BLANK = "blank"
        const val LOCAL_IMPORT = "local_import"
        const val GIT_CLONE = "git_clone"
    }

    /** AiConfig 作用域 */
    object AiConfigScope {
        const val GLOBAL = "global"
        const val WORKSPACE = "workspace"
    }

    /** 任务 ID 清单（系统核心基准，AI 严格遵守） */
    object TaskIds {
        const val APK_BUILD = "apk_build"
        const val LSPATCH_REPACK = "lspatch_repack"
        const val MAGISK_MOD = "magisk_mod"
        const val KSU_MODULE_BUILD = "ksu_module_build"
        const val FRIDA_HOOK = "frida_hook"
        const val STATIC_REVERSE = "static_reverse"
        const val TRAFFIC_CAPTURE = "traffic_capture"
    }

    /** 容器快照操作类型 */
    object SnapshotAction {
        const val SAVE = "save"
        const val LOAD = "load"
        const val LIST = "list"
        const val DELETE = "delete"
    }

    /** 工具链清理操作类型 */
    object CleanAction {
        const val CACHE = "cache"            // 清理编译缓存
        const val REINSTALL = "reinstall"    // 全部重装
        const val UPDATE = "update"          // 更新全部工具
    }

    /** 强制支持的 ABI（最终定稿：仅 ARM64） */
    const val REQUIRED_ARCH = "arm64-v8a"

    /** TOOL_CALL 正则匹配：提取 //TOOL_CALL: 后面的 JSON */
    const val TOOL_CALL_REGEX = """//TOOL_CALL:\s*(\{[^{}]*\})"""

    /** TOOL_CALL 合法 taskId 清单 */
    val VALID_TASK_IDS = setOf(
        TaskIds.APK_BUILD, TaskIds.LSPATCH_REPACK, TaskIds.FRIDA_HOOK,
        TaskIds.TRAFFIC_CAPTURE, TaskIds.MAGISK_MOD, TaskIds.KSU_MODULE_BUILD,
        TaskIds.STATIC_REVERSE
    )

    /** AI 云端就绪超时（毫秒），超过此时间降级本地 */
    const val CLOUD_READY_TIMEOUT_MS = 3000L

    /** 容器空闲自动休眠超时（毫秒） */
    const val CONTAINER_IDLE_TIMEOUT_MS = 30_000L
}
