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
 * 本地 llama.cpp 推理 Provider。
 *
 * 核心特性：
 * - 使用本地 llama.cpp 进行推理（通过 JNI 调用）
 * - 无需 API Key 与网络连接
 * - 支持本地模型文件加载与流式生成
 *
 * 简化实现说明：
 * 当前项目未集成 llama.cpp 原生库，此 Provider 保留了核心接口结构
 * 与模型目录管理逻辑，实际推理需在集成 LlamaSession 后启用。
 */
class LlamaProvider(
    apiEndpoint: String = "",
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.LLAMA_CPP,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false,
    private val threadCount: Int = 4,
    private val contextSize: Int = 2048,
    private val batchSize: Int = 512,
    private val uBatchSize: Int = 512,
    private val gpuLayers: Int = 0,
    private val useMmap: Boolean = false,
    private val flashAttention: Boolean = false,
    private val kvUnified: Boolean = true,
    private val offloadKqv: Boolean = false
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
        private const val TAG = "LlamaProvider"

        /**
         * 获取本地 llama.cpp 模型目录。
         */
        fun getModelsDir(): File {
            return File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AI魔改器/models/llama"
            )
        }

        /**
         * 根据模型名称获取模型文件。
         */
        fun getModelFile(context: Context, modelName: String): File {
            return File(getModelsDir(), modelName)
        }
    }

    /**
     * 重写 sendMessage 以处理本地推理。
     * 简化实现：检测原生库可用性，若不可用则返回错误信息。
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
        // 检查模型文件是否存在
        val modelFile = getModelFile(context, modelName)
        if (!modelFile.exists()) {
            emit("本地模型文件不存在: ${modelFile.absolutePath}")
            return@flow
        }

        // 简化实现：原生库未集成时返回提示
        // 实际集成 LlamaSession 后，此处应：
        // 1. 创建/复用 LlamaSession
        // 2. 构建 prompt（应用 chat template）
        // 3. 设置采样参数（temperature, top_p, top_k 等）
        // 4. 调用 generateStream 进行流式生成
        Log.w(TAG, "llama.cpp 原生库未集成，无法执行本地推理")
        emit("llama.cpp 本地推理引擎未集成。模型文件位于: ${modelFile.absolutePath}，请集成原生库后使用。")
    }.flowOn(Dispatchers.IO)

    /**
     * 获取本地模型列表。
     */
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return withContext(Dispatchers.IO) {
            try {
                val modelsDir = getModelsDir()
                if (!modelsDir.exists() || !modelsDir.isDirectory) {
                    return@withContext Result.success(emptyList())
                }

                val modelFiles = modelsDir.listFiles { file ->
                    file.isFile && (file.extension.equals("gguf", ignoreCase = true) ||
                            file.extension.equals("bin", ignoreCase = true))
                } ?: emptyArray()

                val options = modelFiles.map { file ->
                    ModelOption(id = file.name, name = file.name)
                }.sortedBy { it.id }

                Result.success(options)
            } catch (e: Exception) {
                Log.e(TAG, "获取本地模型列表失败", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 测试连接（检查模型文件是否存在）。
     */
    override suspend fun testConnection(context: Context): Result<String> {
        return withContext(Dispatchers.IO) {
            val modelFile = getModelFile(context, modelName)
            if (!modelFile.exists()) {
                return@withContext Result.failure(
                    IOException("模型文件不存在: ${modelFile.absolutePath}")
                )
            }

            val sizeMB = modelFile.length() / (1024.0 * 1024.0)
            Result.success("模型文件存在: ${modelFile.name} (${String.format("%.2f", sizeMB)} MB)")
        }
    }
}
