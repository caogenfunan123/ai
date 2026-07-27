package com.her.aimodifier.ai.provider

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mistral AI provider.
 * 兼容 OpenAI Chat Completions 接口，保留 Mistral 风格的工具调用 ID 生成逻辑。
 */
class MistralProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.MISTRAL,
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
    companion object {
        private const val TAG = "MistralProvider"
    }

    /**
     * 为 Mistral 风格的工具调用生成稳定的 ID。
     * ID 由工具名、参数与索引的哈希组成，使用 base36 编码并填充至 9 位。
     */
    fun generateMistralToolCallId(toolName: String, params: JSONObject, index: Int): String {
        val raw = "$toolName:${params.toString()}:$index"
        val hash = raw.hashCode()
        val positive = if (hash == Int.MIN_VALUE) 0 else kotlin.math.abs(hash)
        var base = positive.toString(36)
        base = base.filter { it.isLetterOrDigit() }.lowercase()
        if (base.isEmpty()) base = "0"
        val padded = base.padStart(9, '0')
        return if (padded.length > 9) padded.takeLast(9) else padded
    }

    /**
     * XML 反转义工具，用于将工具调用参数中的 XML 实体还原为原始字符。
     */
    fun unescapeXml(text: String): String {
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
