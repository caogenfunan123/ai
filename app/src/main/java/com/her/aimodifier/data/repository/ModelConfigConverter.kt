package com.her.aimodifier.data.repository

import com.her.aimodifier.ai.provider.ApiKeyAvailabilityStatus
import com.her.aimodifier.ai.provider.ApiKeyInfo
import com.her.aimodifier.ai.provider.ApiProviderType
import com.her.aimodifier.ai.provider.ModelConfigData
import com.her.aimodifier.data.database.entity.AiConfigEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * [AiConfigEntity] 与 [ModelConfigData] 之间的双向转换器。
 *
 * 字段一一对应：
 * - 字符串型字段直接复制；
 * - 多API Key 池通过 [apiKeyPoolJson] 字段序列化为 JSON 数组；
 * - contextLength 在 Entity 中以 int (K * 1000) 存储，在 ModelConfigData 中以 float (K) 表示。
 */
object ModelConfigConverter {

    fun toModelConfigData(entity: AiConfigEntity): ModelConfigData {
        return ModelConfigData(
            id = entity.configId.ifBlank { "default" },
            name = entity.configName,
            apiKey = entity.apiKey,
            apiEndpoint = entity.baseUrl,
            modelName = entity.defaultModel,
            apiProviderType = ApiProviderType.fromProviderTypeId(entity.providerType)
                ?: ApiProviderType.DEEPSEEK,
            apiProviderTypeId = entity.providerType,
            useMultipleApiKeys = entity.useMultipleApiKeys,
            apiKeyPool = parseApiKeyPool(entity.apiKeyPoolJson),
            currentKeyIndex = entity.currentKeyIndex,
            keyRotationMode = entity.keyRotationMode,
            hasCustomParameters = entity.hasCustomParameters,
            maxTokensEnabled = entity.maxTokensEnabled,
            temperatureEnabled = entity.temperatureEnabled,
            topPEnabled = entity.topPEnabled,
            topKEnabled = entity.topKEnabled,
            presencePenaltyEnabled = entity.presencePenaltyEnabled,
            frequencyPenaltyEnabled = entity.frequencyPenaltyEnabled,
            repetitionPenaltyEnabled = entity.repetitionPenaltyEnabled,
            maxTokens = entity.maxTokens,
            temperature = entity.temperature,
            topP = entity.topP,
            topK = entity.topK,
            presencePenalty = entity.presencePenalty,
            frequencyPenalty = entity.frequencyPenalty,
            repetitionPenalty = entity.repetitionPenalty,
            customParameters = entity.customParameters,
            customHeaders = entity.customHeaders,
            contextLength = entity.contextLengthK.coerceAtLeast(1f),
            maxContextLength = entity.maxContextLengthK.coerceAtLeast(1f),
            enableMaxContextMode = entity.enableMaxContextMode,
            summaryTokenThreshold = entity.summaryTokenThreshold,
            enableSummary = entity.enableSummary,
            enableSummaryByMessageCount = entity.enableSummaryByMessageCount,
            summaryMessageCountThreshold = entity.summaryMessageCountThreshold,
            summaryCustomRules = entity.summaryCustomRules,
            llamaThreadCount = entity.llamaThreadCount,
            llamaContextSize = entity.llamaContextSize,
            llamaBatchSize = entity.llamaBatchSize,
            llamaUBatchSize = entity.llamaUBatchSize,
            llamaGpuLayers = entity.llamaGpuLayers,
            llamaUseMmap = entity.llamaUseMmap,
            llamaFlashAttention = entity.llamaFlashAttention,
            llamaKvUnified = entity.llamaKvUnified,
            llamaOffloadKqv = entity.llamaOffloadKqv,
            enableDirectImageProcessing = entity.enableDirectImageProcessing,
            enableDirectAudioProcessing = entity.enableDirectAudioProcessing,
            enableDirectVideoProcessing = entity.enableDirectVideoProcessing,
            enableGoogleSearch = entity.enableGoogleSearch,
            enableClaude1hPromptCache = entity.enableClaude1hPromptCache,
            enableToolCall = entity.enableToolCall,
            systemPrompt = entity.systemPrompt,
            requestLimitPerMinute = entity.requestLimitPerMinute,
            maxConcurrentRequests = entity.maxConcurrentRequests,
            enableThinking = entity.enableThinking,
            thinkingQualityLevel = entity.thinkingQualityLevel,
            forwardType = entity.forwardType,
            timeoutMs = entity.timeoutMs,
            configId = entity.configId.ifBlank { "default" }
        )
    }

