package com.her.aimodifier.ai.routing

import android.content.Context
import android.util.Log
import com.her.aimodifier.ai.client.ChatMessage
import com.her.aimodifier.ai.client.OpenAiStreamClient
import com.her.aimodifier.ai.local_gguf.LocalGgufManager
import com.her.aimodifier.ai.provider.AIService
import com.her.aimodifier.ai.provider.AIServiceFactory
import com.her.aimodifier.ai.provider.ApiProviderType
import com.her.aimodifier.ai.provider.ChatMessageTurn
import com.her.aimodifier.ai.provider.ModelConfigData
import com.her.aimodifier.ai.provider.ModelListFetcher
import com.her.aimodifier.ai.provider.ModelOption
import com.her.aimodifier.data.repository.AiConfigRepository
import com.her.aimodifier.data.repository.ModelConfigConverter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * AI 任务路由（完整版）。
 *
 * 路由规则：
 *   1. COMPLEX（代码生成 / 编译报错 / 逆向分析 / 加解密解析 / 长日志分析）→ 云端
 *   2. SIMPLE （代码补全 / 命令生成 / Q&A / 解释）     → 本地 GGUF（若可用）
 *   3. 云端 3s 超时 → 自动降级本地
 *   4. 网络断开     → 强制锁本地
 *   5. API Key 失效 → 自动降级本地
 *   6. 配额耗尽(402)/限流(429) → 切换离线本地
 *   7. 优先级：workspace 自定义 > global 自定义 > 官方 > 本地 GGUF
 */
