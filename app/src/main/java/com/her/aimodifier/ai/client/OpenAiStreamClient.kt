package com.her.aimodifier.ai.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 SSE 流式客户端。
 *
 * - [stream]：发起流式对话，逐 token 推送
 * - [fetchModels]：调用 /v1/models 获取模型列表（用于配置页）
 *
 * 兼容 OpenAI、DeepSeek、各类中转站。
 */
class OpenAiStreamClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val sseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val restClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val factory by lazy { EventSources.createFactory(sseClient) }

    /**
     * 流式对话。
     *
     * @param baseUrl 中转站 URL，如 "https://api.openai.com/v1"
     * @param apiKey API Key
     * @param request 请求体
     * @return Flow 推送每个 chunk 的 content 字符串；遇到错误抛 [StreamException]
     */
    fun stream(
        baseUrl: String,
        apiKey: String,
        request: ChatCompletionRequest
    ): Flow<String> = callbackFlow {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val body = json.encodeToString(ChatCompletionRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .post(body)
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    channel.close()
                    return
                }
                val chunk = runCatching { json.decodeFromString(ChatCompletionChunk.serializer(), data) }.getOrNull()
                val content = chunk?.choices?.firstOrNull()?.delta?.content
                if (!content.isNullOrEmpty()) {
                    trySend(content)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                channel.close(StreamException("SSE 失败：${t?.message}", response?.code, t))
            }
        }
        factory.newEventSource(req, listener)

        awaitClose { /* EventSource 自动管理 */ }
    }

    /**
     * 获取模型列表（/v1/models）。
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/models"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        restClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw StreamException("HTTP ${resp.code}", resp.code)
            val body = resp.body?.string().orEmpty()
            json.decodeFromString(ModelsResponse.serializer(), body).data
        }
    }
}

class StreamException(message: String, val code: Int? = null, cause: Throwable? = null) : Exception(message, cause)
