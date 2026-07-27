package com.her.aimodifier.ai.provider

import kotlinx.serialization.Serializable

@Serializable
enum class ApiProviderType {
    OPENAI,
    OPENAI_GENERIC,
    OPENAI_LOCAL,
    ANTHROPIC,
    ANTHROPIC_GENERIC,
    GOOGLE,
    GEMINI_GENERIC,
    ZHIPU,
    BAICHUAN,
    MOONSHOT,
    MIMO,
    DEEPSEEK,
    MISTRAL,
    SILICONFLOW,
    IFLOW,
    OPENROUTER,
    FOUR_ROUTER,
    NOUS_PORTAL,
    INFINIAI,
    ALIPAY_BAILING,
    DOUBAO,
    NVIDIA,
    LMSTUDIO,
    OLLAMA,
    PPINFRA,
    NOVITA,
    LLAMA_CPP,
    MNN,
    OPENAI_RESPONSES,
    GROQ,
    TOGETHER,
    FIREWORKS,
    PERPLEXITY,
    COHERE,
    GROK,
    QWEN,
    OTHER;

    companion object {
        fun fromProviderTypeId(providerTypeId: String): ApiProviderType? {
            val normalized = providerTypeId.trim()
            if (normalized.isEmpty()) return null
            return values().firstOrNull {
                it.name.equals(normalized, ignoreCase = true)
            }
        }
    }
}

@Serializable
data class ModelOption(
    val id: String,
    val name: String
)

object ModelConfigDefaults {
    const val DEFAULT_CONTEXT_LENGTH = 64.0f
    const val DEFAULT_MAX_CONTEXT_LENGTH = 200.0f
    const val DEFAULT_ENABLE_MAX_CONTEXT_MODE = false
    const val DEFAULT_SUMMARY_TOKEN_THRESHOLD = 0.70f
    const val DEFAULT_ENABLE_SUMMARY = true
    const val DEFAULT_ENABLE_SUMMARY_BY_MESSAGE_COUNT = true
    const val DEFAULT_SUMMARY_MESSAGE_COUNT_THRESHOLD = 16
}

@Serializable
data class ModelConfigData(
    val id: String,
    val name: String,
    val apiKey: String = "",
    val apiEndpoint: String = "",
    val modelName: String = "",
    val apiProviderType: ApiProviderType = ApiProviderType.DEEPSEEK,
    val apiProviderTypeId: String = apiProviderType.name,
    val useMultipleApiKeys: Boolean = false,
    val apiKeyPool: List<ApiKeyInfo> = emptyList(),
    val currentKeyIndex: Int = 0,
    val keyRotationMode: String = "ROUND_ROBIN",
    val hasCustomParameters: Boolean = false,
    val maxTokensEnabled: Boolean = false,
    val temperatureEnabled: Boolean = false,
    val topPEnabled: Boolean = false,
    val topKEnabled: Boolean = false,
    val presencePenaltyEnabled: Boolean = false,
    val frequencyPenaltyEnabled: Boolean = false,
    val repetitionPenaltyEnabled: Boolean = false,
    val maxTokens: Int = 4096,
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    val topK: Int = 0,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,
    val repetitionPenalty: Float = 1.0f,
    val customParameters: String = "[]",
    val customHeaders: String = "{}",
    val contextLength: Float = ModelConfigDefaults.DEFAULT_CONTEXT_LENGTH,
    val maxContextLength: Float = ModelConfigDefaults.DEFAULT_MAX_CONTEXT_LENGTH,
    val enableMaxContextMode: Boolean = ModelConfigDefaults.DEFAULT_ENABLE_MAX_CONTEXT_MODE,
    val summaryTokenThreshold: Float = ModelConfigDefaults.DEFAULT_SUMMARY_TOKEN_THRESHOLD,
    val enableSummary: Boolean = ModelConfigDefaults.DEFAULT_ENABLE_SUMMARY,
    val enableSummaryByMessageCount: Boolean = ModelConfigDefaults.DEFAULT_ENABLE_SUMMARY_BY_MESSAGE_COUNT,
    val summaryMessageCountThreshold: Int = ModelConfigDefaults.DEFAULT_SUMMARY_MESSAGE_COUNT_THRESHOLD,
    val summaryCustomRules: String = "",
    val llamaThreadCount: Int = 4,
    val llamaContextSize: Int = 2048,
    val llamaBatchSize: Int = 512,
    val llamaUBatchSize: Int = 512,
    val llamaGpuLayers: Int = 0,
    val llamaUseMmap: Boolean = false,
    val llamaFlashAttention: Boolean = false,
    val llamaKvUnified: Boolean = true,
    val llamaOffloadKqv: Boolean = false,
    val enableDirectImageProcessing: Boolean = false,
    val enableDirectAudioProcessing: Boolean = false,
    val enableDirectVideoProcessing: Boolean = false,
    val enableGoogleSearch: Boolean = false,
    val enableClaude1hPromptCache: Boolean = false,
    val enableToolCall: Boolean = false,
    val systemPrompt: String = "",
    val requestLimitPerMinute: Int = 0,
    val maxConcurrentRequests: Int = 0,
    val enableThinking: Boolean = false,
    val thinkingQualityLevel: Int = 2,
    val forwardType: Int = 0,
    val timeoutMs: Long = 60_000L,
    val configId: String = id
)

@Serializable
enum class ApiKeyAvailabilityStatus {
    UNTESTED,
    AVAILABLE,
    UNAVAILABLE
}

@Serializable
data class ApiKeyInfo(
    val key: String = "",
    val name: String = "",
    val isEnabled: Boolean = true,
    val availabilityStatus: ApiKeyAvailabilityStatus = ApiKeyAvailabilityStatus.UNTESTED
)

@Serializable
data class ModelConfigSummary(
    val id: String,
    val name: String,
    val modelName: String = "",
    val apiEndpoint: String = "",
    val apiProviderType: ApiProviderType = ApiProviderType.DEEPSEEK,
    val modelIndex: Int = 0
)

fun getModelByIndex(modelName: String, index: Int): String {
    if (modelName.isEmpty()) return ""
    val models = modelName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return if (index >= 0 && index < models.size) models[index] else models.getOrNull(0) ?: ""
}

fun getModelList(modelName: String): List<String> {
    if (modelName.isEmpty()) return emptyList()
    return modelName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

fun getValidModelIndex(modelName: String, requestedIndex: Int): Int {
    val modelList = getModelList(modelName)
    return if (requestedIndex >= 0 && requestedIndex < modelList.size) {
        requestedIndex
    } else {
        0
    }
}
