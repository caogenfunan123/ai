package com.her.aimodifier.ai.provider

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient

/**
 * AI服务工厂，根据配置创建对应的AIService实例
 * 支持多API提供商、多API Key轮询、限流和并发控制
 */
object AIServiceFactory {

    private const val TAG = "AIServiceFactory"

    /**
     * 创建AI服务实例
     * @param config 模型配置数据
     * @param context Android Context
     * @return AIService实例（可能被RateLimitedAIService包装）
     */
    fun createService(
        config: ModelConfigData,
        context: Context
    ): AIService {
        val httpClient = SharedHttpClient.instance
        val customHeaders = parseCustomHeaders(config.customHeaders)
        val providerType = config.apiProviderType

        // 根据配置选择API Key Provider
        val apiKeyProvider = if (config.useMultipleApiKeys && config.apiKeyPool.size > 1) {
            MultiApiKeyProvider(
                apiKeyPool = config.apiKeyPool,
                configId = config.configId,
                fallbackApiKey = config.apiKey
            )
        } else {
            SingleApiKeyProvider(config.apiKey)
        }

        val supportsVision = config.enableDirectImageProcessing
        val supportsAudio = config.enableDirectAudioProcessing
        val supportsVideo = config.enableDirectVideoProcessing
        val enableToolCall = config.enableToolCall

        // 创建基础Provider
        val baseService = createBaseService(
            config = config,
            httpClient = httpClient,
            customHeaders = customHeaders,
            providerType = providerType,
            apiKeyProvider = apiKeyProvider,
            supportsVision = supportsVision,
            supportsAudio = supportsAudio,
            supportsVideo = supportsVideo,
            enableToolCall = enableToolCall
        )

        // 如果配置了限流或并发控制，用RateLimitedAIService包装
        val requestLimitPerMinute = config.requestLimitPerMinute
        val maxConcurrentRequests = config.maxConcurrentRequests

        return if (requestLimitPerMinute > 0 || maxConcurrentRequests > 0) {
            RateLimitedAIService(
                delegate = baseService,
                rateLimiter = if (requestLimitPerMinute > 0) {
                    RateLimiterRegistry.getOrCreate(config.configId, requestLimitPerMinute)
                } else null,
                concurrencySemaphore = if (maxConcurrentRequests > 0) {
                    RequestConcurrencyRegistry.getOrCreate(config.configId, maxConcurrentRequests)
                } else null
            )
        } else {
            baseService
        }
    }

    /**
     * 创建基础Provider实例
     */
    private fun createBaseService(
        config: ModelConfigData,
        httpClient: OkHttpClient,
        customHeaders: Map<String, String>,
        providerType: ApiProviderType,
        apiKeyProvider: ApiKeyProvider,
        supportsVision: Boolean,
        supportsAudio: Boolean,
        supportsVideo: Boolean,
        enableToolCall: Boolean
    ): AIService {
        val commonParams = ProviderParams(
            apiEndpoint = config.apiEndpoint,
            apiKeyProvider = apiKeyProvider,
            modelName = config.modelName,
            client = httpClient,
            customHeaders = customHeaders,
            providerType = providerType,
            supportsVision = supportsVision,
            supportsAudio = supportsAudio,
            supportsVideo = supportsVideo,
            enableToolCall = enableToolCall
        )

        return when (providerType) {
            // Anthropic / Claude
            ApiProviderType.ANTHROPIC,
            ApiProviderType.ANTHROPIC_GENERIC ->
                ClaudeProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall,
                    enableClaude1hPromptCache = config.enableClaude1hPromptCache,
                    enableThinking = config.enableThinking
                )

            // Google Gemini
            ApiProviderType.GOOGLE,
            ApiProviderType.GEMINI_GENERIC ->
                GeminiProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall,
                    enableGoogleSearch = config.enableGoogleSearch,
                    enableThinking = config.enableThinking
                )