    fun toEntity(config: ModelConfigData, scope: String, workspaceId: String? = null): AiConfigEntity {
        return AiConfigEntity(
            scope = scope,
            workspaceId = workspaceId,
            configId = config.configId.ifBlank { config.id.ifBlank { "default" } },
            configName = config.name,
            providerType = config.apiProviderType.name,
            baseUrl = config.apiEndpoint,
            apiKey = config.apiKey,
            defaultModel = config.modelName,
            manualModels = "",
            manualModelMode = false,
            cachedModelsJson = "[]",
            cachedModelsAt = 0L,
            timeoutMs = config.timeoutMs,
            contextLength = (config.contextLength * 1000).toInt().coerceAtLeast(1000),
            temperature = config.temperature,
            temperatureEnabled = config.temperatureEnabled,
            maxTokensEnabled = config.maxTokensEnabled,
            maxTokens = config.maxTokens,
            customHeaders = config.customHeaders,
            enableToolCall = config.enableToolCall,
            enableDirectImageProcessing = config.enableDirectImageProcessing,
            enableDirectAudioProcessing = config.enableDirectAudioProcessing,
            enableDirectVideoProcessing = config.enableDirectVideoProcessing,
            enableGoogleSearch = config.enableGoogleSearch,
            enableClaude1hPromptCache = config.enableClaude1hPromptCache,
            enableThinking = config.enableThinking,
            thinkingQualityLevel = config.thinkingQualityLevel,
            systemPrompt = config.systemPrompt,
            contextLengthK = config.contextLength,
            maxContextLengthK = config.maxContextLength,
            enableMaxContextMode = config.enableMaxContextMode,
            summaryTokenThreshold = config.summaryTokenThreshold,
            enableSummary = config.enableSummary,
            enableSummaryByMessageCount = config.enableSummaryByMessageCount,
            summaryMessageCountThreshold = config.summaryMessageCountThreshold,
            summaryCustomRules = config.summaryCustomRules,
            apiKeyPoolJson = serializeApiKeyPool(config.apiKeyPool),
            useMultipleApiKeys = config.useMultipleApiKeys,
            currentKeyIndex = config.currentKeyIndex,
            keyRotationMode = config.keyRotationMode,
            hasCustomParameters = config.hasCustomParameters,
            topPEnabled = config.topPEnabled,
            topKEnabled = config.topKEnabled,
            presencePenaltyEnabled = config.presencePenaltyEnabled,
            frequencyPenaltyEnabled = config.frequencyPenaltyEnabled,
            repetitionPenaltyEnabled = config.repetitionPenaltyEnabled,
            topP = config.topP,
            topK = config.topK,
            presencePenalty = config.presencePenalty,
            frequencyPenalty = config.frequencyPenalty,
            repetitionPenalty = config.repetitionPenalty,
            customParameters = config.customParameters,
            requestLimitPerMinute = config.requestLimitPerMinute,
            maxConcurrentRequests = config.maxConcurrentRequests,
            llamaThreadCount = config.llamaThreadCount,
            llamaContextSize = config.llamaContextSize,
            llamaBatchSize = config.llamaBatchSize,
            llamaUBatchSize = config.llamaUBatchSize,
            llamaGpuLayers = config.llamaGpuLayers,
            llamaUseMmap = config.llamaUseMmap,
            llamaFlashAttention = config.llamaFlashAttention,
            llamaKvUnified = config.llamaKvUnified,
            llamaOffloadKqv = config.llamaOffloadKqv,
            forwardType = config.forwardType
        )
    }

    /** 解析 apiKeyPoolJson 字段为 [ApiKeyInfo] 列表。 */
    private fun parseApiKeyPool(json: String): List<ApiKeyInfo> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                ApiKeyInfo(
                    key = obj.optString("key", ""),
                    name = obj.optString("name", ""),
                    isEnabled = obj.optBoolean("isEnabled", true),
                    availabilityStatus = runCatching {
                        ApiKeyAvailabilityStatus.valueOf(
                            obj.optString("availabilityStatus", "UNTESTED")
                        )
                    }.getOrDefault(ApiKeyAvailabilityStatus.UNTESTED)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 将 [ApiKeyInfo] 列表序列化为 JSON 字符串。 */
    private fun serializeApiKeyPool(pool: List<ApiKeyInfo>): String {
        if (pool.isEmpty()) return "[]"
        val array = JSONArray()
        pool.forEach { info ->
            array.put(JSONObject().apply {
                put("key", info.key)
                put("name", info.name)
                put("isEnabled", info.isEnabled)
                put("availabilityStatus", info.availabilityStatus.name)
            })
        }
        return array.toString()
    }
}