class AiTaskRouter(
    private val cloudClient: OpenAiStreamClient,
    private val localGgufManager: LocalGgufManager,
    private val configRepository: AiConfigRepository,
    private val context: Context
) {

    companion object {
        private const val TAG = "AiTaskRouter"
        const val CLOUD_READY_TIMEOUT_MS: Long = 3_000L
        private const val NETWORK_PROBE_TIMEOUT_MS: Long = 1_500L
        private const val API_KEY_PROBE_TIMEOUT_MS: Long = 3_000L
        private const val LOCAL_BASE_URL = "http://127.0.0.1:8080/v1"
        private const val HTTP_PAYMENT_REQUIRED = 402
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
    }

    enum class RouteTarget { CLOUD, LOCAL, NONE }

    enum class FailureReason {
        NETWORK_UNREACHABLE,
        API_KEY_INVALID,
        QUOTA_EXHAUSTED,
        RATE_LIMITED,
        CLOUD_TIMEOUT,
        NO_AVAILABLE_MODEL
    }

    data class RouteDecision(
        val target: RouteTarget,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val providerType: ApiProviderType = ApiProviderType.DEEPSEEK,
        val reason: String,
        val modelConfig: ModelConfigData? = null
    )

    suspend fun decide(
        workspaceId: String?,
        complexity: Complexity = Complexity.SIMPLE,
        forceLocal: Boolean = false,
        forceCloud: Boolean = false
    ): RouteDecision {
        val effective = configRepository.findEffective(workspaceId)

        if (localGgufManager.isRunning()) {
            val local = localDecision()
            if (forceLocal || complexity == Complexity.SIMPLE) {
                return local.copy(reason = if (forceLocal) "强制本地" else "简单任务→本地 GGUF")
            }
        } else if (forceLocal) {
            return RouteDecision(RouteTarget.NONE, "", "", "", reason = "强制本地但 GGUF 未启动")
        }

        if (effective == null) {
            return if (localGgufManager.isRunning()) {
                localDecision().copy(reason = "无云端配置，回退本地")
            } else {
                RouteDecision(RouteTarget.NONE, "", "", "", reason = "无可用 AI 配置")
            }
        }

        val (config, fromWorkspace) = effective
        val scopeTag = if (fromWorkspace) "workspace 自定义" else "global 自定义"
        val modelConfig = ModelConfigConverter.toModelConfigData(config)

        if (forceCloud) {
            return if (config.baseUrl.isNotEmpty()) {
                cloudDecision(modelConfig, "强制云端($scopeTag)")
            } else {
                RouteDecision(RouteTarget.NONE, "", "", "", reason = "强制云端但无有效配置")
            }
        }

        return when {
            complexity == Complexity.COMPLEX && config.baseUrl.isNotEmpty() ->
                cloudDecision(modelConfig, "复杂任务→云端($scopeTag)")

            complexity == Complexity.SIMPLE && localGgufManager.isRunning() ->
                localDecision().copy(reason = "简单任务→本地 GGUF")

            config.baseUrl.isNotEmpty() ->
                cloudDecision(modelConfig, "默认→云端($scopeTag)")

            localGgufManager.isRunning() ->
                localDecision().copy(reason = "无云端配置，回退本地")

            else -> RouteDecision(RouteTarget.NONE, "", "", "", reason = "无可用 AI 配置")
        }
    }

    private fun cloudDecision(
        config: ModelConfigData,
        reason: String
    ) = RouteDecision(
        target = RouteTarget.CLOUD,
        baseUrl = config.apiEndpoint,
        apiKey = config.apiKey,
        model = config.modelName,
        providerType = config.apiProviderType,
        reason = reason,
        modelConfig = config
    )

    private fun localDecision() = RouteDecision(
        RouteTarget.LOCAL,
        LOCAL_BASE_URL,
        "local",
        localGgufManager.loadedModelPath() ?: "",
        reason = "本地 GGUF"
    )

    private suspend fun isNetworkAvailable(): Boolean = withContext(Dispatchers.IO) {
        val candidates = listOf(
            InetSocketAddress("8.8.8.8", 53),
            InetSocketAddress("1.1.1.1", 53)
        )
        candidates.forEach { addr ->
            runCatching {
                Socket().use { socket ->
                    socket.connect(addr, NETWORK_PROBE_TIMEOUT_MS.toInt())
                }
                return@withContext true
            }
        }
        false
    }

    suspend fun fetchModels(decision: RouteDecision): Result<List<ModelOption>> {
        if (decision.target == RouteTarget.LOCAL) {
            return Result.success(emptyList())
        }
        val config = decision.modelConfig ?: return Result.success(emptyList())
        return ModelListFetcher.getModelsList(
            context = context,
            apiKey = config.apiKey,
            apiEndpoint = config.apiEndpoint,
            apiProviderType = config.apiProviderType
        )
    }

    fun stream(
        messages: List<ChatMessage>,
        decision: RouteDecision,
        temperature: Float = 0.7f,
        timeoutMs: Long? = null
    ): Flow<String> = flow {
        if (decision.target == RouteTarget.NONE) {
            throw NoAvailableModelError(decision.reason)
        }

        if (decision.target == RouteTarget.CLOUD && !isNetworkAvailable()) {
            Log.w(TAG, "网络断开，强制降级本地")
            val local = localDecision()
            createLocalStream(messages, local, temperature).collect { emit(it) }
            return@flow
        }

        if (decision.target == RouteTarget.CLOUD && decision.modelConfig != null) {
            val service = AIServiceFactory.createService(decision.modelConfig, context)
            val chatTurns = messages.map { ChatMessageTurn(role = it.role, content = it.content) }
            val systemPrompt = decision.modelConfig.systemPrompt.ifBlank { null }

            try {
                service.sendMessage(
                    context = context,
                    chatHistory = chatTurns,
                    temperature = temperature,
                    maxTokens = if (decision.modelConfig.maxTokensEnabled) decision.modelConfig.maxTokens else null,
                    stream = true,
                    systemPrompt = systemPrompt
                ).collect { chunk ->
                    emit(chunk)
                }
                service.release()
                return@flow
            } catch (e: Exception) {
                Log.w(TAG, "新 AIService 流式失败，回退旧实现: ${e.message}")
                service.cancelStreaming()
                service.release()
            }
        }

        if (decision.target == RouteTarget.CLOUD) {
            val cloudOk = runCloudStreamWithFallback(messages, decision, temperature)
            if (cloudOk) return@flow
            throw CloudUnavailableException(
                FailureReason.CLOUD_TIMEOUT,
                "云端流已失败且本地 GGUF 不可用"
            )
        } else {
            val localOk = runLocalStreamSafely(messages, decision, temperature)
            if (!localOk) throw NoAvailableModelError("本地 GGUF 流式执行失败")
        }
    }.catch { e ->
        when (e) {
            is NoAvailableModelError, is CloudUnavailableException -> throw e
            else -> emit("\n[ERROR] ${e.message}")
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<String>.runCloudStreamWithFallback(
        messages: List<ChatMessage>,
        decision: RouteDecision,
        temperature: Float
    ): Boolean {
        val request = buildRequest(messages, decision, temperature)
        val cloudError: Throwable? = try {
            collectCloudWithTimeout(request, decision)
            null
        } catch (e: Throwable) {
            e
        }

        if (cloudError == null) return true

        Log.w(TAG, "云端流失败：${cloudError.message}，尝试降级本地")
        if (!localGgufManager.isRunning()) return false

        val local = localDecision()
        return runLocalStreamSafely(messages, local, temperature)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<String>.collectCloudWithTimeout(
        request: com.her.aimodifier.ai.client.ChatCompletionRequest,
        decision: RouteDecision
    ) {
        val firstTokenReceived = CompletableDeferred<Unit>()
        val up = cloudClient.stream(decision.baseUrl, decision.apiKey, request)
        val timeoutSignal = CompletableDeferred<Throwable>()

        supervisorScope {
            val readyJob = launch {
                val result = withTimeoutOrNull(CLOUD_READY_TIMEOUT_MS) {
                    firstTokenReceived.await()
                }
                if (result == null) {
                    timeoutSignal.complete(CloudTimeoutException())
                }
            }

            try {
                up.collect { token ->
                    if (!firstTokenReceived.isCompleted) firstTokenReceived.complete(Unit)
                    emit(token)
                }
                readyJob.cancel()
            } catch (t: Throwable) {
                readyJob.cancel()
                if (!firstTokenReceived.isCompleted) firstTokenReceived.completeExceptionally(t)
                kotlin.runCatching { readyJob.join() }
                throw t
            }
            readyJob.join()
        }

        if (timeoutSignal.isCompleted) throw timeoutSignal.getCompleted()
    }

    private fun createLocalStream(
        messages: List<ChatMessage>,
        local: RouteDecision,
        temperature: Float
    ): Flow<String> {
        val request = buildRequest(messages, local, temperature)
        return cloudClient.stream(local.baseUrl, local.apiKey, request)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<String>.runLocalStreamSafely(
        messages: List<ChatMessage>,
        local: RouteDecision,
        temperature: Float
    ): Boolean = kotlin.runCatching {
        createLocalStream(messages, local, temperature).collect { emit(it) }
    }.onFailure { e ->
        Log.e(TAG, "本地流失败：${e.message}", e)
    }.isSuccess

    private fun buildRequest(
        messages: List<ChatMessage>,
        decision: RouteDecision,
        temperature: Float
    ) = com.her.aimodifier.ai.client.ChatCompletionRequest(
        model = decision.model,
        messages = messages,
        temperature = temperature,
        stream = true
    )

    enum class Complexity { SIMPLE, COMPLEX }
}

class NoAvailableModelError(message: String) : RuntimeException(message)

class CloudUnavailableException(
    val reason: AiTaskRouter.FailureReason,
    message: String
) : RuntimeException(message)

class CloudTimeoutException : RuntimeException("云端就绪超时（${AiTaskRouter.CLOUD_READY_TIMEOUT_MS}ms）")
