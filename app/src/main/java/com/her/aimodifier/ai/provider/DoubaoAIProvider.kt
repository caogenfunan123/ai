package com.her.aimodifier.ai.provider

import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * 针对豆包（Doubao）模型的特定 API Provider。
 * 继承自 OpenAIProvider 以复用兼容逻辑，但显式注入 thinking 参数并使用 /v3/chat/completions 端点。
 */
class DoubaoAIProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.DOUBAO,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false,
    private val enableThinking: Boolean = false
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
        private const val TAG = "DoubaoAIProvider"
    }

    /**
     * 豆包思考模式显式传参，避免依赖服务端默认值。
     */
    override fun customizeRequestBody(jsonObject: JSONObject) {
        super.customizeRequestBody(jsonObject)

        val thinkingType = if (enableThinking) "enabled" else "disabled"
        val thinkingObject = JSONObject().apply {
            put("type", thinkingType)
        }
        jsonObject.put("thinking", thinkingObject)

        Log.d(TAG, "已为豆包模型设置思考模式: $thinkingType")
    }

    /**
     * 豆包使用 /v3/chat/completions 端点。
     */
    override fun buildChatUrl(): String {
        val baseUrl = ModelListFetcher.getModelsListUrl(apiEndpoint, providerType)
            .removeSuffix("/models")
            .removeSuffix("/v1")
            .removeSuffix("/v3")
        return "$baseUrl/v3/chat/completions"
    }
}
