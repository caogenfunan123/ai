package com.her.aimodifier.ai.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow

interface AIService {
    val inputTokenCount: Int
    val cachedInputTokenCount: Int
    val outputTokenCount: Int
    val providerModel: String

    fun resetTokenCounts()

    fun cancelStreaming()

    suspend fun getModelsList(context: Context): Result<List<ModelOption>>

    suspend fun sendMessage(
        context: Context,
        chatHistory: List<ChatMessageTurn> = emptyList(),
        temperature: Float = 0.7f,
        maxTokens: Int? = null,
        stream: Boolean = true,
        systemPrompt: String? = null,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit = { _, _, _ -> },
        onNonFatalError: suspend (error: String) -> Unit = {},
        enableRetry: Boolean = true
    ): Flow<String>

    suspend fun testConnection(context: Context): Result<String>

    /**
     * 估算下一次请求的输入Token数量
     * @param chatHistory 完整聊天历史
     * @return 估算的输入token总数
     */
    suspend fun calculateInputTokens(
        chatHistory: List<ChatMessageTurn>
    ): Int {
        // 默认实现：简单按字符数估算
        return chatHistory.sumOf { turn ->
            (turn.content.length / 4).coerceAtLeast(1)
        }
    }

    fun release() {}
}

data class ChatMessageTurn(
    val role: String,
    val content: String,
    val name: String? = null
)