            // Kimi / Moonshot
            ApiProviderType.MOONSHOT ->
                KimiProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall,
                    enableThinking = config.enableThinking
                )

            // Deepseek
            ApiProviderType.DEEPSEEK ->
                DeepseekProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )

            // Mistral
            ApiProviderType.MISTRAL ->
                MistralProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )

            // Xiaomi MiMo
            ApiProviderType.MIMO ->
                MimoProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )

            // Doubao
            ApiProviderType.DOUBAO ->
                DoubaoAIProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall,
                    enableThinking = config.enableThinking
                )

            // NVIDIA NIM
            ApiProviderType.NVIDIA ->
                NvidiaAIProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall,
                    enableThinking = config.enableThinking,
                    thinkingQualityLevel = config.thinkingQualityLevel
                )

            // Qwen / SiliconFlow
            ApiProviderType.SILICONFLOW,
            ApiProviderType.QWEN ->
                QwenAIProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    qwenProviderType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )

            // OpenRouter
            ApiProviderType.OPENROUTER ->
                OpenRouterProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )

            // FourRouter (继承OpenRouter)
            ApiProviderType.FOUR_ROUTER ->
                FourRouterProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )

            // Nous Portal (继承OpenRouter)
            ApiProviderType.NOUS_PORTAL ->
                NousPortalProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )

            // Ollama (本地)
            ApiProviderType.OLLAMA ->
                OllamaProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )

            // llama.cpp (本地)
            ApiProviderType.LLAMA_CPP ->
                LlamaProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall,
                    threadCount = config.llamaThreadCount,
                    contextSize = config.llamaContextSize,
                    batchSize = config.llamaBatchSize,
                    uBatchSize = config.llamaUBatchSize,
                    gpuLayers = config.llamaGpuLayers,
                    useMmap = config.llamaUseMmap,
                    flashAttention = config.llamaFlashAttention,
                    kvUnified = config.llamaKvUnified,
                    offloadKqv = config.llamaOffloadKqv
                )

            // MNN (本地)
            ApiProviderType.MNN,
            ApiProviderType.OPENAI_LOCAL ->
                MNNProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall,
                    forwardType = config.forwardType,
                    threadCount = config.llamaThreadCount
                )

            // OpenAI Responses API
            ApiProviderType.OPENAI_RESPONSES ->
                OpenAIResponsesProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall,
                    enableThinking = config.enableThinking,
                    thinkingQualityLevel = config.thinkingQualityLevel
                )

            // 标准OpenAI兼容
            ApiProviderType.OPENAI,
            ApiProviderType.OPENAI_GENERIC,
            ApiProviderType.ZHIPU,
            ApiProviderType.BAICHUAN,
            ApiProviderType.LMSTUDIO,
            ApiProviderType.PPINFRA,
            ApiProviderType.NOVITA,
            ApiProviderType.IFLOW,
            ApiProviderType.INFINIAI,
            ApiProviderType.ALIPAY_BAILING,
            ApiProviderType.GROQ,
            ApiProviderType.TOGETHER,
            ApiProviderType.FIREWORKS,
            ApiProviderType.PERPLEXITY,
            ApiProviderType.COHERE,
            ApiProviderType.GROK,
            ApiProviderType.OTHER ->
                OpenAIProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )

            // 默认回退
            else ->
                OpenAIProvider(
                    apiEndpoint = commonParams.apiEndpoint,
                    apiKeyProvider = commonParams.apiKeyProvider,
                    modelName = commonParams.modelName,
                    client = commonParams.client,
                    customHeaders = commonParams.customHeaders,
                    providerType = commonParams.providerType,
                    supportsVision = commonParams.supportsVision,
                    supportsAudio = commonParams.supportsAudio,
                    supportsVideo = commonParams.supportsVideo,
                    enableToolCall = commonParams.enableToolCall
                )
        }
    }

    /**
     * 解析自定义请求头JSON
     */
    private fun parseCustomHeaders(customHeadersJson: String): Map<String, String> {
        return try {
            val headers = mutableMapOf<String, String>()
            if (customHeadersJson.isNotEmpty() && customHeadersJson != "{}") {
                val jsonObject = org.json.JSONObject(customHeadersJson)
                for (key in jsonObject.keys()) {
                    headers[key] = jsonObject.getString(key)
                }
            }
            headers
        } catch (e: Exception) {
            Log.e(TAG, "解析自定义请求头失败", e)
            emptyMap()
        }
    }

    /**
     * Provider构造参数封装
     */
    private data class ProviderParams(
        val apiEndpoint: String,
        val apiKeyProvider: ApiKeyProvider,
        val modelName: String,
        val client: OkHttpClient,
        val customHeaders: Map<String, String>,
        val providerType: ApiProviderType,
        val supportsVision: Boolean,
        val supportsAudio: Boolean,
        val supportsVideo: Boolean,
        val enableToolCall: Boolean
    )
}

// ==================== 基础Provider子类 ====================

/**
 * Deepseek Provider - 支持 reasoning_content 推理参数
 */
class DeepseekProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.DEEPSEEK,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false
) : OpenAIProvider(
    apiEndpoint, apiKeyProvider, modelName, client, customHeaders, providerType,
    supportsVision, supportsAudio, supportsVideo, enableToolCall
) {
    override fun customizeRequestBody(jsonObject: org.json.JSONObject) {
        super.customizeRequestBody(jsonObject)
    }
}

/**
 * Xiaomi MiMo Provider - 同时添加 Authorization 和 api-key 头
 */
class MimoProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.MIMO,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false
) : OpenAIProvider(
    apiEndpoint, apiKeyProvider, modelName, client, customHeaders, providerType,
    supportsVision, supportsAudio, supportsVideo, enableToolCall
) {
    override fun applyAuthenticationHeaders(
        builder: okhttp3.Request.Builder,
        currentApiKey: String
    ) {
        if (currentApiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $currentApiKey")
            builder.addHeader("api-key", currentApiKey)
        }
    }
}

/**
 * Qwen AI Provider - 通义千问适配
 */
class QwenAIProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    private val qwenProviderType: ApiProviderType = ApiProviderType.SILICONFLOW,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false
) : OpenAIProvider(
    apiEndpoint, apiKeyProvider, modelName, client, customHeaders, qwenProviderType,
    supportsVision, supportsAudio, supportsVideo, enableToolCall
)

/**
 * OpenRouter Provider - 添加 HTTP-Referer 和 X-Title 头
 */
open class OpenRouterProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.OPENROUTER,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false
) : OpenAIProvider(
    apiEndpoint, apiKeyProvider, modelName, client, customHeaders, providerType,
    supportsVision, supportsAudio, supportsVideo, enableToolCall
) {
    override fun applyAuthenticationHeaders(
        builder: okhttp3.Request.Builder,
        currentApiKey: String
    ) {
        if (currentApiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $currentApiKey")
        }
        builder.addHeader("HTTP-Referer", "com.her.aimodifier")
        builder.addHeader("X-Title", "AI魔改器")
    }
}
