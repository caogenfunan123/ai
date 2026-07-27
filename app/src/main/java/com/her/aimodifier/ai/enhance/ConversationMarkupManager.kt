package com.her.aimodifier.ai.enhance

import java.security.SecureRandom

/**
 * 工具执行结果数据类，用于在 [ConversationMarkupManager] 中传递工具调用的产物。
 *
 * @property toolName 工具名称
 * @property success 是否执行成功
 * @property result 结果内容（成功时为返回值，失败时为详细信息）
 * @property error 失败时的错误消息
 */
data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val result: Any = "",
    val error: String? = null
)

/**
 * 管理 AI 对话中使用的标记元素。
 *
 * 该类负责生成标准化的 XML 格式状态消息、工具调用格式和工具结果，
 * 用于在对话中展示。
 *
 * 适配说明：
 * - 内联实现随机标签名生成（避免模型学会固定模式），不依赖 ChatMarkupRegex
 * - 用常量替代 ToolExecutionLimits
 * - 用硬编码中文字符串替代 R.string.xxx
 * - 简化处理，不依赖 MediaLinkParser（图片链接拆分逻辑改为直接透传）
 */
class ConversationMarkupManager {

    companion object {
        private const val TOOL_RESULT_TRUNCATION_SUFFIX =
            "\n[工具结果过长，已截断]"

        /** 最终发送给模型的工具结果消息最大字符数（替代 ToolExecutionLimits） */
        private const val MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS = 16000

        // 随机标签名生成所用的字符集与随机源
        private val RANDOM_TAG_CODE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val RANDOM_TAG_SOURCE = SecureRandom()

        /**
         * 生成一个随机的工具结果标签名（如 `tool_result_aB3x`）。
         *
         * 通过随机化标签名，避免模型在长对话中学会固定的标签模式，
         * 从而降低其绕过工具结果格式约束的可能性。
         */
        fun generateRandomToolResultTagName(): String =
            "tool_result_${generateRandomTagCode()}"

        private fun generateRandomTagCode(length: Int = 4): String =
            buildString(length) {
                repeat(length) {
                    append(RANDOM_TAG_CODE_CHARS[RANDOM_TAG_SOURCE.nextInt(RANDOM_TAG_CODE_CHARS.length)])
                }
            }

        /**
         * 为工具创建一个 'error' 状态的标记元素。
         *
         * @param toolName 产生错误的工具名称
         * @param errorMessage 错误消息
         * @return 格式化后的状态元素
         */
        fun createToolErrorStatus(toolName: String, errorMessage: String): String {
            return createToolResultXml(
                toolName = toolName,
                status = "error",
                content = "<content><error>${errorMessage}</error></content>"
            )
        }

        /**
         * 创建一个 'warning' 状态的标记元素。
         *
         * @param warningMessage 要显示的警告消息
         * @return 格式化后的状态元素
         */
        fun createWarningStatus(warningMessage: String): String {
            return "<status type=\"warning\">$warningMessage</status>"
        }

        /**
         * 格式化工具结果消息以便发送给 AI。
         *
         * 成功时，将结果包裹在带有随机标签名的 XML 中；
         * 失败时，将错误信息和详情合并后以 error 状态包裹。
         *
         * @param result 工具执行结果
         * @return 格式化后的工具结果消息
         */
        fun formatToolResultForMessage(result: ToolResult): String {
            return if (result.success) {
                // 简化处理：不拆分图片链接，直接使用原始 payload
                val toolPayload = result.result.toString()
                createBoundedToolResultXml(
                    toolName = result.toolName,
                    status = "success",
                    rawPayload = toolPayload
                ) { payload ->
                    "<content>$payload</content>"
                }
            } else {
                val errorPayload = buildString {
                    val message = result.error.orEmpty().trim()
                    val detail = result.result.toString().trim()
                    append(message)
                    if (detail.isNotEmpty()) {
                        if (message.isNotEmpty()) {
                            append("\n\n")
                        }
                        append(detail)
                    }
                }
                createBoundedToolResultXml(
                    toolName = result.toolName,
                    status = "error",
                    rawPayload = errorPayload
                ) { payload ->
                    "<content><error>$payload</error></content>"
                }
            }
        }

        /**
         * 将多个工具结果组装为一条带边界限制的消息。
         *
         * 当累计长度超过 [MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS] 时停止追加。
         *
         * @param results 工具结果列表
         * @return 组装后的消息字符串（空列表返回空字符串）
         */
        fun buildBoundedToolResultMessage(results: List<ToolResult>): String {
            if (results.isEmpty()) {
                return ""
            }

            val maxChars = MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS
            val separator = "\n"
            val builder = StringBuilder()

            for (result in results) {
                val formatted = formatToolResultForMessage(result)
                val additionalLength =
                    (if (builder.isEmpty()) 0 else separator.length) + formatted.length
                if (builder.length + additionalLength > maxChars) {
                    break
                }
                if (builder.isNotEmpty()) {
                    builder.append(separator)
                }
                builder.append(formatted)
            }

            return builder.toString()
        }

        /**
         * 创建一条表示"发现多个工具调用但只会处理其中一个"的警告消息。
         *
         * @param toolName 将被处理的工具名称
         * @return 格式化后的警告消息
         */
        fun createMultipleToolsWarning(toolName: String): String {
            // 硬编码中文字符串，替代 R.string.conversation_markup_multiple_tools_warning
            val warningMessage = "检测到多个工具调用，仅会执行第一个工具: $toolName"
            return createWarningStatus(warningMessage)
        }

        /**
         * 创建工具不可用的错误消息。
         *
         * @param toolName 不可用的工具名称
         * @param details 可选的详细错误消息
         * @return 格式化后的错误消息
         */
        fun createToolNotAvailableError(toolName: String, details: String? = null): String {
            val errorMessage = details ?: "工具 `$toolName` 不可用。"
            return createToolErrorStatus(toolName, errorMessage)
        }

        private fun createToolResultXml(toolName: String, status: String, content: String): String {
            val tagName = generateRandomToolResultTagName()
            return """<$tagName name="$toolName" status="$status">$content</$tagName>""".trimIndent()
        }

        private fun createBoundedToolResultXml(
            toolName: String,
            status: String,
            rawPayload: String,
            bodyBuilder: (String) -> String
        ): String {
            val emptyXml =
                createToolResultXml(
                    toolName = toolName,
                    status = status,
                    content = bodyBuilder("")
                )
            val maxPayloadChars =
                (MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS - emptyXml.length)
                    .coerceAtLeast(0)
            val boundedPayload = truncatePayload(rawPayload, maxPayloadChars)
            return createToolResultXml(
                toolName = toolName,
                status = status,
                content = bodyBuilder(boundedPayload)
            )
        }

        private fun truncatePayload(payload: String, maxChars: Int): String {
            if (payload.length <= maxChars) {
                return payload
            }
            if (maxChars <= 0) {
                return ""
            }
            if (TOOL_RESULT_TRUNCATION_SUFFIX.length >= maxChars) {
                return TOOL_RESULT_TRUNCATION_SUFFIX.take(maxChars)
            }
            return payload
                .take(maxChars - TOOL_RESULT_TRUNCATION_SUFFIX.length)
                .trimEnd() + TOOL_RESULT_TRUNCATION_SUFFIX
        }
    }
}
