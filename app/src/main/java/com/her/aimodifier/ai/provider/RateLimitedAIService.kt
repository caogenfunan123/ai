package com.her.aimodifier.ai.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore

/**
 * 在委托的 [AIService] 之上叠加速率限制和并发控制。
 *
 * - 当 [rateLimiter] 非空时，每次请求前会先通过滑动窗口限流。
 * - 当 [concurrencySemaphore] 非空时，每次请求会获取一个信号量许可，请求结束后释放。
 *
 * 其余 [AIService] 方法直接委托给 [delegate]。
 */
class RateLimitedAIService(
    private val delegate: AIService,
    private val rateLimiter: SlidingWindowRateLimiter?,
    private val concurrencySemaphore: Semaphore?
) : AIService by delegate {

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
    ): Flow<String> = flow {
        rateLimiter?.acquire()
        concurrencySemaphore?.acquire()

        try {
            delegate.sendMessage(
                context = context,
                chatHistory = chatHistory,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = stream,
                systemPrompt = systemPrompt,
                onTokensUpdated = onTokensUpdated,
                onNonFatalError = onNonFatalError,
                enableRetry = enableRetry
            ).collect { chunk ->
                emit(chunk)
            }
        } finally {
            concurrencySemaphore?.release()
        }
    }
}
