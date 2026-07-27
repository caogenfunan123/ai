package com.her.aimodifier.ai.provider

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
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
import java.security.MessageDigest

/**
 * OpenAI Responses API Provider。
 *
 * 核心特性：
 * - 使用 /v1/responses 端点（而非 /v1/chat/completions）
 * - 使用 input 而非 messages
 * - 使用 max_output_tokens 而非 max_tokens
 * - 使用 reasoning.effort 控制推理深度
 * - 使用 prompt_cache_key（SHA-256 计算）实现缓存复用
 * - 使用 reasoning.encrypted_content 实现多轮推理复用
 */
class OpenAIResponsesProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.OPENAI,
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
        private const val TAG = "OpenAIResponsesProvider"
        private const val MIN_THINKING_QUALITY_LEVEL = 1
        private const val MAX_THINKING_QUALITY_LEVEL = 5
    }

    /**
     * 使用 /v1/responses 端点。
     */
    override fun buildChatUrl(): String {
        val baseUrl = ModelListFetcher.getModelsListUrl(apiEndpoint, providerType)
            .removeSuffix("/models")
            .removeSuffix("/v1")
        return "$baseUrl/v1/responses"
    }

    // ==================== 请求体构建 ====================

    /**
     * 构建 Responses API 格式的请求体。
     * 将 OpenAI Chat 格式转换为 Responses 格式：
     * - messages → input
     * - max_tokens → max_output_tokens
     * - 添加 reasoning.effort
     * - 添加 prompt_cache_key（SHA-256）
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

        // max_tokens → max_output_tokens
        maxTokens?.let {
            jsonObject.put("max_output_tokens", it)
        }

        // 构建 input 数组（而非 messages）
        val inputArray = JSONArray()

        systemPrompt?.let {
            inputArray.put(JSONObject().apply {
                put("type", "message")
                put("role", "developer")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", it)
                    })
                })
            })
        }

        chatHistory.forEach { turn ->
            val mappedRole = when (turn.role) {
                "system" -> "developer"
                else -> turn.role
            }

            val contentType = if (turn.role == "assistant") "output_text" else "input_text"

            inputArray.put(JSONObject().apply {
                put("type", "message")
                put("role", mappedRole)
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", contentType)
                        put("text", turn.content)
                    })
                })
            })
        }

        jsonObject.put("input", inputArray)

        // 应用 reasoning.effort
        applyResponsesReasoningEffort(jsonObject, enableThinking)

        // 应用 prompt_cache_key（SHA-256 计算）
        val promptCacheKey = buildPromptCacheKey(inputArray)
        if (promptCacheKey != null) {
            jsonObject.put("prompt_cache_key", promptCacheKey)
            Log.d(TAG, "Responses API 自动附加 prompt_cache_key: $promptCacheKey")
        }

        return jsonObject
    }

    /**
     * 应用 reasoning.effort 参数。
     */
    private fun applyResponsesReasoningEffort(requestJson: JSONObject, enableThinking: Boolean) {
        val reasoningObject = requestJson.optJSONObject("reasoning") ?: JSONObject()

        val existingEffort = reasoningObject.optString("effort", "").trim().takeIf { it.isNotEmpty() }
        if (existingEffort == null) {
            val effort = if (enableThinking) resolveResponsesReasoningEffort() else "none"
            if (effort != null) {
                reasoningObject.put("effort", effort)
                Log.d(TAG, "Responses reasoning.effort=$effort")
            }
        }

        // 启用思考时添加 summary=auto
        val existingSummary = reasoningObject.optString("summary", "").trim().takeIf { it.isNotEmpty() }
        if (enableThinking && existingSummary == null) {
            reasoningObject.put("summary", "auto")
            Log.d(TAG, "Responses reasoning summary enabled via reasoning.summary=auto")
        }

        requestJson.put("reasoning", reasoningObject)

        // 启用推理时附加 reasoning.encrypted_content 到 include 数组
        if (reasoningObject.optString("effort", "").trim() != "none") {
            ensureResponsesReasoningEncryptedContentIncluded(requestJson)
        }
    }

    /**
     * 确保 include 数组包含 reasoning.encrypted_content 以实现多轮推理复用。
     */
    private fun ensureResponsesReasoningEncryptedContentIncluded(requestJson: JSONObject) {
        val includeArray = requestJson.optJSONArray("include") ?: JSONArray().also {
            requestJson.put("include", it)
        }
        for (i in 0 until includeArray.length()) {
            if (includeArray.optString(i, "") == "reasoning.encrypted_content") {
                return
            }
        }
        includeArray.put("reasoning.encrypted_content")
    }

    /**
     * 根据思考质量等级解析 reasoning.effort。
     */
    private fun resolveResponsesReasoningEffort(): String {
        val effortLevels = listOf("low", "medium", "high", "xhigh", "max")
        val qualityIndex = thinkingQualityLevel.coerceIn(
            MIN_THINKING_QUALITY_LEVEL,
            MAX_THINKING_QUALITY_LEVEL
        ) - 1
        return effortLevels[qualityIndex]
    }

    /**
     * 构建 prompt_cache_key（SHA-256 计算）。
     * 锚点包含模型名、工具调用标志、system 消息与首个 user 消息。
     */
    private fun buildPromptCacheKey(inputArray: JSONArray): String? {
        if (inputArray.length() == 0) {
            return null
        }

        val anchorParts = mutableListOf<String>()
        var assistantOrToolSeen = false

        for (i in 0 until inputArray.length()) {
            val message = inputArray.optJSONObject(i) ?: continue
            val role = message.optString("role", "")
            if (role.isEmpty()) {
                continue
            }

            if (role == "assistant") {
                assistantOrToolSeen = true
                break
            }

            if (role == "system" || role == "developer") {
                anchorParts.add("$role:${message.opt("content")}")
                continue
            }

            if (role == "user") {
                anchorParts.add("$role:${message.opt("content")}")
                break
            }
        }

        if (anchorParts.isEmpty() && assistantOrToolSeen) {
            val firstMessage = inputArray.optJSONObject(0)
            if (firstMessage != null) {
                anchorParts.add(
                    "${firstMessage.optString("role", "unknown")}:${firstMessage.opt("content")}"
                )
            }
        }

        val digestInput = buildString {
            append("aimodifier:responses_prompt_cache:v1")
            append("|model=").append(modelName)
            append("|toolCall=").append(enableToolCall)
            anchorParts.forEach { part ->
                append("|anchor=").append(part)
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(digestInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return "aimod_resp_${digest.take(48)}"
    }

    // ==================== 消息发送 ====================

    /**
     * 重写 sendMessage 以处理 Responses API 特有的 SSE 事件格式。
     * Responses API 流式事件类型包括：
     * - response.output_item.added
     * - response.content_part.added
     * - response.output_text.delta（增量文本）
     * - response.output_text.done
     * - response.completed
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

                    applyAuthenticationHeaders(requestBuilder, apiKey)

                    customHeaders.forEach { (key, value) ->
                        requestBuilder.header(key, value)
                    }

                    val request = requestBuilder
                        .post(requestBody.toString().toRequestBody(JSON))
                        .build()

                    Log.d(TAG, "发送请求到: $url, model=$modelName, stream=$stream")

                    if (stream) {
                        sendResponsesStreamRequest(request).collect { emit(it) }
                    } else {
                        emit(sendResponsesNonStreamRequest(request))
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
     * 发送 Responses API 流式请求并解析 SSE 事件。
     */
    private fun sendResponsesStreamRequest(request: Request): Flow<String> = callbackFlow {
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
                        channel.close(IOException("Responses API错误: $message"))
                        return
                    }

                    val eventType = json.optString("type", "")

                    when (eventType) {
                        "response.output_text.delta" -> {
                            val delta = json.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                trySend(delta)
                            }
                        }
                        "response.completed" -> {
                            // 提取 usage
                            val response = json.optJSONObject("response")
                            val usage = response?.optJSONObject("usage")
                            if (usage != null) {
                                val inputTokens = usage.optInt("input_tokens", usage.optInt("prompt_tokens", 0))
                                val outputTokens = usage.optInt("output_tokens", usage.optInt("completion_tokens", 0))
                                Log.d(TAG, "Responses usage: input=$inputTokens, output=$outputTokens")
                            }
                            channel.close()
                        }
                        "response.failed",
                        "response.error" -> {
                            val errorObj = json.optJSONObject("error")
                            val message = errorObj?.optString("message", "未知错误") ?: "未知错误"
                            channel.close(IOException("Responses API失败: $message"))
                        }
                        "" -> {
                            // 兼容 OpenAI Chat 格式回退
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
                    Log.w(TAG, "解析 Responses SSE 事件失败: ${e.message}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!channel.isClosedForSend) {
                    channel.close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = t?.message ?: "Responses SSE连接失败"
                val code = response?.code
                Log.e(TAG, "Responses SSE失败: $errorMsg, code=$code")
                if (!channel.isClosedForSend) {
                    channel.close(IOException("Responses SSE失败: $errorMsg (HTTP $code)", t))
                }
            }
        }

        val eventSource = eventSourceFactory.newEventSource(request, listener)

        awaitClose {
            runCatching { eventSource.cancel() }
        }
    }

    /**
     * 发送 Responses API 非流式请求并解析响应。
     */
    private suspend fun sendResponsesNonStreamRequest(request: Request): String = withContext(Dispatchers.IO) {
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
                throw IOException("Responses API错误: $message")
            }

            // 解析 output 数组
            val outputArray = json.optJSONArray("output")
            if (outputArray != null && outputArray.length() > 0) {
                val fullText = StringBuilder()
                for (i in 0 until outputArray.length()) {
                    val item = outputArray.optJSONObject(i) ?: continue
                    if (item.optString("type") == "message") {
                        val contentArray = item.optJSONArray("content")
                        if (contentArray != null) {
                            for (j in 0 until contentArray.length()) {
                                val part = contentArray.optJSONObject(j) ?: continue
                                val partType = part.optString("type", "")
                                if (partType == "output_text" || partType == "text") {
                                    val text = part.optString("text", "")
                                    if (text.isNotEmpty()) {
                                        fullText.append(text)
                                    }
                                }
                            }
                        }
                    }
                }
                return@withContext fullText.toString()
            }

            // 兼容 OpenAI Chat 格式回退
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
                    Log.e(TAG, "解析 Responses 响应失败", e)
                    throw IOException("解析响应失败: ${e.message}", e)
                }
            }
        }
    }
}
