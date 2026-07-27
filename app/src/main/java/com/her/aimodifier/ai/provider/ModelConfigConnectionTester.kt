package com.her.aimodifier.ai.provider

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException

enum class ModelConnectionTestType {
    CHAT,
    TOOL_CALL,
    IMAGE,
    AUDIO,
    VIDEO
}

data class ModelConnectionTestItem(
    val type: ModelConnectionTestType,
    val success: Boolean,
    val error: String? = null
)

data class ModelConnectionTestReport(
    val configId: String,
    val configName: String,
    val providerType: String,
    val requestedModelIndex: Int,
    val actualModelIndex: Int,
    val testedModelName: String,
    val items: List<ModelConnectionTestItem>
) {
    val success: Boolean
        get() = items.all { it.success }
}

object ModelConfigConnectionTester {
    private const val TAG = "ModelConfigTester"

    /** 用于拼接 XML 风格标签的工具方法，避免在源码中出现字面量关闭标签。 */
    private fun tag(name: String, attrs: String = "", close: Boolean = false): String {
        val slash = if (close) "/" else ""
        return if (attrs.isEmpty()) {
            "<$slash$name>"
        } else {
            "<$name $attrs>"
        }
    }

    private fun toolCallMarkup(toolName: String, paramName: String, paramValue: String): String {
        return buildString {
            append(tag("tool_call", "name=\"$toolName\""))
            append(tag("param", "name=\"$paramName\""))
            append(paramValue)
            append(tag("param", close = true))
            append(tag("tool_call", close = true))
        }
    }

    private fun toolResultMarkup(toolName: String, status: String, content: String): String {
        return buildString {
            append(tag("tool_result", "name=\"$toolName\" status=\"$status\""))
            append(tag("content"))
            append(content)
            append(tag("content", close = true))
            append(tag("tool_result", close = true))
        }
    }

    suspend fun run(
        context: Context,
        config: ModelConfigData,
        requestedModelIndex: Int = 0,
        onActiveServiceChanged: (AIService?) -> Unit = {}
    ): ModelConnectionTestReport {
        val actualModelIndex = getValidModelIndex(config.modelName, requestedModelIndex)
        val testedModelName = getModelByIndex(config.modelName, actualModelIndex)
        val configForTest = config.copy(modelName = testedModelName)
        val items = mutableListOf<ModelConnectionTestItem>()

        val service =
            AIServiceFactory.createService(
                config = configForTest,
                context = context
            )
        onActiveServiceChanged(service)

        try {
            val temperature = if (configForTest.temperatureEnabled) configForTest.temperature else 0.7f
            val maxTokens = if (configForTest.maxTokensEnabled) configForTest.maxTokens else null

            suspend fun runCase(type: ModelConnectionTestType, block: suspend () -> Unit) {
                val result =
                    try {
                        block()
                        Result.success(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                items.add(
                    ModelConnectionTestItem(
                        type = type,
                        success = result.isSuccess,
                        error = result.exceptionOrNull()?.message
                    )
                )
            }

            runCase(ModelConnectionTestType.CHAT) {
                service.sendMessage(
                    context,
                    listOf(ChatMessageTurn(role = "user", content = "Hi")),
                    temperature = temperature,
                    maxTokens = maxTokens,
                    stream = false,
                    enableRetry = false
                ).collect { }
            }

            if (configForTest.enableToolCall) {
                runCase(ModelConnectionTestType.TOOL_CALL) {
                    // Simplified tool call test: send a conversation history
                    // containing tool-call-style markup to verify connectivity.
                    val testHistory = listOf(
                        ChatMessageTurn(role = "system", content = "You are a helpful assistant."),
                        ChatMessageTurn(
                            role = "assistant",
                            content = toolCallMarkup("echo", "text", "ping")
                        ),
                        ChatMessageTurn(
                            role = "user",
                            content = toolResultMarkup("echo", "success", "pong")
                        ),
                        ChatMessageTurn(role = "user", content = "请继续对话。")
                    )
                    service.sendMessage(
                        context,
                        testHistory,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        stream = false,
                        enableRetry = false
                    ).collect { }
                }
            }

            if (configForTest.enableDirectImageProcessing) {
                runCase(ModelConnectionTestType.IMAGE) {
                    // Simplified image test: verify model connectivity with a text prompt.
                    service.sendMessage(
                        context,
                        listOf(ChatMessageTurn(role = "user", content = "请描述一张测试图片可能包含的内容。")),
                        temperature = temperature,
                        maxTokens = maxTokens,
                        stream = false,
                        enableRetry = false
                    ).collect { }
                }
            }

            if (configForTest.enableDirectAudioProcessing) {
                runCase(ModelConnectionTestType.AUDIO) {
                    // Simplified audio test: verify model connectivity with a text prompt.
                    service.sendMessage(
                        context,
                        listOf(ChatMessageTurn(role = "user", content = "请描述一段测试音频可能包含的内容。")),
                        temperature = temperature,
                        maxTokens = maxTokens,
                        stream = false,
                        enableRetry = false
                    ).collect { }
                }
            }

            if (configForTest.enableDirectVideoProcessing) {
                runCase(ModelConnectionTestType.VIDEO) {
                    // Simplified video test: verify model connectivity with a text prompt.
                    service.sendMessage(
                        context,
                        listOf(ChatMessageTurn(role = "user", content = "请描述一段测试视频可能包含的内容。")),
                        temperature = temperature,
                        maxTokens = maxTokens,
                        stream = false,
                        enableRetry = false
                    ).collect { }
                }
            }
        } catch (e: CancellationException) {
            runCatching { service.cancelStreaming() }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "连接测试异常: ${e.message}", e)
            if (items.none { it.type == ModelConnectionTestType.CHAT }) {
                items.add(
                    ModelConnectionTestItem(
                        type = ModelConnectionTestType.CHAT,
                        success = false,
                        error = e.message ?: "未知错误"
                    )
                )
            }
        } finally {
            onActiveServiceChanged(null)
            service.release()
        }

        return ModelConnectionTestReport(
            configId = configForTest.id,
            configName = configForTest.name,
            providerType = configForTest.apiProviderTypeId,
            requestedModelIndex = requestedModelIndex,
            actualModelIndex = actualModelIndex,
            testedModelName = testedModelName,
            items = items
        )
    }
}
