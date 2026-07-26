package com.her.aimodifier.ai.client

import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容流式响应 chunk（v1/chat/completions, stream=true）。
 *
 * 每个 chunk 仅包含 delta 中的增量内容。
 */
@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList()
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val delta: Delta = Delta(),
        val finish_reason: String? = null
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        val tool_calls: List<ToolCall>? = null
    )
}

/** /v1/models 接口返回 */
@Serializable
data class ModelsResponse(
    val data: List<ModelInfo> = emptyList()
)

@Serializable
data class ModelInfo(
    val id: String,
    val owned_by: String? = null
)
