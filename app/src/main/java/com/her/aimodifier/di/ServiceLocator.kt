package com.her.aimodifier.di

import android.content.Context
import com.her.aimodifier.ai.client.OpenAiStreamClient
import com.her.aimodifier.ai.local_gguf.LocalGgufManager
import com.her.aimodifier.ai.memory.GlobalPromptMemoryManager
import com.her.aimodifier.ai.routing.AiTaskRouter
import com.her.aimodifier.container.env.RootEnvironmentDetector
import com.her.aimodifier.container.manager.ProotContainerManager
import com.her.aimodifier.container.snapshot.ContainerSnapshotManager
import com.her.aimodifier.container.toolchain.MirrorConfig
import com.her.aimodifier.container.toolchain.ToolchainDownloadService
import com.her.aimodifier.container.toolchain.ToolchainPathResolver
import com.her.aimodifier.container.toolchain.ToolchainVersionManager
import com.her.aimodifier.data.database.AppDatabase
import com.her.aimodifier.data.pref.EncryptedPrefs
import com.her.aimodifier.data.repository.AiConfigRepository
import com.her.aimodifier.data.repository.ChatSessionRepository
import com.her.aimodifier.data.repository.LocalModelRepository
import com.her.aimodifier.data.repository.WorkspaceRepository
import com.her.aimodifier.filesystem.ProotPathMapper
import com.her.aimodifier.mcp.core.McpClient
import com.her.aimodifier.mcp.plugins.AiModelManagerPlugin
import com.her.aimodifier.workspace.WorkspaceManager
import com.her.aimodifier.mcp.plugins.AndroidControlPluginManager
import com.her.aimodifier.mcp.plugins.ToolchainManagerPlugin

/**
 * 轻量服务定位器（最终定稿版）。
 *
 * 替代 Hilt，避免为额外 KSP 处理器；项目体量较小，手动装配即可。
 * 所有单例通过 [init] 一次性创建。
 *
 * 使用方式：`ServiceLocator.workspaceRepository.list()`
 */
object ServiceLocator {

    @Volatile
    private var initialized = false

    // ---- 数据层 ----
    lateinit var database: AppDatabase
        private set
    lateinit var encryptedPrefs: EncryptedPrefs
        private set
    lateinit var workspaceRepository: WorkspaceRepository
        private set
    lateinit var chatSessionRepository: ChatSessionRepository
        private set
    lateinit var aiConfigRepository: AiConfigRepository
        private set
    lateinit var localModelRepository: LocalModelRepository
        private set

    // ---- 文件系统 ----
    lateinit var prootPathMapper: ProotPathMapper
        private set

    // ---- MCP ----
    lateinit var mcpClient: McpClient
        private set
    lateinit var pluginManager: AndroidControlPluginManager
        private set

    // ---- 容器 ----
    lateinit var rootEnvDetector: RootEnvironmentDetector
        private set
    lateinit var pathResolver: ToolchainPathResolver
        private set
    lateinit var containerManager: ProotContainerManager
        private set
    lateinit var snapshotManager: ContainerSnapshotManager
        private set
    lateinit var mirrorConfig: MirrorConfig
        private set
    lateinit var toolchainDownloadService: ToolchainDownloadService
        private set
    lateinit var versionManager: ToolchainVersionManager
        private set

    // ---- AI ----
    lateinit var openAiClient: OpenAiStreamClient
        private set
    lateinit var localGgufManager: LocalGgufManager
        private set
    lateinit var promptMemory: GlobalPromptMemoryManager
        private set
    lateinit var aiTaskRouter: AiTaskRouter
        private set

    // ---- 工作区 ----
    lateinit var workspaceManager: WorkspaceManager
        private set

    // ---- 业务模块（全部通过 MCP 调度） ----
    lateinit var buildEngine: com.her.aimodifier.business.BuildEngine
        private set
    lateinit var fridaService: com.her.aimodifier.business.FridaService
        private set
    lateinit var mitmproxyService: com.her.aimodifier.business.MitmproxyService
        private set
    lateinit var moduleBuildService: com.her.aimodifier.business.ModuleBuildService
        private set
    lateinit var lsPatchService: com.her.aimodifier.business.LsPatchService
        private set

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext

        // 数据层
        database = AppDatabase.get(appCtx)
        encryptedPrefs = EncryptedPrefs(appCtx)
        workspaceRepository = WorkspaceRepository(database.workspaceDao())
        chatSessionRepository = ChatSessionRepository(database.chatSessionDao())
        aiConfigRepository = AiConfigRepository(database.aiConfigDao(), encryptedPrefs)
        localModelRepository = LocalModelRepository(database.localModelDao())

        // 文件系统
        prootPathMapper = ProotPathMapper()

        // 容器
        rootEnvDetector = RootEnvironmentDetector()
        pathResolver = ToolchainPathResolver(rootEnvDetector)
        mirrorConfig = MirrorConfig(encryptedPrefs)
        toolchainDownloadService = ToolchainDownloadService(appCtx, pathResolver, mirrorConfig)
        containerManager = ProotContainerManager(appCtx, toolchainDownloadService)
        snapshotManager = ContainerSnapshotManager(appCtx)
        versionManager = ToolchainVersionManager(toolchainDownloadService, encryptedPrefs)

        // AI 引擎（先初始化，MCP 插件需要注入）
        openAiClient = OpenAiStreamClient()
        localGgufManager = LocalGgufManager(appCtx, rootEnvDetector)
        promptMemory = GlobalPromptMemoryManager(encryptedPrefs)
        aiTaskRouter = AiTaskRouter(openAiClient, localGgufManager, aiConfigRepository)

        // 工作区管理器
        workspaceManager = WorkspaceManager(workspaceRepository, chatSessionRepository)

        // MCP 核心框架（注入完整 4 依赖）
        pluginManager = AndroidControlPluginManager().apply {
            register(ToolchainManagerPlugin(
                containerManager = containerManager,
                toolchainDownloadService = toolchainDownloadService,
                snapshotManager = snapshotManager,
                rootEnvDetector = rootEnvDetector,
                pathResolver = pathResolver
            ))
            register(AiModelManagerPlugin(
                aiConfigRepository = aiConfigRepository,
                localModelRepository = localModelRepository,
                openAiStreamClient = openAiClient,
                localGgufManager = localGgufManager
            ))
        }
        mcpClient = McpClient().also { it.attachPluginManager(pluginManager) }

        // 业务模块（严格通过 MCP 调度，禁止直接操作 PRoot）
        buildEngine = com.her.aimodifier.business.BuildEngine(mcpClient)
        fridaService = com.her.aimodifier.business.FridaService(mcpClient)
        mitmproxyService = com.her.aimodifier.business.MitmproxyService(mcpClient)
        moduleBuildService = com.her.aimodifier.business.ModuleBuildService(mcpClient)
        lsPatchService = com.her.aimodifier.business.LsPatchService(mcpClient)

        initialized = true
    }
}
