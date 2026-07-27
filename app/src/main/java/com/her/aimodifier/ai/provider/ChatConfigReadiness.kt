package com.her.aimodifier.ai.provider

import java.net.URI

enum class ChatConfigReadinessIssue {
    PROVIDER_MISSING,
    PROVIDER_UNAVAILABLE,
    ENDPOINT_INVALID,
    MODEL_MISSING,
    API_KEY_MISSING,
    API_KEY_INVALID
}

data class ChatConfigReadinessResult(val issue: ChatConfigReadinessIssue? = null) {
    val isReady: Boolean
        get() = issue == null
}

object ChatConfigReadiness {
    private val credentialOptionalProviders =
        setOf(
            ApiProviderType.OPENAI_GENERIC,
            ApiProviderType.ANTHROPIC_GENERIC,
            ApiProviderType.GEMINI_GENERIC,
            ApiProviderType.OTHER
        )

    fun evaluate(
        config: ModelConfigData,
        modelIndex: Int,
        registeredPluginProviderIds: Set<String> = emptySet()
    ): ChatConfigReadinessResult {
        val providerTypeId = config.apiProviderTypeId.trim()
        if (providerTypeId.isEmpty()) {
            return ChatConfigReadinessResult(ChatConfigReadinessIssue.PROVIDER_MISSING)
        }

        val normalizedPluginIds = registeredPluginProviderIds.mapTo(mutableSetOf()) {
            it.trim().lowercase()
        }
        if (providerTypeId.lowercase() in normalizedPluginIds) {
            return ChatConfigReadinessResult()
        }

        val providerType = ApiProviderType.fromProviderTypeId(providerTypeId)
            ?: return ChatConfigReadinessResult(ChatConfigReadinessIssue.PROVIDER_UNAVAILABLE)
        val validModelIndex = getValidModelIndex(config.modelName, modelIndex)
        if (getModelByIndex(config.modelName, validModelIndex).isBlank()) {
            return ChatConfigReadinessResult(ChatConfigReadinessIssue.MODEL_MISSING)
        }

        if (providerType == ApiProviderType.LLAMA_CPP) {
            return ChatConfigReadinessResult()
        }

        val completedEndpoint = EndpointCompleter.completeEndpoint(config.apiEndpoint, providerType)
        if (!isHttpEndpoint(completedEndpoint)) {
            return ChatConfigReadinessResult(ChatConfigReadinessIssue.ENDPOINT_INVALID)
        }

        val hasConfiguredKey =
            config.apiKey.isNotBlank() ||
                config.apiKeyPool.any { key -> key.isEnabled && key.key.isNotBlank() }
        val keyIsRequired =
            providerType !in credentialOptionalProviders &&
                requiresApiKey(providerType, config.apiEndpoint)
        val hasUsableKey = hasUsableKey(config)

        if (config.useMultipleApiKeys && !hasUsableKey) {
            val issue =
                if (hasConfiguredKey) {
                    ChatConfigReadinessIssue.API_KEY_INVALID
                } else {
                    ChatConfigReadinessIssue.API_KEY_MISSING
                }
            return ChatConfigReadinessResult(issue)
        }
        if (keyIsRequired && !hasConfiguredKey) {
            return ChatConfigReadinessResult(ChatConfigReadinessIssue.API_KEY_MISSING)
        }
        if ((keyIsRequired || hasConfiguredKey) && !hasUsableKey) {
            return ChatConfigReadinessResult(ChatConfigReadinessIssue.API_KEY_INVALID)
        }
        return ChatConfigReadinessResult()
    }

    private fun isHttpEndpoint(endpoint: String): Boolean {
        return try {
            val uri = URI(endpoint.trim())
            uri.host != null &&
                (uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 判断指定提供商类型是否需要 API 密钥。
     * 本地推理服务（Ollama、LMStudio、llama.cpp）通常无需密钥。
     */
    private fun requiresApiKey(providerType: ApiProviderType, endpoint: String): Boolean {
        return when (providerType) {
            ApiProviderType.OLLAMA,
            ApiProviderType.LMSTUDIO,
            ApiProviderType.LLAMA_CPP -> false
            else -> true
        }
    }

    /**
     * 判断配置中是否存在可用的 API 密钥。
     * 单个密钥非空，或密钥池中存在已启用且非空的密钥时返回 true。
     */
    private fun hasUsableKey(config: ModelConfigData): Boolean {
        if (config.apiKey.isNotBlank()) return true
        return config.apiKeyPool.any { it.isEnabled && it.key.isNotBlank() }
    }
}
