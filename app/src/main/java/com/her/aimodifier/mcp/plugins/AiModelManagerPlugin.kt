package com.her.aimodifier.mcp.plugins

import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.data.database.entity.AiConfigEntity
import com.her.aimodifier.data.database.entity.LocalModelEntity
import com.her.aimodifier.data.repository.AiConfigRepository
import com.her.aimodifier.data.repository.LocalModelRepository
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.mcp.core.McpParam
import com.her.aimodifier.mcp.core.McpPlugin
import com.her.aimodifier.mcp.core.McpTool
import com.her.aimodifier.utils.DownloadUtil
import com.her.aimodifier.utils.HashUtil
import com.her.aimodifier.ai.local_gguf.LocalGgufManager
import com.her.aimodifier.ai.client.OpenAiStreamClient
import com.her.aimodifier.ai.client.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * AI 模型调度 MCP 插件。
 *
 * 暴露工具：
 * - `list_models`：列出当前可用模型（云端 + 本地）
 * - `switch_model`：切换当前会话使用的模型
 * - `load_local_model`：加载本地 GGUF 模型到内存
 * - `unload_local_model`：卸载本地模型
 * - `ai_set_custom_endpoint`：保存自定义 AI 中转站配置
 * - `ai_fetch_remote_models`：拉取远端 /v1/models 模型列表
 * - `ai_download_model_manual`：手动下载模型文件并入库
 * - `ai_verify_model_integrity`：校验本地模型文件 SHA256 完整性
 * - `ai_context_bind`：查询指定 workspace 的生效 AI 配置
 */
