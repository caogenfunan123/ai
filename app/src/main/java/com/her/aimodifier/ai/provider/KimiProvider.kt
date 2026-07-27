package com.her.aimodifier.ai.provider

import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Kimi K2.5 Provider (Moonshot API).
 * 在 OpenAI 兼容接口基础上显式注入 thinking 参数：
 *   thinking.type = "enabled" | "disabled"
 */
open class KimiProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.MOONSHOT,
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
        private const val TAG = "KimiProvider"
    }

    /**
     * 注入 Kimi 风格的 thinking 参数。
     * 按官方文档建议始终显式传入 enabled/disabled，避免依赖服务端默认值。
     */
    override fun customizeRequestBody(jsonObject: JSONObject) {
        super.customizeRequestBody(jsonObject)

        val thinkingType = if (enableThinking) "enabled" else "disabled"
        val thinkingObject = JSONObject().apply {
            put("type", thinkingType)
        }
        jsonObject.put("thinking", thinkingObject)

        Log.d(TAG, "已为 Kimi 模型设置思考模式: $thinkingType")
    }
}
