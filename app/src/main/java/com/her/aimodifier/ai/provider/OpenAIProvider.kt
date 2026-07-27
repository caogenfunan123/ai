package com.her.aimodifier.ai.provider

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
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

open class OpenAIProvider(
    protected val apiEndpoint: String,
    protected val apiKeyProvider: ApiKeyProvider,
    protected val modelName: String,
    protected val client: OkHttpClient,
    protected val customHeaders: Map<String, String> = emptyMap(),
    protected val providerType: ApiProviderType = ApiProviderType.OPENAI,
    protected val supportsVision: Boolean = false,
    protected val supportsAudio: Boolean = false,
    protected val supportsVideo: Boolean = false,
    protected val enableToolCall: Boolean = false
) : AIService {

    protected val JSON = "application/json".toMediaType()
    protected val eventSourceFactory by lazy { EventSources.createFactory(client) }

    private var activeCall: okhttp3.Call? = null
    private var activeEventSource: EventSource? = null
    private var activeResponse: Response? = null

    @Volatile
    private var isManuallyCancelled = false

    private var _inputTokenCount = 0
    private var _cachedInputTokenCount = 0
    private var _outputTokenCount = 0

    override val inputTokenCount: Int get() = _inputTokenCount
    override val cachedInputTokenCount: Int get() = _cachedInputTokenCount
    override val outputTokenCount: Int get() = _outputTokenCount

    override val providerModel: String
        get() = "${providerType.name}:$modelName"

    override fun resetTokenCounts() {
        _inputTokenCount = 0
        _cachedInputTokenCount = 0
        _outputTokenCount = 0
    }

    protected open fun applyAuthenticationHeaders(
        builder: Request.Builder,
        currentApiKey: String
    ) {
        if (currentApiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $currentApiKey")
        }
    }

    override fun cancelStreaming() {
        isManuallyCancelled = true
        runCatching { activeResponse?.close() }
        activeResponse = null
        activeCall?.let {
            if (!it.isCanceled()) {
                runCatching { it.cancel() }
            }
        }
        activeCall = null
        runCatching { activeEventSource?.cancel() }
        activeEventSource = null
    }

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return ModelListFetcher.getModelsList(
            context = context,
            apiKey = apiKeyProvider.getApiKey(),
            apiEndpoint = apiEndpoint,
            apiProviderType = providerType
        )
    }

    override suspend fun testConnection(context: Context): Result<String> {
        return try {
            val testHistory = listOf(
                ChatMessageTurn(role = "system", content = "You are a helpful assistant."),
                ChatMessageTurn(role = "user", content = "Hi")
            )
            val stream = sendMessage(
                context,
                testHistory,
                temperature = 0.7f,
                maxTokens = 10,
                stream = false,
                enableRetry = false
            )
            val sb = StringBuilder()
            stream.collect { sb.append(it) }
            Result.success("连接测试成功")
        } catch (e: Exception) {
            Log.e("OpenAIProvider", "连接测试失败", e)
            Result.failure(IOException("连接测试失败: ${e.message}", e))
        }
    }

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
            val maxRetries = if (enableRetry) LlmRetryPolicy.MAX_RETRY_ATTEMPTS else 0
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

                    Log.d("OpenAIProvider", "发送请求到: $url, model=$modelName, stream=$stream")

                    if (stream) {
                        sendStreamRequest(request).collect { emit(it) }
                    } else {
                        emit(sendNonStreamRequest(request))
                    }
                    return@flow
                } catch (e: IOException) {
                    lastException = e
                    if (!enableRetry || retryCount >= maxRetries) throw e
                    retryCount++
                    val retryDelay = LlmRetryPolicy.nextDelayMs(retryCount)
                    Log.w("OpenAIProvider", "请求失败，重试 $retryCount/$maxRetries, ${retryDelay}ms后重试: ${e.message}")
                    if (!shouldSuppressKeyPoolRateLimitNotice(apiKeyProvider, e, "OpenAIProvider")) {
                        onNonFatalError("请求失败，正在重试 $retryCount/$maxRetries...")
                    }
                    delay(retryDelay)
                } catch (e: Exception) {
                    lastException = e
                    if (!enableRetry || retryCount >= maxRetries) throw e
                    retryCount++
                    val retryDelay = LlmRetryPolicy.nextDelayMs(retryCount)
                    Log.w("OpenAIProvider", "请求异常，重试 $retryCount/$maxRetries, ${retryDelay}ms后重试: ${e.message}")
                    onNonFatalError("请求异常，正在重试 $retryCount/$maxRetries...")
                    delay(retryDelay)
                }
            }
            throw lastException ?: IOException("请求失败")
        }.flowOn(Dispatchers.IO)
    }

    protected open fun buildChatUrl(): String {
        return EndpointCompleter.completeEndpoint(apiEndpoint, providerType)
    }

    protected open fun createRequestBody(
        chatHistory: List<ChatMessageTurn>,
        temperature: Float,
        maxTokens: Int?,
        stream: Boolean,
        systemPrompt: String?
    ): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream)
        jsonObject.put("temperature", temperature.toDouble())

        maxTokens?.let { jsonObject.put("max_tokens", it) }

        // 工具调用模式：使用 StructuredToolCallBridge 构建结构化消息
        if (enableToolCall) {
            val mergedHistory = buildMergedHistoryWithSystemPrompt(chatHistory, systemPrompt)
            val structuredMessagesJson = StructuredToolCallBridge.buildMessagesJson(
                history = mergedHistory,
                preserveThinkInHistory = false
            )
            val messagesArray = JSONArray(structuredMessagesJson)
            jsonObject.put("messages", messagesArray)

            // 注入工具定义（由子类或调用方注入 ToolPrompt 列表时构建）
            val toolsJson = buildToolsJsonForRequest()
            if (toolsJson != null) {
                jsonObject.put("tools", JSONArray(toolsJson))
                jsonObject.put("tool_choice", "auto")
            }
        } else {
            val messagesArray = JSONArray()
            systemPrompt?.let {
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", it)
                })
            }
            chatHistory.forEach { turn ->
                messagesArray.put(JSONObject().apply {
                    put("role", turn.role)
                    put("content", buildContentField(turn.content))
                    turn.name?.let { put("name", it) }
                })
            }
            jsonObject.put("messages", messagesArray)
        }

        customizeRequestBody(jsonObject)

        return jsonObject
    }

    /**
     * 将 systemPrompt 合并到聊天历史的最前面，便于工具调用桥接器统一处理。
     */
    private fun buildMergedHistoryWithSystemPrompt(
        chatHistory: List<ChatMessageTurn>,
        systemPrompt: String?
    ): List<ChatMessageTurn> {
        if (systemPrompt.isNullOrBlank()) return chatHistory
        return listOf(ChatMessageTurn(role = "system", content = systemPrompt)) + chatHistory
    }

    /**
     * 子类可重写以提供工具定义 JSON。
     * 默认返回 null（不注入工具定义），仅使用桥接器对历史中的工具调用格式进行规范化。
     */
    protected open fun buildToolsJsonForRequest(): String? = null

    protected open fun customizeRequestBody(jsonObject: JSONObject) {}

    protected open fun buildContentField(text: String): Any {
        return text
    }

    private fun sendStreamRequest(request: Request): Flow<String> = callbackFlow {
        isManuallyCancelled = false

        // 用于累积流式 tool_calls 增量，最后输出为 XML 工具调用标记
        val accumulatedToolCalls = mutableMapOf<Int, JSONObject>()
        var lastEmittedToolCallIndex: Int = -1

        fun flushAccumulatedToolCalls() {
            if (!enableToolCall) return
            if (accumulatedToolCalls.isEmpty()) return

            val sortedIndices = accumulatedToolCalls.keys.sorted()
            for (idx in sortedIndices) {
                val toolCall = accumulatedToolCalls[idx] ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                val name = function.optString("name", "")
                if (name.isBlank()) continue

                val arguments = function.optString("arguments", "")
                val toolCallsArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", toolCall.optString("id", ""))
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", name)
                            put("arguments", arguments)
                        })
                    })
                }
                val xml = StructuredToolCallBridge.convertToolCallPayloadToXml(
                    toolCallsArray.toString()
                )
                if (xml.isNotBlank()) {
                    trySend(xml)
                }
            }
            accumulatedToolCalls.clear()
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (isManuallyCancelled) return

                if (data == "[DONE]") {
                    flushAccumulatedToolCalls()
                    channel.close()
                    return
                }

                try {
                    val json = JSONObject(data)

                    val error = json.optJSONObject("error")
                    if (error != null) {
                        val message = error.optString("message", "未知错误")
                        channel.close(IOException("API错误: $message"))
                        return
                    }

                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")
                        if (delta != null) {
                            val content = delta.optString("content")
                            if (!content.isNullOrEmpty()) {
                                trySend(content)
                            }

                            // 处理工具调用增量
                            val toolCallsDelta = delta.optJSONArray("tool_calls")
                            if (toolCallsDelta != null) {
                                for (i in 0 until toolCallsDelta.length()) {
                                    val tcDelta = toolCallsDelta.optJSONObject(i) ?: continue
                                    val tcIndex = tcDelta.optInt("index", 0)
                                    val existing = accumulatedToolCalls[tcIndex] ?: JSONObject().apply {
                                        put("id", "")
                                        put("type", "function")
                                        put("function", JSONObject().apply {
                                            put("name", "")
                                            put("arguments", "")
                                        })
                                    }
                                    val function = existing.optJSONObject("function") ?: JSONObject().apply {
                                        put("name", "")
                                        put("arguments", "")
                                    }

                                    tcDelta.optString("id").takeIf { it.isNotEmpty() }?.let {
                                        existing.put("id", it)
                                    }
                                    tcDelta.optJSONObject("function")?.let { fnDelta ->
                                        fnDelta.optString("name").takeIf { it.isNotEmpty() }?.let {
                                            function.put("name", it)
                                        }
                                        fnDelta.optString("arguments").takeIf { it.isNotEmpty() }?.let { newArgs ->
                                            val curArgs = function.optString("arguments", "")
                                            function.put("arguments", curArgs + newArgs)
                                        }
                                    }
                                    existing.put("function", function)
                                    accumulatedToolCalls[tcIndex] = existing
                                    if (tcIndex > lastEmittedToolCallIndex) {
                                        lastEmittedToolCallIndex = tcIndex
                                    }
                                }
                            }
                        }

                        val usage = json.optJSONObject("usage")
                        if (usage != null) {
                            val promptTokens = usage.optInt("prompt_tokens", 0)
                            val completionTokens = usage.optInt("completion_tokens", 0)
                            _inputTokenCount = promptTokens
                            _outputTokenCount = completionTokens
                        }

                        val finishReason = choice.optString("finish_reason")
                        if (finishReason == "stop" || finishReason == "length" || finishReason == "tool_calls") {
                            flushAccumulatedToolCalls()
                            channel.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("OpenAIProvider", "解析SSE事件失败: ${e.message}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!channel.isClosedForSend) {
                    flushAccumulatedToolCalls()
                    channel.close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (isManuallyCancelled) {
                    channel.close()
                    return
                }
                val errorMsg = t?.message ?: "SSE连接失败"
                val code = response?.code
                Log.e("OpenAIProvider", "SSE失败: $errorMsg, code=$code")
                if (!channel.isClosedForSend) {
                    channel.close(IOException("SSE失败: $errorMsg (HTTP $code)", t))
                }
            }
        }

        val eventSource = eventSourceFactory.newEventSource(request, listener)
        activeEventSource = eventSource

        awaitClose {
            runCatching { eventSource.cancel() }
        }
    }

    private suspend fun sendNonStreamRequest(request: Request): String = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        activeCall = call

        val response = call.execute()
        activeResponse = response

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
                throw IOException("API错误: $message")
            }

            val usage = json.optJSONObject("usage")
            if (usage != null) {
                _inputTokenCount = usage.optInt("prompt_tokens", 0)
                _outputTokenCount = usage.optInt("completion_tokens", 0)
            }

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
                    Log.e("OpenAIProvider", "解析响应失败", e)
                    throw IOException("解析响应失败: ${e.message}", e)
                }
            }
        }
    }
}

object SharedHttpClient {
    val instance: OkHttpClient by lazy {
        UnsafeModelSsl.apply(
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(1000, TimeUnit.SECONDS)
                .writeTimeout(1000, TimeUnit.SECONDS)
        ).build()
    }
}
