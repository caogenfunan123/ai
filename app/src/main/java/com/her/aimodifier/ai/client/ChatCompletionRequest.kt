package com.her.aimodifier.ai.client

import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容请求体（v1/chat/completions）。
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0.7f,
    val stream: Boolean = true,
    val max_tokens: Int? = null,
    val timeout_ms: Long? = null
)

@Serializable
data class ChatMessage(
    val role: String,        // system / user / assistant / tool
    val content: String,
    val name: String? = null,
    val tool_calls: List<ToolCall>? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val arguments: String   // JSON string
)
