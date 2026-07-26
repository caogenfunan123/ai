package com.her.aimodifier.ai.routing

import android.util.Log
import com.her.aimodifier.ai.client.ChatCompletionRequest
import com.her.aimodifier.ai.client.ChatMessage
import com.her.aimodifier.ai.client.OpenAiStreamClient
import com.her.aimodifier.ai.client.StreamException
import com.her.aimodifier.ai.local_gguf.LocalGgufManager
import com.her.aimodifier.data.repository.AiConfigRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
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
 *
 * 降级在 [stream] 中执行；[decide] 仅做静态决策，运行期动态条件在 [stream] 里补做。
 */
class AiTaskRouter(
    private val cloudClient: OpenAiStreamClient,
    private val localGgufManager: LocalGgufManager,
    private val configRepository: AiConfigRepository
) {

    companion object {
        private const val TAG = "AiTaskRouter"

        /** 默认云端调用就绪超时（毫秒），超时视为不可用并降级 */
        const val CLOUD_READY_TIMEOUT_MS: Long = 3_000L

        /** 网络连通性探测超时（毫秒） */
        private const val NETWORK_PROBE_TIMEOUT_MS: Long = 1_500L

        /** API Key 有效性探测超时（毫秒） */
        private const val API_KEY_PROBE_TIMEOUT_MS: Long = 3_000L

        /** 本地 GGUF baseUrl */
        private const val LOCAL_BASE_URL = "http://127.0.0.1:8080/v1"

        /** 402 Payment Required：配额/余额耗尽 */
        private const val HTTP_PAYMENT_REQUIRED = 402

        /** 429 Too Many Requests：限流 */
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** 401 Unauthorized：API Key 失效 */
        private const val HTTP_UNAUTHORIZED = 401

        /** 403 Forbidden：Key 无权限 */
        private const val HTTP_FORBIDDEN = 403
    }

    enum class RouteTarget { CLOUD, LOCAL, NONE }

    /**
     * 路由失败原因，用于 UI/日志定位。
     */
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
        val reason: String
    )

    /**
     * 决定路由（仅静态决策，不做网络/Key 可用性探测）。
     *
     * 运行期动态降级在 [stream] 中处理。
     */
    suspend fun decide(
        workspaceId: String?,
        complexity: Complexity = Complexity.SIMPLE,
        forceLocal: Boolean = false,
        forceCloud: Boolean = false
    ): RouteDecision {
        val effective = configRepository.findEffective(workspaceId)

        // —— 本地 GGUF 兜底（最高本地优先级）——
        if (localGgufManager.isRunning()) {
            val local = localDecision()
            // 强制本地或任务为简单任务时直接走本地
            if (forceLocal || complexity == Complexity.SIMPLE) {
                return local.copy(reason = if (forceLocal) "强制本地" else "简单任务→本地 GGUF")
            }
        } else if (forceLocal) {
            // 用户强制本地但本地没跑起来
            return RouteDecision(RouteTarget.NONE, "", "", "", "强制本地但 GGUF 未启动")
        }

        // —— 没有任何有效配置 ——
        if (effective == null) {
            return if (localGgufManager.isRunning()) {
                localDecision().copy(reason = "无云端配置，回退本地")
            } else {
                RouteDecision(RouteTarget.NONE, "", "", "", "无可用 AI 配置")
            }
        }

        val (config, fromWorkspace) = effective
        val scopeTag = if (fromWorkspace) "workspace 自定义" else "global 自定义"

        // —— 强制云端 ——
        if (forceCloud) {
            return if (config.baseUrl.isNotEmpty()) {
                cloudDecision(config.baseUrl, config.apiKey, config.defaultModel, "强制云端($scopeTag)")
            } else {
                RouteDecision(RouteTarget.NONE, "", "", "", "强制云端但无有效配置")
            }
        }

        // —— 静态规则：complexity 决定 ——
        return when {
            complexity == Complexity.COMPLEX && config.baseUrl.isNotEmpty() ->
                cloudDecision(config.baseUrl, config.apiKey, config.defaultModel, "复杂任务→云端($scopeTag)")

            complexity == Complexity.SIMPLE && localGgufManager.isRunning() ->
                localDecision().copy(reason = "简单任务→本地 GGUF")

            config.baseUrl.isNotEmpty() ->
                cloudDecision(config.baseUrl, config.apiKey, config.defaultModel, "默认→云端($scopeTag)")

            localGgufManager.isRunning() ->
                localDecision().copy(reason = "无云端配置，回退本地")

            else -> RouteDecision(RouteTarget.NONE, "", "", "", "无可用 AI 配置")
        }
    }

    private fun cloudDecision(
        baseUrl: String,
        apiKey: String,
        model: String,
        reason: String
    ) = RouteDecision(RouteTarget.CLOUD, baseUrl, apiKey, model, reason)

    private fun localDecision() = RouteDecision(
        RouteTarget.LOCAL,
        LOCAL_BASE_URL,
        "local",
        localGgufManager.loadedModelPath() ?: "",
        "本地 GGUF"
    )

    // ==================================================================
    //  运行期探测（网络 / API Key / 配额）
    // ==================================================================

    /**
     * 轻量网络连通性探测：尝试与 Google DNS / 目标端点建立 TCP 连接。
     * 若默认网关都不可达，视为网络断开。
     */
    private suspend fun isNetworkAvailable(): Boolean = withContext(Dispatchers.IO) {
        // 先尝试公共 DNS，避免依赖具体业务端点
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

    /**
     * 快速检查 API Key 有效性：调用一次 /v1/models。
     *
     * @return [KeyProbeResult]
     */
    private suspend fun probeCloudKey(
        baseUrl: String,
        apiKey: String
    ): KeyProbeResult {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return KeyProbeResult.Invalid("baseUrl / apiKey 为空")
        }
        return withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(API_KEY_PROBE_TIMEOUT_MS) {
                runCatching {
                    cloudClient.fetchModels(baseUrl, apiKey)
                }
            }
            when {
                result == null -> KeyProbeResult.Timeout
                result.isSuccess -> KeyProbeResult.Ok
                else -> {
                    val ex = result?.exceptionOrNull()
                    val code = (ex as? StreamException)?.code
                    when (code) {
                        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> KeyProbeResult.Invalid("HTTP $code")
                        HTTP_PAYMENT_REQUIRED -> KeyProbeResult.QuotaExhausted
                        HTTP_TOO_MANY_REQUESTS -> KeyProbeResult.RateLimited
                        null -> KeyProbeResult.Unreachable(ex?.message ?: "未知错误")
                        else -> KeyProbeResult.Unreachable("HTTP $code")
                    }
                }
            }
        }
    }

    private sealed class KeyProbeResult {
        data object Ok : KeyProbeResult()
        data object Timeout : KeyProbeResult()
        data object QuotaExhausted : KeyProbeResult()
        data object RateLimited : KeyProbeResult()
        data class Invalid(val msg: String) : KeyProbeResult()
        data class Unreachable(val msg: String) : KeyProbeResult()
    }

    // ==================================================================
    //  主入口：stream
    // ==================================================================

    /**
     * 执行流式对话：路由决策 + 运行期探测 + 分级降级。
     *
     * 降级顺序：
     *   CLOUD → LOCAL(GGUF) → emit 错误
     */
    fun stream(
        messages: List<ChatMessage>,
        decision: RouteDecision,
        temperature: Float = 0.7f,
        timeoutMs: Long? = null
    ): Flow<String> = flow {
        if (decision.target == RouteTarget.NONE) {
            throw NoAvailableModelError(decision.reason)
        }

        // —— 网络断开 → 强制锁本地 ——
        if (decision.target == RouteTarget.CLOUD && !isNetworkAvailable()) {
            Log.w(TAG, "网络断开，强制降级本地")
            val local = localDecision()
            createLocalStream(messages, local, temperature).collect { emit(it) }
            return@flow
        }

        // —— 决策为云端时先做 Key / 配额 探测 ——
        if (decision.target == RouteTarget.CLOUD) {
            when (val probe = probeCloudKey(decision.baseUrl, decision.apiKey)) {
                is KeyProbeResult.Ok -> { /* 放行 */ }

                is KeyProbeResult.QuotaExhausted -> {
                    Log.w(TAG, "配额耗尽，切换离线本地模式")
                    if (localGgufManager.isRunning()) {
                        createLocalStream(messages, localDecision(), temperature).collect { emit(it) }
                        return@flow
                    }
                    throw CloudUnavailableException(
                        FailureReason.QUOTA_EXHAUSTED,
                        "云端配额耗尽(402)，本地 GGUF 也不可用"
                    )
                }

                is KeyProbeResult.RateLimited -> {
                    Log.w(TAG, "云端限流(429)，降级本地")
                    if (localGgufManager.isRunning()) {
                        createLocalStream(messages, localDecision(), temperature).collect { emit(it) }
                        return@flow
                    }
                    throw CloudUnavailableException(
                        FailureReason.RATE_LIMITED,
                        "云端限流(429)，本地 GGUF 也不可用"
                    )
                }

                is KeyProbeResult.Invalid -> {
                    Log.w(TAG, "API Key 失效(${probe.msg})，降级本地")
                    if (localGgufManager.isRunning()) {
                        createLocalStream(messages, localDecision(), temperature).collect { emit(it) }
                        return@flow
                    }
                    throw CloudUnavailableException(
                        FailureReason.API_KEY_INVALID,
                        "API Key 失效(${probe.msg})，本地 GGUF 也不可用"
                    )
                }

                is KeyProbeResult.Timeout -> {
                    Log.w(TAG, "API Key 探测超时，降级本地")
                    if (localGgufManager.isRunning()) {
                        createLocalStream(messages, localDecision(), temperature).collect { emit(it) }
                        return@flow
                    }
                    throw CloudUnavailableException(
                        FailureReason.CLOUD_TIMEOUT,
                        "云端响应超时，本地 GGUF 也不可用"
                    )
                }

                is KeyProbeResult.Unreachable -> {
                    Log.w(TAG, "云端不可达(${probe.msg})，降级本地")
                    if (localGgufManager.isRunning()) {
                        createLocalStream(messages, localDecision(), temperature).collect { emit(it) }
                        return@flow
                    }
                    throw CloudUnavailableException(
                        FailureReason.NETWORK_UNREACHABLE,
                        "云端不可达(${probe.msg})，本地 GGUF 也不可用"
                    )
                }
            }
        }

        // —— 真正执行：云端或本地 ——
        if (decision.target == RouteTarget.CLOUD) {
            val cloudOk = runCloudStreamWithFallback(messages, decision, temperature)
            if (cloudOk) return@flow
            // 云端失败 + 本地不可用：抛错
            throw CloudUnavailableException(
                FailureReason.CLOUD_TIMEOUT,
                "云端流已失败且本地 GGUF 不可用"
            )
        } else {
            // 本地
            val localOk = runLocalStreamSafely(messages, decision, temperature)
            if (!localOk) throw NoAvailableModelError("本地 GGUF 流式执行失败")
        }
    }.catch { e ->
        when (e) {
            is NoAvailableModelError, is CloudUnavailableException -> throw e
            else -> emit("\n[ERROR] ${e.message}")
        }
    }

    // ==================================================================
    //  具体执行：云端 + 3s 超时降级 本地
    // ==================================================================

    /**
     * 跑云端流；失败自动降级本地。
     * @return true 表示已完成流式推送（不代表成功）
     */
    private suspend fun FlowCollector<String>.runCloudStreamWithFallback(
        messages: List<ChatMessage>,
        decision: RouteDecision,
        temperature: Float
    ): Boolean {
        val request = buildRequest(messages, decision, temperature)

        // 尝试云端（带就绪 3s 超时保护）
        val cloudError: Throwable? = try {
            collectCloudWithTimeout(request, decision)
            null
        } catch (e: Throwable) {
            e
        }

        if (cloudError == null) return true

        // —— 云端失败，降级本地 ——
        Log.w(TAG, "云端流失败：${cloudError.message}，尝试降级本地")

        if (!localGgufManager.isRunning()) return false

        val local = localDecision()
        return runLocalStreamSafely(messages, local, temperature)
    }

    /**
     * 执行云端流式对话，首包 [CLOUD_READY_TIMEOUT_MS] 内未到达则抛 [CloudTimeoutException]。
     * 全程无异常则正常 emit 所有 token。
     */
    private suspend fun FlowCollector<String>.collectCloudWithTimeout(
        request: ChatCompletionRequest,
        decision: RouteDecision
    ) {
        val firstTokenReceived = CompletableDeferred<Unit>()
        val up = cloudClient.stream(decision.baseUrl, decision.apiKey, request)
        val timeoutSignal = CompletableDeferred<Throwable>()

        // supervisorScope 隔离 readyJob 的失败，避免它直接取消父协程
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
                runCatching { readyJob.join() }
                throw t
            }
            readyJob.join()
        }

        // 首包超时信号命中 → 抛异常以触发降级
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

    private suspend fun FlowCollector<String>.runLocalStreamSafely(
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
    ) = ChatCompletionRequest(
        model = decision.model,
        messages = messages,
        temperature = temperature,
        stream = true
    )

    enum class Complexity { SIMPLE, COMPLEX }
}

// =====================================================================
//  异常类
// =====================================================================

class NoAvailableModelError(message: String) : RuntimeException(message)

/**
 * 云端不可用（含 401/402/429/超时/断网等）的分级异常。
 */
class CloudUnavailableException(
    val reason: AiTaskRouter.FailureReason,
    message: String
) : RuntimeException(message)

/**
 * 云端就绪超时（3s 内未吐出第一个 token）。
 */
class CloudTimeoutException : RuntimeException("云端就绪超时（${AiTaskRouter.CLOUD_READY_TIMEOUT_MS}ms）")
