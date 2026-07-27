package com.her.aimodifier.ai.provider

import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * NVIDIA API Catalog / NIM provider.
 *
 * 官方文档指出了两种推理控制方式：
 * 1) chat_template_kwargs.enable_thinking（Nemotron 等基于模板的模型）
 * 2) reasoning_effort（GPT-OSS 部署）
 *
 * 这里始终写入 chat_template_kwargs.enable_thinking 以显式切换思考模式，
 * 并在启用思考时为 GPT-OSS 模型添加默认的 reasoning_effort=medium。
 */
class NvidiaAIProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.NVIDIA,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false,
    private val enableThinking: Boolean = false,
    private val thinkingQualityLevel: Int = 2
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
        private const val TAG = "NvidiaAIProvider"
        private const val MIN_THINKING_QUALITY_LEVEL = 1
        private const val MAX_THINKING_QUALITY_LEVEL = 5
    }

    /**
     * 在请求体中注入 NVIDIA 特有的推理控制参数。
     */
    override fun customizeRequestBody(jsonObject: JSONObject) {
        super.customizeRequestBody(jsonObject)

        // 为基于模板的 NVIDIA 推理模型显式切换思考模式。
        val chatTemplateKwargs = jsonObject.optJSONObject("chat_template_kwargs") ?: JSONObject()
        chatTemplateKwargs.put("enable_thinking", enableThinking)
        jsonObject.put("chat_template_kwargs", chatTemplateKwargs)

        // GPT-OSS 模型在 NVIDIA 上使用 reasoning_effort 控制推理深度。
        val modelNameLower = modelName.lowercase()
        val isGptOss = modelNameLower.contains("gpt-oss")
        val gptOssEffort = if (enableThinking && isGptOss && !jsonObject.has("reasoning_effort")) {
            resolveGptOssReasoningEffort()
        } else {
            null
        }
        if (gptOssEffort != null) {
            jsonObject.put("reasoning_effort", gptOssEffort)
        }

        Log.d(
            TAG,
            "NVIDIA thinking params applied: enable_thinking=$enableThinking, gpt_oss_reasoning_effort=$gptOssEffort"
        )
    }

    private fun resolveGptOssReasoningEffort(): String {
        val efforts = listOf("low", "medium", "high", "max", "max")
        val qualityIndex = thinkingQualityLevel.coerceIn(
            MIN_THINKING_QUALITY_LEVEL,
            MAX_THINKING_QUALITY_LEVEL
        ) - 1
        return efforts[qualityIndex]
    }
}