class AiModelManagerPlugin(
    private val aiConfigRepository: AiConfigRepository,
    private val localModelRepository: LocalModelRepository,
    private val openAiStreamClient: OpenAiStreamClient,
    private val localGgufManager: LocalGgufManager
) : McpPlugin {

    override val pluginId: String = AppConstants.McpPlugins.AI_MODEL_MANAGER
    override val displayName: String = "AI 模型调度"

    override fun listTools(): List<McpTool> = listOf(

        McpTool(
            name = "list_models",
            description = "列出当前 workspace 可用的 AI 模型（云端中转站 + 本地 GGUF）",
            inputSchema = mapOf(
                "workspaceId" to McpParam(type = "string", description = "工作区ID，空则查全局")
            )
        ),

        McpTool(
            name = "switch_model",
            description = "切换当前会话使用的模型",
            inputSchema = mapOf(
                "modelName" to McpParam(type = "string", description = "模型名", required = true),
                "useLocal" to McpParam(type = "boolean", description = "是否切换到本地模型，默认 false")
            )
        ),

        McpTool(
            name = "load_local_model",
            description = "加载本地 GGUF 模型到内存（同时只允许加载一个）",
            inputSchema = mapOf(
                "modelId" to McpParam(type = "number", description = "本地模型ID", required = true)
            )
        ),

        McpTool(
            name = "unload_local_model",
            description = "卸载所有已加载的本地 GGUF 模型，释放内存"
        ),

        McpTool(
            name = "ai_set_custom_endpoint",
            description = "保存自定义 AI 中转站配置（支持 global / workspace 作用域）",
            inputSchema = mapOf(
                "baseUrl" to McpParam(type = "string", description = "中转站 BaseURL", required = true),
                "apiKey" to McpParam(type = "string", description = "API Key", required = true),
                "defaultModel" to McpParam(type = "string", description = "默认模型名", required = true),
                "timeoutMs" to McpParam(type = "number", description = "请求超时毫秒数，默认 30000"),
                "contextLength" to McpParam(type = "number", description = "上下文长度，默认 8192"),
                "temperature" to McpParam(type = "number", description = "采样温度，默认 0.7"),
                "scope" to McpParam(type = "string", description = "作用域：global / workspace", required = true),
                "workspaceId" to McpParam(type = "string", description = "workspace ID（scope=workspace 时必填）")
            )
        ),

        McpTool(
            name = "ai_fetch_remote_models",
            description = "调用远端 /v1/models 拉取可用模型列表",
            inputSchema = mapOf(
                "baseUrl" to McpParam(type = "string", description = "中转站 BaseURL", required = true),
                "apiKey" to McpParam(type = "string", description = "API Key", required = true)
            )
        ),

        McpTool(
            name = "ai_download_model_manual",
            description = "手动下载 GGUF 模型文件（支持断点续传）并注册到本地模型库",
            inputSchema = mapOf(
                "modelUrl" to McpParam(type = "string", description = "模型下载 URL", required = true),
                "modelName" to McpParam(type = "string", description = "模型显示名", required = true),
                "saveDir" to McpParam(type = "string", description = "保存目录绝对路径", required = true)
            )
        ),

        McpTool(
            name = "ai_verify_model_integrity",
            description = "校验本地模型文件 SHA256 完整性",
            inputSchema = mapOf(
                "modelId" to McpParam(type = "number", description = "本地模型ID", required = true)
            )
        ),

        McpTool(
            name = "ai_context_bind",
            description = "查询指定 workspace 生效的 AI 配置（workspace 优先，回退 global）",
            inputSchema = mapOf(
                "workspaceId" to McpParam(type = "string", description = "工作区ID", required = true)
            )
        )
    )

    override suspend fun call(toolName: String, arguments: Map<String, Any?>): McpCallResult {
        return when (toolName) {
            "list_models" -> listModels(arguments["workspaceId"] as? String)
            "switch_model" -> {
                val name = arguments["modelName"] as? String
                    ?: return McpCallResult.Error("MISSING_MODEL_NAME", "缺少 modelName")
                val useLocal = (arguments["useLocal"] as? Boolean) ?: false
                switchModel(name, useLocal)
            }
            "load_local_model" -> {
                val id = (arguments["modelId"] as? Number)?.toLong()
                    ?: return McpCallResult.Error("MISSING_MODEL_ID", "缺少 modelId")
                loadLocalModel(id)
            }
            "unload_local_model" -> unloadAll()

            "ai_set_custom_endpoint" -> setCustomEndpoint(arguments)
            "ai_fetch_remote_models" -> fetchRemoteModels(arguments)
            "ai_download_model_manual" -> downloadModelManual(arguments)
            "ai_verify_model_integrity" -> verifyModelIntegrity(arguments)
            "ai_context_bind" -> contextBind(arguments)

            else -> McpCallResult.Error("UNKNOWN_TOOL", "未知工具：$toolName")
        }
    }

    // ────────────────────────────────────────────────────────────
    // 1. list_models
    // ────────────────────────────────────────────────────────────
    private suspend fun listModels(workspaceId: String?): McpCallResult {
        val effective = aiConfigRepository.findEffective(workspaceId)?.first
        val cloudModels: List<String> = effective?.let {
            if (it.manualModelMode) {
                it.manualModels.split(",").map(String::trim).filter(String::isNotEmpty)
            } else {
                listOfNotNull(it.defaultModel.takeIf(String::isNotEmpty))
            }
        } ?: emptyList()

        val localCount = runCatching { localModelRepository.observeAll().first().size }
            .getOrDefault(0)

        return McpCallResult.Success(
            result = buildString {
                appendLine("云端模型：")
                cloudModels.forEach { appendLine("  - $it") }
                appendLine("本地模型：$localCount 个（参见本地模型管理页面）")
                effective?.let { appendLine("当前生效配置：scope=${it.scope} baseUrl=${it.baseUrl}") }
            }.trim()
        )
    }

    // ────────────────────────────────────────────────────────────
    // 2. switch_model
    // ────────────────────────────────────────────────────────────
    private fun switchModel(modelName: String, useLocal: Boolean): McpCallResult {
        return McpCallResult.Success(
            result = "已请求切换到模型：$modelName（useLocal=$useLocal），下次会话生效",
            metadata = mapOf("modelName" to modelName, "useLocal" to useLocal.toString())
        )
    }

    // ────────────────────────────────────────────────────────────
    // 3. load_local_model
    // ────────────────────────────────────────────────────────────
    private suspend fun loadLocalModel(modelId: Long): McpCallResult {
        val model = localModelRepository.findById(modelId)
            ?: return McpCallResult.Error("MODEL_NOT_FOUND", "未找到模型ID=$modelId")

        val loaded = localGgufManager.load(model.filePath)
        if (!loaded) {
            return McpCallResult.Error(
                "LOAD_FAILED",
                "本地模型加载失败：${model.name}",
                cause = "llama-server 健康检查超时或二进制缺失"
            )
        }
        localModelRepository.setLoaded(modelId, true)
        return McpCallResult.Success(
            result = "已加载本地模型：${model.name}",
            metadata = mapOf(
                "modelId" to modelId.toString(),
                "filePath" to model.filePath,
                "port" to localGgufManager.currentPort().toString()
            )
        )
    }

    // ────────────────────────────────────────────────────────────
    // 4. unload_local_model
    // ────────────────────────────────────────────────────────────
    private suspend fun unloadAll(): McpCallResult {
        localGgufManager.unload()
        localModelRepository.unloadAll()
        return McpCallResult.Success("已卸载所有本地模型")
    }

    // ────────────────────────────────────────────────────────────
    // 5. ai_set_custom_endpoint
    // ────────────────────────────────────────────────────────────
    private suspend fun setCustomEndpoint(arguments: Map<String, Any?>): McpCallResult {
        val baseUrl = arguments["baseUrl"] as? String
            ?: return McpCallResult.Error("MISSING_BASE_URL", "缺少 baseUrl")
        val apiKey = arguments["apiKey"] as? String
            ?: return McpCallResult.Error("MISSING_API_KEY", "缺少 apiKey")
        val defaultModel = arguments["defaultModel"] as? String
            ?: return McpCallResult.Error("MISSING_DEFAULT_MODEL", "缺少 defaultModel")
        val timeoutMs = (arguments["timeoutMs"] as? Number)?.toLong()
            ?: AppConstants.AI_CLOUD_TIMEOUT_MS
        val contextLength = (arguments["contextLength"] as? Number)?.toInt()
            ?: AppConstants.DEFAULT_CONTEXT_LENGTH
        val temperature = (arguments["temperature"] as? Number)?.toFloat()
            ?: AppConstants.DEFAULT_TEMPERATURE
        val scope = arguments["scope"] as? String
            ?: return McpCallResult.Error("MISSING_SCOPE", "缺少 scope（global / workspace）")
        val workspaceId = arguments["workspaceId"] as? String

        if (scope == AppConstants.AiConfigScope.WORKSPACE && workspaceId.isNullOrBlank()) {
            return McpCallResult.Error(
                "MISSING_WORKSPACE_ID",
                "scope=workspace 时必须提供 workspaceId"
            )
        }

        val entity = when (scope) {
            AppConstants.AiConfigScope.WORKSPACE -> {
                aiConfigRepository.upsertWorkspace(workspaceId!!, baseUrl, apiKey, defaultModel)
            }
            AppConstants.AiConfigScope.GLOBAL -> {
                aiConfigRepository.upsertGlobal(baseUrl, apiKey, defaultModel)
            }
            else -> return McpCallResult.Error(
                "INVALID_SCOPE",
                "scope 必须是 'global' 或 'workspace'，当前值：$scope"
            )
        }

        val updated = entity.copy(
            timeoutMs = timeoutMs,
            contextLength = contextLength,
            temperature = temperature,
            updatedAt = System.currentTimeMillis()
        )
        aiConfigRepository.upsert(updated)

        return McpCallResult.Success(
            result = "AI 配置已保存（scope=$scope baseUrl=$baseUrl defaultModel=$defaultModel）",
            metadata = mapOf(
                "configId" to updated.id.toString(),
                "scope" to scope,
                "baseUrl" to baseUrl,
                "defaultModel" to defaultModel,
                "timeoutMs" to timeoutMs.toString(),
                "contextLength" to contextLength.toString(),
                "temperature" to temperature.toString()
            )
        )
    }

    // ────────────────────────────────────────────────────────────
    // 6. ai_fetch_remote_models
    // ────────────────────────────────────────────────────────────
    private suspend fun fetchRemoteModels(arguments: Map<String, Any?>): McpCallResult {
        val baseUrl = arguments["baseUrl"] as? String
            ?: return McpCallResult.Error("MISSING_BASE_URL", "缺少 baseUrl")
        val apiKey = arguments["apiKey"] as? String
            ?: return McpCallResult.Error("MISSING_API_KEY", "缺少 apiKey")

        return try {
            val models: List<ModelInfo> = openAiStreamClient.fetchModels(baseUrl, apiKey)
            if (models.isEmpty()) {
                return McpCallResult.Success(
                    result = "远端模型列表为空",
                    metadata = mapOf("count" to "0", "baseUrl" to baseUrl)
                )
            }
            McpCallResult.Success(
                result = buildString {
                    appendLine("远端模型列表（共 ${models.size} 个）：")
                    models.forEach { appendLine("  - ${it.id}${it.owned_by?.let { by -> " ($by)" } ?: ""}") }
                }.trim(),
                metadata = mapOf(
                    "count" to models.size.toString(),
                    "baseUrl" to baseUrl,
                    "models" to models.joinToString(", ") { it.id }
                )
            )
        } catch (e: Exception) {
            McpCallResult.Error(
                "FETCH_MODELS_FAILED",
                "拉取远端模型列表失败：${e.message}",
                cause = e.stackTraceToString()
            )
        }
    }

    // ────────────────────────────────────────────────────────────
    // 7. ai_download_model_manual
    // ────────────────────────────────────────────────────────────
    private suspend fun downloadModelManual(arguments: Map<String, Any?>): McpCallResult {
        val modelUrl = arguments["modelUrl"] as? String
            ?: return McpCallResult.Error("MISSING_MODEL_URL", "缺少 modelUrl")
        val modelName = arguments["modelName"] as? String
            ?: return McpCallResult.Error("MISSING_MODEL_NAME", "缺少 modelName")
        val saveDir = arguments["saveDir"] as? String
            ?: return McpCallResult.Error("MISSING_SAVE_DIR", "缺少 saveDir")

        return try {
            val dir = File(saveDir)
            val fileName = modelUrl.substringAfterLast('/').ifBlank { "$modelName.gguf" }
            val targetFile = File(dir, fileName)

            withContext(Dispatchers.IO) {
                DownloadUtil.downloadWithResume(
                    url = modelUrl,
                    target = targetFile
                )
            }

            val sha256 = HashUtil.sha256(targetFile)
            val sizeBytes = targetFile.length()

            val entity = LocalModelEntity(
                name = modelName,
                filePath = targetFile.absolutePath,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                sourceUrl = modelUrl
            )
            val modelId = localModelRepository.upsert(entity)

            McpCallResult.Success(
                result = "模型下载完成并已入库：$modelName（ID=$modelId, 大小=${sizeBytes} 字节）",
                metadata = mapOf(
                    "modelId" to modelId.toString(),
                    "modelName" to modelName,
                    "filePath" to targetFile.absolutePath,
                    "sizeBytes" to sizeBytes.toString(),
                    "sha256" to sha256
                )
            )
        } catch (e: Exception) {
            McpCallResult.Error(
                "DOWNLOAD_FAILED",
                "模型下载失败：${e.message}",
                cause = e.stackTraceToString()
            )
        }
    }

    // ────────────────────────────────────────────────────────────
    // 8. ai_verify_model_integrity
    // ────────────────────────────────────────────────────────────
    private suspend fun verifyModelIntegrity(arguments: Map<String, Any?>): McpCallResult {
        val modelId = (arguments["modelId"] as? Number)?.toLong()
            ?: return McpCallResult.Error("MISSING_MODEL_ID", "缺少 modelId")

        val model = localModelRepository.findById(modelId)
            ?: return McpCallResult.Error("MODEL_NOT_FOUND", "未找到模型ID=$modelId")

        val file = File(model.filePath)
        if (!file.exists()) {
            return McpCallResult.Error(
                "FILE_NOT_FOUND",
                "模型文件不存在：${model.filePath}"
            )
        }

        val actualSha256 = withContext(Dispatchers.IO) { HashUtil.sha256(file) }
        val storedSha256 = model.sha256

        val ok = if (storedSha256.isNullOrBlank()) {
            true
        } else {
            actualSha256.equals(storedSha256, ignoreCase = true)
        }

        return if (ok) {
            McpCallResult.Success(
                result = "模型完整性校验通过：${model.name}（SHA256=$actualSha256）",
                metadata = mapOf(
                    "modelId" to modelId.toString(),
                    "status" to "OK",
                    "sha256" to actualSha256
                )
            )
        } else {
            McpCallResult.Success(
                result = "模型文件已损坏：${model.name}（存储SHA256=$storedSha256, 实际SHA256=$actualSha256）",
                metadata = mapOf(
                    "modelId" to modelId.toString(),
                    "status" to "DAMAGED",
                    "storedSha256" to storedSha256,
                    "actualSha256" to actualSha256
                )
            )
        }
    }

    // ────────────────────────────────────────────────────────────
    // 9. ai_context_bind
    // ────────────────────────────────────────────────────────────
    private suspend fun contextBind(arguments: Map<String, Any?>): McpCallResult {
        val workspaceId = arguments["workspaceId"] as? String
            ?: return McpCallResult.Error("MISSING_WORKSPACE_ID", "缺少 workspaceId")

        val effective = aiConfigRepository.findEffective(workspaceId)
            ?: return McpCallResult.Error(
                "NO_CONFIG",
                "workspace=$workspaceId 未配置任何 AI 设置，且无全局回退配置"
            )

        val (config, isWorkspace) = effective
        val scopeLabel = if (isWorkspace) "workspace" else "global"

        return McpCallResult.Success(
            result = buildString {
                appendLine("生效 AI 配置（scope=$scopeLabel）：")
                appendLine("  baseUrl=${config.baseUrl}")
                appendLine("  apiKey=${config.apiKey.take(4)}****")
                appendLine("  defaultModel=${config.defaultModel}")
                appendLine("  timeoutMs=${config.timeoutMs}")
                appendLine("  contextLength=${config.contextLength}")
                appendLine("  temperature=${config.temperature}")
                if (isWorkspace) appendLine("  workspaceId=$workspaceId")
            }.trim(),
            metadata = mapOf(
                "scope" to scopeLabel,
                "configId" to config.id.toString(),
                "baseUrl" to config.baseUrl,
                "defaultModel" to config.defaultModel,
                "timeoutMs" to config.timeoutMs.toString(),
                "contextLength" to config.contextLength.toString(),
                "temperature" to config.temperature.toString(),
                "workspaceId" to workspaceId
            )
        )
    }
}