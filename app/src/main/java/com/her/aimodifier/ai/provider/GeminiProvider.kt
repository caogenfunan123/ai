package com.her.aimodifier.ai.provider

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.URL

/**
 * Google Gemini API 的实现。
 *
 * 核心特性：
 * - 使用 key 查询参数进行认证（而非 Authorization 头）
 * - 使用 /v1beta/models/{model}:generateContent 或 :streamGenerateContent 端点
 * - 使用 contents 数组与 parts 格式（而非 messages）
 * - system 消息作为 systemInstruction 顶层字段
 * - generationConfig 包含 temperature/topP/topK/maxOutputTokens
 * - tools 数组支持 function_declarations 与 googleSearch
 */
class GeminiProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.GOOGLE,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false,
    private val enableGoogleSearch: Boolean = false,
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
        private const val TAG = "GeminiProvider"
    }

    // ==================== 认证与 URL ====================

    /**
     * Gemini 使用 key 查询参数认证。
     * 此处不添加 Authorization 头，而是在 URL 中附加 key 参数。
     */
    override fun applyAuthenticationHeaders(
        builder: Request.Builder,
        currentApiKey: String
    ) {
        // 不使用 Authorization 头，key 在 URL 查询参数中附加
    }

    /**
     * 构建带 API Key 的 Gemini 请求 URL。
     */
    private fun buildGeminiUrl(apiKey: String, isStreaming: Boolean): String {
        val baseUrl = determineBaseUrl(apiEndpoint)
        val method = if (isStreaming) "streamGenerateContent" else "generateContent"
        val requestUrl = "$baseUrl/v1beta/models/$modelName:$method"

        val httpUrl = requestUrl.toHttpUrlOrNull()
            ?: return if (requestUrl.contains("?")) {
                "$requestUrl&key=$apiKey"
            } else {
                "$requestUrl?key=$apiKey"
            }

        val finalUrl = httpUrl.newBuilder()
            .setQueryParameter("key", apiKey)
            .build()
        return finalUrl.toString()
    }

    /**
     * 确定基础 URL（协议 + 主机 + 端口）。
     */
    private fun determineBaseUrl(endpoint: String): String {
        return try {
            val url = URL(endpoint)
            val port = if (url.port != -1) ":${url.port}" else ""
            "${url.protocol}://${url.host}${port}"
        } catch (e: Exception) {
            Log.e(TAG, "解析 API 端点失败", e)
            "https://generativelanguage.googleapis.com"
        }
    }

    // ==================== 请求体构建 ====================

    /**
     * 构建 Gemini 格式的请求体。
     */
    override fun createRequestBody(
        chatHistory: List<ChatMessageTurn>,
        temperature: Float,
        maxTokens: Int?,
        stream: Boolean,
        systemPrompt: String?
    ): JSONObject {
        val json = JSONObject()

        // 构建 tools 数组
        val tools = JSONArray()
        if (enableGoogleSearch) {
            tools.put(JSONObject().apply {
                put("googleSearch", JSONObject())
            })
        }
        if (tools.length() > 0) {
            json.put("tools", tools)
        }

        // 构建 contents 数组与 systemInstruction
        val contentsArray = JSONArray()
        var systemInstruction: JSONObject? = null

        // 处理 systemPrompt
        if (!systemPrompt.isNullOrBlank()) {
            systemInstruction = JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            }
        }

        chatHistory.forEach { turn ->
            if (turn.role == "system") {
                // system 消息合并到 systemInstruction
                systemInstruction = JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", turn.content) })
                    })
                }
            } else {
                val geminiRole = if (turn.role == "assistant") "model" else "user"
                contentsArray.put(JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", turn.content) })
                    })
                })
            }
        }

        if (systemInstruction != null) {
            json.put("systemInstruction", systemInstruction)
        }
        json.put("contents", contentsArray)

        // 构建 generationConfig
        val generationConfig = JSONObject()
        generationConfig.put("temperature", temperature.toDouble())

        // 如果启用思考模式，添加 thinkingConfig
        if (enableThinking) {
            val thinkingConfig = JSONObject()
            thinkingConfig.put("includeThoughts", true)
            generationConfig.put("thinkingConfig", thinkingConfig)
            Log.d(TAG, "已为 Gemini 模型启用思考模式")
        }

        maxTokens?.let {
            generationConfig.put("maxOutputTokens", it)
        }

        json.put("generationConfig", generationConfig)

        return json
    }

    // ==================== 消息发送 ====================

    /**
     * 重写 sendMessage 以处理 Gemini 特有的请求与响应格式。
     * Gemini 流式响应返回 JSON 对象序列（非 SSE），需要逐行/逐块解析。
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

                    val url = buildGeminiUrl(apiKey, stream)
                    val requestBuilder = Request.Builder()
                        .url(url)
                        .header("Content-Type", "application/json")

                    customHeaders.forEach { (key, value) ->
                        requestBuilder.header(key, value)
                    }

                    val request = requestBuilder
                        .post(requestBody.toString().toRequestBody(JSON))
                        .build()

                    Log.d(TAG, "发送请求到: $url, model=$modelName, stream=$stream")

                    if (stream) {
                        sendGeminiStreamRequest(request).collect { emit(it) }
                    } else {
                        emit(sendGeminiNonStreamRequest(request))
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
     * 发送 Gemini 流式请求并解析响应。
     * Gemini 流式响应可能是 SSE 格式（data: 前缀）或 JSON 数组/对象序列。
     */
    private fun sendGeminiStreamRequest(request: Request): Flow<String> = flow {
        val call = client.newCall(request)
        val response = call.execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "无错误信息"
            response.close()
            throw IOException("HTTP ${response.code}: $errorBody")
        }

        val responseBody = response.body ?: throw IOException("Gemini 响应为空")
        val reader = BufferedReader(responseBody.charStream())

        try {
            val jsonBuilder = StringBuilder()
            var jsonDepth = 0
            var isCollectingJson = false

            while (true) {
                val line = reader.readLine() ?: break

                val trimmedLine = line.trim()

                // 处理 SSE 格式（data: 前缀）
                if (trimmedLine.startsWith("data:")) {
                    val data = trimmedLine.substringAfter("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isBlank()) continue

                    val content = extractContentFromJson(data)
                    if (content.isNotEmpty()) {
                        emit(content)
                    }
                    continue
                }

                // 处理 JSON 数组/对象序列
                if (trimmedLine.startsWith("{") || trimmedLine.startsWith("[")) {
                    if (!isCollectingJson) {
                        isCollectingJson = true
                        jsonDepth = 0
                        jsonBuilder.clear()
                    }
                    jsonBuilder.append(trimmedLine)

                    for (char in trimmedLine) {
                        if (char == '{' || char == '[') jsonDepth++
                        if (char == '}' || char == ']') jsonDepth--
                    }

                    if (jsonDepth == 0) {
                        val possibleJson = jsonBuilder.toString()
                        val content = extractContentFromJsonString(possibleJson)
                        if (content.isNotEmpty()) {
                            emit(content)
                        }
                        isCollectingJson = false
                        jsonBuilder.clear()
                    }
                } else if (isCollectingJson) {
                    jsonBuilder.append(trimmedLine)
                    for (char in trimmedLine) {
                        if (char == '{' || char == '[') jsonDepth++
                        if (char == '}' || char == ']') jsonDepth--
                    }

                    if (jsonDepth == 0) {
                        val possibleJson = jsonBuilder.toString()
                        val content = extractContentFromJsonString(possibleJson)
                        if (content.isNotEmpty()) {
                            emit(content)
                        }
                        isCollectingJson = false
                        jsonBuilder.clear()
                    }
                }
            }

            // 处理剩余的 JSON
            if (isCollectingJson && jsonBuilder.isNotEmpty()) {
                val content = extractContentFromJsonString(jsonBuilder.toString())
                if (content.isNotEmpty()) {
                    emit(content)
                }
            }
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 发送 Gemini 非流式请求并解析响应。
     */
    private suspend fun sendGeminiNonStreamRequest(request: Request): String = withContext(Dispatchers.IO) {
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
            return@withContext extractContentFromJson(json)
        } catch (e: Exception) {
            when (e) {
                is IOException -> throw e
                else -> {
                    Log.e(TAG, "解析 Gemini 响应失败", e)
                    throw IOException("解析响应失败: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 从 Gemini 响应 JSON 中提取文本内容。
     */
    private fun extractContentFromJson(jsonStr: String): String {
        return try {
            val json = JSONObject(jsonStr)
            extractContentFromJson(json)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从 Gemini 响应 JSON 对象中提取文本内容。
     * 处理 candidates[0].content.parts[].text 格式。
     */
    private fun extractContentFromJson(json: JSONObject): String {
        val contentBuilder = StringBuilder()

        // 检查错误
        val error = json.optJSONObject("error")
        if (error != null) {
            val message = error.optString("message", "未知错误")
            Log.e(TAG, "Gemini API错误: $message")
            return ""
        }

        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return ""
        }

        val candidate = candidates.optJSONObject(0) ?: return ""

        val content = candidate.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""

        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val text = part.optString("text", "")
            if (text.isNotEmpty()) {
                contentBuilder.append(text)
            }
        }

        return contentBuilder.toString()
    }
}
