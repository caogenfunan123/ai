package com.her.aimodifier.ai.provider

import okhttp3.OkHttpClient

/**
 * Ollama provider.
 * Uses the OpenAI-compatible API surface exposed by Ollama (e.g. /v1/chat/completions).
 * Local Ollama does not require an API Key.
 */
class OllamaProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.OLLAMA,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false
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
    /**
     * Ollama 默认不要求 API Key，因此不附加 Authorization 头。
     * 但仍保留自定义请求头的能力。
     */
    override fun applyAuthenticationHeaders(
        builder: okhttp3.Request.Builder,
        currentApiKey: String
    ) {
        // Ollama 本地服务通常不需要 API Key，跳过 Authorization 头。
        // 如果用户配置了自定义请求头，仍会在外层被应用。
    }
}
