package com.her.aimodifier.ai.provider

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Anthropic Claude API 的实现。
 *
 * 核心特性：
 * - 使用 x-api-key + anthropic-version 头进行认证
 * - 使用 /v1/messages 端点
 * - system 消息作为顶层字段，messages 中不包含 system 角色
 * - content 使用数组格式（支持文本与图片块）
 * - thinking 参数支持（adaptive + summarized / enabled + budget_tokens）
 * - enableClaude1hPromptCache 时附加 cache_control.ttl = "1h"
 * - XML 工具调用格式
 */
class ClaudeProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.ANTHROPIC,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false,
    private val enableClaude1hPromptCache: Boolean = false,
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
        private const val TAG = "ClaudeProvider"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val PROMPT_CACHE_CONTROL_TYPE = "ephemeral"
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val EMPTY_MESSAGE_TEXT = "[Empty]"
    }

    /**
     * Thinking 格式模式：
     * - ADAPTIVE: thinking.type="adaptive" + display="summarized"（新模型）
     * - ENABLED:  thinking.type="enabled" + budget_tokens（旧模型）
     */
    private enum class ThinkingFormat { ADAPTIVE, ENABLED }

    @Volatile
    private var cachedThinkingFormat: ThinkingFormat? = null

    // ==================== 认证与 URL ====================

    /**
     * Claude 使用 x-api-key 头进行认证，并附加 anthropic-version 头。
     */
    override fun applyAuthenticationHeaders(
        builder: Request.Builder,
        currentApiKey: String
    ) {
        if (currentApiKey.isNotEmpty()) {
            builder.addHeader("x-api-key", currentApiKey)
        }
        builder.addHeader("anthropic-version", ANTHROPIC_VERSION)
    }

    /**
     * Claude 使用 /v1/messages 端点。
     */
    override fun buildChatUrl(): String {
        val baseUrl = ModelListFetcher.getModelsListUrl(apiEndpoint, providerType)
            .removeSuffix("/models")
            .removeSuffix("/v1")
        return "$baseUrl/v1/messages"
    }

    // ==================== 请求体构建 ====================

    /**
     * 构建 Claude 格式的请求体。
     * 重写 OpenAIProvider 的 createRequestBody，因为 Claude 的格式与 OpenAI 差异较大。
     */
    override fun createRequestBody(
        chatHistory: List<ChatMessageTurn>,
        temperature: Float,
        maxTokens: Int?,
        stream: Boolean,
        systemPrompt: String?
    ): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream)

        // Claude 要求 max_tokens 必填
        val effectiveMaxTokens = maxTokens ?: resolveOfficialAnthropicMaxTokens() ?: DEFAULT_MAX_TOKENS
        jsonObject.put("max_tokens", effectiveMaxTokens)
        jsonObject.put("temperature", temperature.toDouble())

        // 构建 messages 数组，提取 system 消息到顶层 system 字段
        val messagesArray = JSONArray()
        val systemBlocks = JSONArray()

        systemPrompt?.let {
            systemBlocks.put(JSONObject().apply {
                put("type", "text")
                put("text", it)
            })
        }

        chatHistory.forEach { turn ->
            if (turn.role == "system") {
                systemBlocks.put(JSONObject().apply {
                    put("type", "text")
                    put("text", turn.content)
                })
            } else {
                val claudeRole = if (turn.role == "assistant") "assistant" else "user"
                messagesArray.put(JSONObject().apply {
                    put("role", claudeRole)
                    put("content", buildContentArray(turn.content))
                })
            }
        }

        jsonObject.put("messages", messagesArray)

        // Claude 对系统消息使用顶层 system 字段
        if (systemBlocks.length() > 0) {
            // 应用 cache_control 到最后一个 system 块
            if (enableClaude1hPromptCache) {
                val lastBlock = systemBlocks.optJSONObject(systemBlocks.length() - 1)
                lastBlock?.put("cache_control", cacheControlObject())
            }
            jsonObject.put("system", systemBlocks)
        }

        // 应用 cache_control 到最后一条消息的最后一个 content 块
        if (enableClaude1hPromptCache && messagesArray.length() > 0) {
            val lastMessage = messagesArray.optJSONObject(messagesArray.length() - 1)
            val contentArray = lastMessage?.optJSONArray("content")
            if (contentArray != null && contentArray.length() > 0) {
                val lastBlock = contentArray.optJSONObject(contentArray.length() - 1)
                lastBlock?.put("cache_control", cacheControlObject())
            }
        }

        // 添加 extended thinking 支持
        if (enableThinking) {
            val format = getThinkingFormat()
            when (format) {
                ThinkingFormat.ADAPTIVE -> {
                    // adaptive thinking: thinking.type=adaptive + display=summarized
                    val thinkingObject = JSONObject()
                    thinkingObject.put("type", "adaptive")
                    thinkingObject.put("display", "summarized")
                    jsonObject.put("thinking", thinkingObject)
                    Log.d(TAG, "启用 Claude adaptive thinking, display=summarized")
                }
                ThinkingFormat.ENABLED -> {
                    // enabled thinking: thinking.type=enabled + budget_tokens
                    val thinkingObject = JSONObject()
                    thinkingObject.put("type", "enabled")
                    val budgetTokens = minOf(1024, effectiveMaxTokens)
                    thinkingObject.put("budget_tokens", budgetTokens)
                    jsonObject.put("thinking", thinkingObject)
                    Log.d(TAG, "启用 Claude extended thinking, budget_tokens=$budgetTokens")
                }
            }
        }

        return jsonObject
    }

    private fun cacheControlObject(): JSONObject {
        return JSONObject().apply {
            put("type", PROMPT_CACHE_CONTROL_TYPE)
            if (enableClaude1hPromptCache) {
                put("ttl", "1h")
            }
        }
    }

    /**
     * 构建包含文本的 content 数组（Claude 格式）。
     */
    private fun buildContentArray(text: String): JSONArray {
        val contentArray = JSONArray()
        val effectiveText = if (text.isBlank()) EMPTY_MESSAGE_TEXT else text
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", effectiveText)
        })
        return contentArray
    }

    // ==================== Max Tokens 解析 ====================

    private fun resolveOfficialAnthropicMaxTokens(): Int? {
        if (providerType != ApiProviderType.ANTHROPIC) {
            return null
        }
        val normalizedModelName = modelName.trim().lowercase()
        return when {
            normalizedModelName.startsWith("claude-opus-4-1") -> 32_000
            normalizedModelName.startsWith("claude-opus-4") -> 32_000
            normalizedModelName.startsWith("claude-sonnet-4") -> 64_000
            normalizedModelName.startsWith("claude-3-7-sonnet") -> 64_000
            normalizedModelName.startsWith("claude-3-5-sonnet") -> 8_192
            normalizedModelName.startsWith("claude-3-5-haiku") -> 8_192
            normalizedModelName.startsWith("claude-3-haiku") -> 4_096
            else -> DEFAULT_MAX_TOKENS
        }
    }

    // ==================== Thinking 格式启发式 ====================

    /**
     * 判断模型是否推荐使用 adaptive thinking 格式。
     */
    private fun prefersAdaptiveThinking(): Boolean {
        val name = normalizeClaudeModelName(modelName)
        return hasClaudeFamilyAtLeast(name, "opus", 4, 6) ||
                hasClaudeFamilyAtLeast(name, "sonnet", 4, 6)
    }

    private fun normalizeClaudeModelName(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(Regex("(?<=[a-z])(?=\\d)|(?<=\\d)(?=[a-z])"), "-")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun hasClaudeFamilyAtLeast(
        normalizedModelName: String,
        family: String,
        minMajor: Int,
        minMinor: Int
    ): Boolean {
        val version = claudeFamilyVersion(normalizedModelName, family) ?: return false
        val major = version.first
        val minor = version.second
        return major > minMajor || (major == minMajor && minor >= minMinor)
    }

    private fun claudeFamilyVersion(normalizedModelName: String, family: String): Pair<Int, Int>? {
        val parts = normalizedModelName.split('-').filter { it.isNotEmpty() }
        val familyIndex = parts.indexOf(family)
        if (familyIndex == -1) return null

        val afterFamily = parts.drop(familyIndex + 1)
            .takeWhile { it.all(Char::isDigit) && it.length < 8 }
            .take(2)
        return numericVersion(afterFamily)
    }

    private fun numericVersion(parts: List<String>): Pair<Int, Int>? {
        val major = parts.firstOrNull()?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major to minor
    }

    private fun getThinkingFormat(): ThinkingFormat {
        return cachedThinkingFormat
            ?: if (prefersAdaptiveThinking()) ThinkingFormat.ADAPTIVE
            else ThinkingFormat.ENABLED
    }

    // ==================== XML 工具调用格式 ====================

    /**
     * XML 转义工具。
     */
    private object XmlEscaper {
        fun escape(text: String): String {
            return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }

        fun unescape(text: String): String {
            return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
    }

    /**
     * 将工具调用参数序列化为 XML 格式。
     * 示例：<tool_use name="example"><param>value</param></tool_use>
     */
    fun serializeToolCallToXml(toolName: String, params: JSONObject): String {
        val builder = StringBuilder()
        builder.append("\n<$toolName name=\"${XmlEscaper.escape(toolName)}\">")
        for (key in params.keys()) {
            val value = params.optString(key, "")
            builder.append("\n  <$key>${XmlEscaper.escape(value)}</$key>")
        }
        builder.append("\n</$toolName>\n")
        return builder.toString()
    }

    /**
     * 从 XML 格式解析工具调用结果。
     */
    fun parseToolResultFromXml(xmlContent: String): String {
        return XmlEscaper.unescape(xmlContent).trim()
    }

    // ==================== 消息发送与 SSE 解析 ====================

    /**
     * 重写 sendMessage 以处理 Claude 特有的 SSE 事件格式。
     * Claude 的 SSE 事件类型包括：message_start, content_block_start,
     * content_block_delta (text_delta, thinking_delta, input_json_delta),
     * content_block_stop, message_delta, message_stop。
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
    ): Flow<String> {
        return flow {
            val maxRetries = if (enableRetry) 2 else 0
            var retryCount = 0
            var lastException: Exception? = null

            while (retryCount <= maxRetries) {
                try {
                    val apiKey = apiKeyProvider.getApiKey()
                    val requestBody = createRequestBody(
                        chatHistory = chatHistory,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        stream = stream,
                        systemPrompt = systemPrompt
                    )

                    val url = buildChatUrl()
                    val requestBuilder = Request.Builder()
                        .url(url)
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                        .header("Content-Type", "application/json")

                    applyAuthenticationHeaders(requestBuilder, apiKey)

                    customHeaders.forEach { (key, value) ->
                        requestBuilder.header(key, value)
                    }

                    val request = requestBuilder
                        .post(requestBody.toString().toRequestBody(JSON))
                        .build()

                    Log.d(TAG, "发送请求到: $url, model=$modelName, stream=$stream")

                    if (stream) {
                        sendClaudeStreamRequest(request).collect { emit(it) }
                    } else {
                        emit(sendClaudeNonStreamRequest(request))
                    }
                    return@flow
                } catch (e: IOException) {
                    lastException = e
                    if (!enableRetry || retryCount >= maxRetries) throw e
                    retryCount++
                    Log.w(TAG, "请求失败，重试 $retryCount/$maxRetries: ${e.message}")
                    delay(1000L * retryCount)
                } catch (e: Exception) {
                    lastException = e
                    if (!enableRetry || retryCount >= maxRetries) throw e
                    retryCount++
                    Log.w(TAG, "请求异常，重试 $retryCount/$maxRetries: ${e.message}")
                    delay(1000L * retryCount)
                }
            }
            throw lastException ?: IOException("请求失败")
        }.flowOn(Dispatchers.IO)
    }

    /**
     * 发送 Claude 流式请求并解析 SSE 事件。
     */
    private fun sendClaudeStreamRequest(request: Request): Flow<String> = callbackFlow {
        val eventSourceFactory = EventSources.createFactory(client)

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    channel.close()
                    return
                }

                try {
                    val json = JSONObject(data)

                    val error = json.optJSONObject("error")
                    if (error != null) {
                        val message = error.optString("message", "未知错误")
                        channel.close(IOException("Claude API错误: $message"))
                        return
                    }

                    val eventType = json.optString("type", "")

                    when (eventType) {
                        "ping" -> { /* 心跳 */ }
                        "message_start" -> {
                            // 提取初始 usage
                            val usage = json.optJSONObject("message")?.optJSONObject("usage")
                            if (usage != null) {
                                val inputTokens = usage.optInt("input_tokens", 0)
                                val cachedTokens = usage.optInt("cache_read_input_tokens", 0)
                                Log.d(TAG, "Claude message_start: input=$inputTokens, cached=$cachedTokens")
                            }
                        }
                        "content_block_start" -> {
                            // content 块开始（text, thinking, tool_use）
                        }
                        "content_block_delta" -> {
                            val delta = json.optJSONObject("delta")
                            if (delta != null) {
                                val deltaType = delta.optString("type", "")
                                when (deltaType) {
                                    "text_delta" -> {
                                        val content = delta.optString("text", "")
                                        if (content.isNotEmpty()) {
                                            trySend(content)
                                        }
                                    }
                                    "thinking_delta" -> {
                                        val thinking = delta.optString("thinking", "")
                                        if (thinking.isNotEmpty()) {
                                            trySend(thinking)
                                        }
                                    }
                                    "input_json_delta" -> {
                                        // 工具调用参数的增量 JSON（简化处理）
                                        val partialJson = delta.optString("partial_json", "")
                                        if (partialJson.isNotEmpty()) {
                                            trySend(partialJson)
                                        }
                                    }
                                }
                            }
                        }
                        "content_block_stop" -> {
                            // content 块结束
                        }
                        "message_delta" -> {
                            // 消息级别的 delta（可能包含 usage）
                            val usage = json.optJSONObject("usage")
                            if (usage != null) {
                                val outputTokens = usage.optInt("output_tokens", 0)
                                Log.d(TAG, "Claude message_delta: output=$outputTokens")
                            }
                        }
                        "message_stop" -> {
                            channel.close()
                        }
                        "" -> {
                            // 兼容 OpenAI 格式的回退
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val choice = choices.getJSONObject(0)
                                val delta = choice.optJSONObject("delta")
                                if (delta != null) {
                                    val content = delta.optString("content")
                                    if (!content.isNullOrEmpty()) {
                                        trySend(content)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析 Claude SSE 事件失败: ${e.message}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!channel.isClosedForSend) {
                    channel.close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = t?.message ?: "Claude SSE连接失败"
                val code = response?.code
                Log.e(TAG, "Claude SSE失败: $errorMsg, code=$code")
                if (!channel.isClosedForSend) {
                    channel.close(IOException("Claude SSE失败: $errorMsg (HTTP $code)", t))
                }
            }
        }

        val eventSource = eventSourceFactory.newEventSource(request, listener)

        awaitClose {
            runCatching { eventSource.cancel() }
        }
    }

    /**
     * 发送 Claude 非流式请求并解析响应。
     */
    private suspend fun sendClaudeNonStreamRequest(request: Request): String = withContext(Dispatchers.IO) {
        val call = client.newCall(request)

        val response = call.execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "无错误信息"
            response.close()
            throw IOException("HTTP ${response.code}: $errorBody")
        }

        val body = response.body?.string() ?: ""
        response.close()

        try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            if (error != null) {
                val message = error.optString("message", "未知错误")
                throw IOException("Claude API错误: $message")
            }

            // 解析 content 数组
            val contentArray = json.optJSONArray("content")
            if (contentArray != null && contentArray.length() > 0) {
                val fullText = StringBuilder()
                for (i in 0 until contentArray.length()) {
                    val block = contentArray.optJSONObject(i) ?: continue
                    when (block.optString("type")) {
                        "text" -> {
                            val text = block.optString("text", "")
                            if (text.isNotEmpty()) {
                                fullText.append(text)
                            }
                        }
                        "thinking" -> {
                            val thinking = block.optString("thinking", "")
                            if (thinking.isNotEmpty()) {
                                fullText.append("\n<think>")
                                fullText.append(thinking)
                                fullText.append("</think>\n")
                            }
                        }
                    }
                }
                return@withContext fullText.toString()
            }

            // 兼容 OpenAI 格式回退
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                val message = choice.optJSONObject("message")
                if (message != null) {
                    return@withContext message.optString("content", "")
                }
            }

            ""
        } catch (e: Exception) {
            when (e) {
                is IOException -> throw e
                else -> {
                    Log.e(TAG, "解析 Claude 响应失败", e)
                    throw IOException("解析响应失败: ${e.message}", e)
                }
            }
        }
    }
}
