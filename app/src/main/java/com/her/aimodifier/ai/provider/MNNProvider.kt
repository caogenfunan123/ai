package com.her.aimodifier.ai.provider

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * MNN 本地推理 Provider。
 *
 * 核心特性：
 * - 使用阿里巴巴 MNN 引擎进行本地推理
 * - 无需 API Key 与网络连接
 * - 支持多种硬件后端（CPU/OpenCL/Vulkan 等）
 *
 * 简化实现说明：
 * 当前项目未集成 MNN 原生库，此 Provider 保留了核心接口结构
 * 与模型目录管理逻辑，实际推理需在集成 MNNLlmSession 后启用。
 */
class MNNProvider(
    apiEndpoint: String = "",
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.OPENAI_LOCAL,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false,
    private val forwardType: Int = 0,
    private val threadCount: Int = 4
) : OpenAIProvider(
    apiEndpoint = apiEndpoint,
    apiKeyProvider = apiKeyProvider,
    modelName = modelName,
    client = client,
    customHeaders = customHeaders,
    providerType = providerType,
    supportsVision = supportsVision,
    supportsAudio = supportsAudio,
    supportsVideo = supportsVideo,
    enableToolCall = enableToolCall
) {
    companion object {
        private const val TAG = "MNNProvider"

        /**
         * 根据模型名称获取 MNN 模型目录路径。
         */
        fun getModelDir(context: Context, modelName: String): String {
            val modelsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AI魔改器/models/mnn"
            )
            return File(modelsDir, modelName).absolutePath
        }

        /**
         * 将 forwardType 映射到后端类型字符串。
         */
        fun mapForwardTypeToBackend(forwardType: Int): String {
            return when (forwardType) {
                0 -> "cpu"
                3 -> "opencl"
                4 -> "auto"
                6 -> "opengl"
                7 -> "vulkan"
                else -> "cpu"
            }
        }
    }

    /**
     * 重写 sendMessage 以处理 MNN 本地推理。
     * 简化实现：检测模型目录与配置，若原生库不可用则返回错误信息。
     */
    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<ChatMessageTurn>,
        temperature: Float,
        maxTokens: Int?,
        stream: Boolean,
        systemPrompt: String?,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean
    ): Flow<String> = flow {
        // 检查模型目录是否存在
        val modelDir = getModelDir(context, modelName)
        val modelDirFile = File(modelDir)
        if (!modelDirFile.exists() || !modelDirFile.isDirectory) {
            emit("MNN 模型目录不存在: $modelDir")
            return@flow
        }

        // 检查配置文件是否存在
        val configFile = File(modelDir, "llm_config.json")
        if (!configFile.exists()) {
            emit("MNN 配置文件不存在: ${configFile.absolutePath}")
            return@flow
        }

        // 简化实现：原生库未集成时返回提示
        // 实际集成 MNNLlmSession 后，此处应：
        // 1. 创建/复用 MNNLlmSession（指定后端、线程数、精度等）
        // 2. 应用采样参数（temperature, topP, topK 等）
        // 3. 构建 conversation history
        // 4. 调用 generateStream 进行流式生成
        val backendType = mapForwardTypeToBackend(forwardType)
        Log.w(TAG, "MNN 原生库未集成，无法执行本地推理（后端: $backendType, 线程: $threadCount）")
        emit("MNN 本地推理引擎未集成。模型目录位于: $modelDir，后端: $backendType，请集成原生库后使用。")
    }.flowOn(Dispatchers.IO)

    /**
     * 获取本地 MNN 模型列表。
     */
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return withContext(Dispatchers.IO) {
            try {
                val modelsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AI魔改器/models/mnn"
                )
                if (!modelsDir.exists() || !modelsDir.isDirectory) {
                    return@withContext Result.success(emptyList())
                }

                // MNN 模型是目录，且应包含 llm_config.json
                val modelDirs = modelsDir.listFiles { file ->
                    file.isDirectory && File(file, "llm_config.json").exists()
                } ?: emptyArray()

                val options = modelDirs.map { dir ->
                    ModelOption(id = dir.name, name = dir.name)
                }.sortedBy { it.id }

                Result.success(options)
            } catch (e: Exception) {
                Log.e(TAG, "获取 MNN 本地模型列表失败", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 测试连接（检查模型目录与关键文件）。
     */
    override suspend fun testConnection(context: Context): Result<String> {
        return withContext(Dispatchers.IO) {
            val modelDir = getModelDir(context, modelName)
            val modelDirFile = File(modelDir)

            if (!modelDirFile.exists() || !modelDirFile.isDirectory) {
                return@withContext Result.failure(
                    IOException("模型目录不存在: $modelDir")
                )
            }

            val configFile = File(modelDir, "llm_config.json")
            if (!configFile.exists()) {
                return@withContext Result.failure(
                    IOException("配置文件不存在: ${configFile.absolutePath}")
                )
            }

            val modelFile = File(modelDir, "llm.mnn")
            val fileStatus = buildString {
                appendLine("文件状态:")
                appendLine("- llm.mnn: ${if (modelFile.exists()) "✓" else "✗"}")
                appendLine("- llm_config.json: ${if (configFile.exists()) "✓" else "✗"}")
            }

            val totalSize = modelDirFile.listFiles()?.sumOf { it.length() } ?: 0L
            Result.success("MNN 模型验证成功: $modelName ($modelDir)\n${fileStatus}总大小: ${formatFileSize(totalSize)}")
        }
    }

    private fun formatFileSize(sizeBytes: Long): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> String.format("%.2f KB", sizeBytes / 1024.0)
            sizeBytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
