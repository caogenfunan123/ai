package com.her.aimodifier.ai.provider

import java.security.SecureRandom
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具参数模式定义（用于定义工具的参数结构）。
 */
data class ToolParameterSchema(
    val name: String,
    val type: String = "string",
    val description: String,
    val required: Boolean = true,
    val default: String? = null
)

/**
 * 工具提示词数据类，表示单个工具的完整提示词信息。
 */
data class ToolPrompt(
    val name: String,
    val description: String,
    val parameters: String = "",
    val parametersStructured: List<ToolParameterSchema>? = null,
    val details: String = "",
    val notes: String = ""
)

/**
 * 结构化工具调用桥接器。
 *
 * 负责：
 * - 解析聊天历史中的 XML 工具调用标记（<tool...>、<tool_result...>）
 * - 转换为各家 Provider 特定格式（OpenAI tool_calls、Claude tool_use/tool_result、Gemini functionCall/functionResponse）
 * - 支持从流式响应中提取工具调用并还原为 XML
 * - compileHistoryForProvider 方法将相邻同类型消息合并为块
 *
 * 适配说明：
 * - 使用 [ChatMessageTurn]（基于 role 字符串）而非 PromptTurn（基于 kind 枚举）
 * - 内联了 ChatMarkupRegex 的正则模式与 ChatUtils 的辅助方法
 * - 不依赖任何外部工具类或媒体池
 */
internal object StructuredToolCallBridge {
    private const val TOOL_TAG_SUFFIX_REGEX_SOURCE = "[A-Za-z0-9_]+"
    private const val TOOL_TAG_NAME_REGEX_SOURCE =
        "tool(?:_(?!result(?:_|\\b))$TOOL_TAG_SUFFIX_REGEX_SOURCE)?"
    private const val TOOL_RESULT_TAG_NAME_REGEX_SOURCE =
        "tool_result(?:_${TOOL_TAG_SUFFIX_REGEX_SOURCE})?"

    private enum class ProviderHistoryBlockType {
        ASSISTANT,
        USER_INPUT,
        TOOL_RESULT
    }

    private data class ToolResultRecord(
        val name: String?,
        val content: String
    )

    // ---- 内联的 ChatMarkupRegex 正则模式 ----

    private val toolStartTagRegex =
        Regex("<(?:$TOOL_TAG_NAME_REGEX_SOURCE)\\b", RegexOption.IGNORE_CASE)
    private val toolResultStartTagRegex =
        Regex("<(?:$TOOL_RESULT_TAG_NAME_REGEX_SOURCE)\\b", RegexOption.IGNORE_CASE)

    private val toolCallPattern = Regex(
        """<($TOOL_TAG_NAME_REGEX_SOURCE)\b[^>]*name="([^"]+)"[^>]*>([\s\S]*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val toolParamPattern = Regex("<param\\s+name=\"([^\"]+)\">([\\s\\S]*?)</param>")

    private val toolResultAnyPattern = Regex(
        """<($TOOL_RESULT_TAG_NAME_REGEX_SOURCE)\b[^>]*>([\s\S]*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val contentTag = Regex(
        "<content>([\\s\\S]*?)</content>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val nameAttr = Regex("name\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private val randomTagCodeChars =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val randomTagCodeSource = SecureRandom()

    // ---- 内联的 ChatMarkupRegex 方法 ----

    private fun containsToolTag(content: String): Boolean =
        toolStartTagRegex.containsMatchIn(content)

    private fun containsToolResultTag(content: String): Boolean =
        toolResultStartTagRegex.containsMatchIn(content)

    private fun containsAnyToolLikeTag(content: String): Boolean =
        containsToolTag(content) || containsToolResultTag(content)

    private fun generateRandomToolTagName(): String = "tool_${generateRandomTagCode()}"

    private fun generateRandomTagCode(length: Int = 4): String =
        buildString(length) {
            repeat(length) {
                append(randomTagCodeChars[randomTagCodeSource.nextInt(randomTagCodeChars.length)])
            }
        }

    // ---- 内联的 ChatUtils 方法 ----

    private fun removeThinkingContent(content: String): String {
        val thinkPattern =
            "<think(?:ing)?>.*?(</think(?:ing)?>|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL)
        val searchPattern = "<search>.*?(</search>|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL)
        return content.replace(thinkPattern, "").replace(searchPattern, "").trim()
    }

    private fun extractJson(response: String): String {
        var text = response.trim()
        if (text.startsWith("```")) {
            val lines = text.lines()
            text = lines.drop(1).dropLast(1).joinToString("\n").trim()
        }
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        return if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
            text.substring(firstBrace, lastBrace + 1)
        } else {
            text
        }
    }

    private fun extractJsonArray(response: String): String {
        var text = response.trim()
        if (text.startsWith("```")) {
            val lines = text.lines()
            text = lines.drop(1).dropLast(1).joinToString("\n").trim()
        }
        val firstBracket = text.indexOf('[')
        val lastBracket = text.lastIndexOf(']')
        return if (firstBracket != -1 && lastBracket != -1 && firstBracket < lastBracket) {
            text.substring(firstBracket, lastBracket + 1)
        } else {
            text
        }
    }

    // ---- 公共 API ----

    fun buildToolsJson(toolPrompts: List<ToolPrompt>?): String? {
        if (toolPrompts.isNullOrEmpty()) {
            return null
        }
        val tools = buildToolDefinitions(toolPrompts)
        return if (tools.length() > 0) tools.toString() else null
    }

    fun buildToolsArray(toolPrompts: List<ToolPrompt>?): JSONArray {
        if (toolPrompts.isNullOrEmpty()) {
            return JSONArray()
        }
        return buildToolDefinitions(toolPrompts)
    }

    fun buildMessagesJson(
        history: List<ChatMessageTurn>,
        preserveThinkInHistory: Boolean
    ): String {
        return buildStructuredMessages(history, preserveThinkInHistory).toString()
    }

    fun buildMnnChatHistory(
        history: List<ChatMessageTurn>,
        preserveThinkInHistory: Boolean
    ): List<Pair<String, String>> {
        val messages = buildStructuredMessages(history, preserveThinkInHistory)
        val compiledHistory = ArrayList<Pair<String, String>>(messages.length())
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role", "").trim()
            val contentValue = message.opt("content")
            val content =
                when (contentValue) {
                    null,
                    JSONObject.NULL -> ""
                    is String -> contentValue
                    else -> contentValue.toString()
                }
            val isPlainRoleContentMessage =
                role.isNotEmpty() &&
                    message.length() == 2 &&
                    message.has("role") &&
                    message.has("content") &&
                    (contentValue == null || contentValue == JSONObject.NULL || contentValue is String)
            if (isPlainRoleContentMessage) {
                compiledHistory.add(role to content)
            } else {
                compiledHistory.add("json" to message.toString())
            }
        }
        return compiledHistory
    }

    /**
     * 将历史合并为 Provider 友好的块结构：
     * - system 消息保持原样
     * - 相邻的 assistant（含工具调用）合并为一个 assistant 块
     * - 相邻的 tool 消息合并为一个 tool_result 块（或 user 块，当 [useToolCall] 为 false）
     * - 相邻的 user 消息合并为一个 user 块
     */
    fun compileHistoryForProvider(
        history: List<ChatMessageTurn>,
        useToolCall: Boolean
    ): List<ChatMessageTurn> {
        if (history.isEmpty()) {
            return history
        }

        val compiled = mutableListOf<ChatMessageTurn>()
        var currentBlockType: ProviderHistoryBlockType? = null
        var currentContent = StringBuilder()

        fun flushCurrentBlock() {
            val blockType = currentBlockType ?: return
            val role = when (blockType) {
                ProviderHistoryBlockType.ASSISTANT -> "assistant"
                ProviderHistoryBlockType.USER_INPUT -> "user"
                ProviderHistoryBlockType.TOOL_RESULT ->
                    if (useToolCall) "tool" else "user"
            }
            compiled.add(
                ChatMessageTurn(
                    role = role,
                    content = currentContent.toString().trim(),
                    name = null
                )
            )
            currentBlockType = null
            currentContent = StringBuilder()
        }

        fun appendToBlock(blockType: ProviderHistoryBlockType, turn: ChatMessageTurn) {
            if (currentBlockType != blockType) {
                flushCurrentBlock()
                currentBlockType = blockType
            }
            val trimmedContent = turn.content.trim()
            if (trimmedContent.isNotEmpty()) {
                if (currentContent.isNotEmpty()) {
                    currentContent.append("\n")
                }
                currentContent.append(trimmedContent)
            }
        }

        fun blockTypeForRole(role: String): ProviderHistoryBlockType? {
            return when (role.lowercase()) {
                "system" -> null // system 单独处理，不进入块
                "assistant" -> ProviderHistoryBlockType.ASSISTANT
                "tool" -> ProviderHistoryBlockType.TOOL_RESULT
                "user" -> ProviderHistoryBlockType.USER_INPUT
                else -> ProviderHistoryBlockType.USER_INPUT
            }
        }

        for (turn in history) {
            val role = turn.role.lowercase()
            if (role == "system") {
                flushCurrentBlock()
                compiled.add(turn)
            } else {
                val blockType = blockTypeForRole(role)
                if (blockType == null) {
                    flushCurrentBlock()
                    compiled.add(turn)
                } else {
                    appendToBlock(blockType, turn)
                }
            }
        }

        flushCurrentBlock()
        return compiled
    }

    /**
     * 将可能的 JSON 工具调用载荷还原为 XML 标记。
     * 若内容已包含工具标签则原样返回。
     */
    fun convertToolCallPayloadToXml(content: String): String {
        if (content.isBlank()) {
            return content
        }

        if (containsAnyToolLikeTag(content)) {
            return content
        }

        val toolCalls = parsePossibleToolCallsFromText(content) ?: return content
        val xml = convertToolCallsToXml(toolCalls)
        return if (xml.isBlank()) content else xml
    }

    // ---- 内部实现 ----

    private fun buildStructuredMessages(
        history: List<ChatMessageTurn>,
        preserveThinkInHistory: Boolean
    ): JSONArray {
        val mergedHistory = compileHistoryForProvider(history, useToolCall = true)
        val messagesArray = JSONArray()
        var queuedAssistantToolText: String? = null
        var queuedToolCalls = JSONArray()
        val queuedToolCallIds = mutableListOf<String>()
        val openToolCallIds = mutableListOf<String>()
        var nextToolCallOrdinal = 0

        fun appendQueuedAssistantToolText(text: String) {
            if (text.isBlank()) return
            queuedAssistantToolText =
                if (queuedAssistantToolText.isNullOrBlank()) {
                    text
                } else {
                    queuedAssistantToolText + "\n" + text
                }
        }

        fun queueToolCalls(textContent: String, toolCalls: JSONArray) {
            appendQueuedAssistantToolText(textContent)
            for (i in 0 until toolCalls.length()) {
                val sourceToolCall = toolCalls.optJSONObject(i) ?: continue
                val toolCall = JSONObject(sourceToolCall.toString())
                val callId = generatedToolCallId(nextToolCallOrdinal++)
                toolCall.put("id", callId)
                queuedToolCalls.put(toolCall)
                queuedToolCallIds.add(callId)
            }
        }

        fun emitQueuedToolCallsIfNeeded() {
            if (queuedToolCalls.length() == 0) return

            messagesArray.put(
                JSONObject().apply {
                    put("role", "assistant")
                    put(
                        "content",
                        if (!queuedAssistantToolText.isNullOrBlank()) {
                            queuedAssistantToolText
                        } else {
                            JSONObject.NULL
                        }
                    )
                    put("tool_calls", queuedToolCalls)
                }
            )

            openToolCallIds.addAll(queuedToolCallIds)
            queuedAssistantToolText = null
            queuedToolCalls = JSONArray()
            queuedToolCallIds.clear()
        }

        fun flushOpenToolCallsAsCancelled() {
            emitQueuedToolCallsIfNeeded()
            if (openToolCallIds.isEmpty()) return

            for (toolCallId in openToolCallIds) {
                messagesArray.put(
                    JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", toolCallId)
                        put("content", "User cancelled")
                    }
                )
            }
            openToolCallIds.clear()
        }

        for (turn in mergedHistory) {
            val role = turn.role.lowercase()
            val content =
                if (!preserveThinkInHistory && role == "assistant") {
                    removeThinkingContent(turn.content)
                } else {
                    turn.content
                }

            when (role) {
                "system" -> {
                    flushOpenToolCallsAsCancelled()
                    messagesArray.put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", nonEmptyContent(content))
                        }
                    )
                }

                "user" -> {
                    flushOpenToolCallsAsCancelled()
                    messagesArray.put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", nonEmptyContent(content))
                        }
                    )
                }

                "assistant" -> {
                    val (textContent, parsedToolCalls) = parseXmlToolCalls(content)
                    val toolCalls =
                        if (parsedToolCalls != null) {
                            wrapPackageToolCallsWithProxy(parsedToolCalls)
                        } else {
                            null
                        }

                    if (toolCalls != null && toolCalls.length() > 0) {
                        flushOpenToolCallsAsCancelled()
                        queueToolCalls(textContent, toolCalls)
                    } else {
                        flushOpenToolCallsAsCancelled()
                        messagesArray.put(
                            JSONObject().apply {
                                put("role", "assistant")
                                put("content", nonEmptyContent(content))
                            }
                        )
                    }
                }

                "tool" -> {
                    emitQueuedToolCallsIfNeeded()
                    val (textContent, toolResults) = parseXmlToolResults(content)
                    val resultsList = toolResults ?: emptyList()

                    if (resultsList.isNotEmpty() && openToolCallIds.isNotEmpty()) {
                        val validCount = minOf(resultsList.size, openToolCallIds.size)
                        repeat(validCount) { index ->
                            val result = resultsList[index]
                            val toolMessage = JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", openToolCallIds[index])
                                if (!result.name.isNullOrBlank()) {
                                    put("name", result.name)
                                }
                                put("content", nonEmptyContent(result.content))
                            }
                            messagesArray.put(toolMessage)
                        }
                        repeat(validCount) {
                            openToolCallIds.removeAt(0)
                        }
                        if (textContent.isNotBlank()) {
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", textContent)
                                }
                            )
                        }
                    } else {
                        flushOpenToolCallsAsCancelled()
                        messagesArray.put(
                            JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    when {
                                        textContent.isNotBlank() -> textContent
                                        else -> nonEmptyContent(content)
                                    }
                                )
                            }
                        )
                    }
                }

                else -> {
                    flushOpenToolCallsAsCancelled()
                    messagesArray.put(
                        JSONObject().apply {
                            put("role", turn.role)
                            put("content", nonEmptyContent(content))
                        }
                    )
                }
            }
        }

        flushOpenToolCallsAsCancelled()
        return messagesArray
    }

    private fun nonEmptyContent(content: String): String {
        return if (content.isBlank()) "[Empty]" else content
    }

    private fun buildToolDefinitions(toolPrompts: List<ToolPrompt>): JSONArray {
        val tools = JSONArray()

        for (tool in toolPrompts) {
            tools.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    val fullDescription = if (tool.details.isNotEmpty()) {
                        "${tool.description}\n${tool.details}"
                    } else {
                        tool.description
                    }
                    put("description", fullDescription)
                    put(
                        "parameters",
                        buildSchemaFromStructured(tool.parametersStructured ?: emptyList())
                    )
                })
            })
        }

        return tools
    }

    private fun buildSchemaFromStructured(params: List<ToolParameterSchema>): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
        }

        val properties = JSONObject()
        val required = JSONArray()

        for (param in params) {
            properties.put(param.name, JSONObject().apply {
                put("type", param.type)
                put("description", param.description)
                if (param.default != null) {
                    put("default", param.default)
                }
            })

            if (param.required) {
                required.put(param.name)
            }
        }

        schema.put("properties", properties)
        // OpenAI-style tool schemas must keep `required` as an array, including an empty one.
        schema.put("required", required)

        return schema
    }

    private fun convertToolCallsToXml(toolCalls: JSONArray): String {
        val xml = StringBuilder()

        for (i in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(i) ?: continue
            val function = toolCall.optJSONObject("function") ?: continue
            val name = function.optString("name", "")
            if (name.isBlank()) {
                continue
            }

            val argumentsRaw = function.optString("arguments", "")
            val paramsObj = kotlin.runCatching {
                JSONObject(argumentsRaw)
            }.getOrNull()

            val toolTagName = generateRandomToolTagName()
            xml.append("\n<")
                .append(toolTagName)
                .append(" name=\"")
                .append(name)
                .append("\">")

            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = paramsObj.opt(key)
                    xml.append("\n<param name=\"")
                        .append(key)
                        .append("\">")
                        .append(escapeXml(value?.toString() ?: ""))
                        .append("</param>")
                }
            } else if (argumentsRaw.isNotBlank()) {
                xml.append("\n<param name=\"_raw_arguments\">")
                    .append(escapeXml(argumentsRaw))
                    .append("</param>")
            }

            xml.append("\n</")
                .append(toolTagName)
                .append(">\n")
        }

        return xml.toString().trimEnd()
    }

    private fun parsePossibleToolCallsFromText(content: String): JSONArray? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val candidates = LinkedHashSet<String>()
        candidates.add(trimmed)

        val extractedJson = extractJson(trimmed).trim()
        if (extractedJson.isNotBlank()) {
            candidates.add(extractedJson)
        }

        val extractedArray = extractJsonArray(trimmed).trim()
        if (extractedArray.isNotBlank()) {
            candidates.add(extractedArray)
        }

        val fencedRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fencedRegex.findAll(trimmed).forEach { match ->
            val fenced = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (fenced.isNotBlank()) {
                candidates.add(fenced)
            }
        }

        for (candidate in candidates) {
            val fromObject = kotlin.runCatching {
                extractToolCallsFromAny(JSONObject(candidate))
            }.getOrNull()
            if (fromObject != null && fromObject.length() > 0) {
                return fromObject
            }

            val fromArray = kotlin.runCatching {
                extractToolCallsFromAny(JSONArray(candidate))
            }.getOrNull()
            if (fromArray != null && fromArray.length() > 0) {
                return fromArray
            }
        }

        return null
    }

    private fun extractToolCallsFromAny(root: JSONObject): JSONArray? {
        root.optJSONArray("tool_calls")?.let { array ->
            val normalized = normalizeToolCalls(array)
            if (normalized.length() > 0) {
                return normalized
            }
        }

        root.optJSONObject("function_call")?.let { functionCall ->
            val normalized = normalizeSingleToolCall(functionCall, 0)
            if (normalized != null) {
                return JSONArray().put(normalized)
            }
        }

        if (root.optString("type", "") == "function_call") {
            val normalized = normalizeSingleToolCall(root, 0)
            if (normalized != null) {
                return JSONArray().put(normalized)
            }
        }

        root.optJSONArray("output")?.let { outputArray ->
            val normalized = normalizeToolCalls(outputArray)
            if (normalized.length() > 0) {
                return normalized
            }
        }

        return null
    }

    private fun extractToolCallsFromAny(root: JSONArray): JSONArray? {
        val normalized = normalizeToolCalls(root)
        return if (normalized.length() > 0) normalized else null
    }

    private fun normalizeToolCalls(source: JSONArray): JSONArray {
        val normalized = JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val normalizedCall = normalizeSingleToolCall(item, i) ?: continue
            normalized.put(normalizedCall)
        }
        return normalized
    }

    private fun normalizeSingleToolCall(raw: JSONObject, index: Int): JSONObject? {
        val functionObject = raw.optJSONObject("function")
        val functionCallObject = raw.optJSONObject("function_call")

        val name = when {
            functionObject != null -> functionObject.optString("name", "")
            raw.optString("name", "").isNotBlank() -> raw.optString("name", "")
            functionCallObject != null -> functionCallObject.optString("name", "")
            else -> ""
        }
        if (name.isBlank()) {
            return null
        }

        val argumentsValue: Any? = when {
            functionObject != null && functionObject.has("arguments") -> functionObject.opt("arguments")
            raw.has("arguments") -> raw.opt("arguments")
            functionCallObject != null && functionCallObject.has("arguments") -> functionCallObject.opt("arguments")
            else -> null
        }

        val arguments = when (argumentsValue) {
            is JSONObject, is JSONArray -> argumentsValue.toString()
            is String -> if (argumentsValue.isBlank()) "{}" else argumentsValue
            null -> "{}"
            else -> argumentsValue.toString()
        }

        val rawId = raw.optString("id", "")
            .ifBlank { raw.optString("call_id", "") }
            .ifBlank { "call_${sanitizeToolCallId(name)}_$index" }
        val callId = sanitizeToolCallId(rawId)

        return JSONObject().apply {
            put("id", callId)
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("arguments", arguments)
            })
        }
    }

    private fun parseXmlToolCalls(content: String): Pair<String, JSONArray?> {
        val matches = toolCallPattern.findAll(content)
        if (!matches.any()) {
            return content to null
        }

        val toolCalls = JSONArray()
        var textContent = content
        var callIndex = 0

        matches.forEach { match ->
            val toolName = match.groupValues[2]
            val toolBody = match.groupValues[3]

            val params = JSONObject()
            toolParamPattern.findAll(toolBody).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                params.put(paramName, paramValue)
            }

            val toolNamePart = sanitizeToolCallId(toolName)
            val hashPart = stableIdHashPart("${toolName}:${params}")
            val callId = sanitizeToolCallId("call_${toolNamePart}_${hashPart}_$callIndex")

            toolCalls.put(JSONObject().apply {
                put("id", callId)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", params.toString())
                })
            })

            callIndex++
            textContent = textContent.replace(match.value, "")
        }

        return textContent.trim() to toolCalls
    }

    private fun wrapPackageToolCallsWithProxy(toolCalls: JSONArray): JSONArray {
        val wrappedToolCalls = JSONArray()

        for (i in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(i) ?: continue
            val function = toolCall.optJSONObject("function")
            if (function == null) {
                wrappedToolCalls.put(toolCall)
                continue
            }

            val toolName = function.optString("name", "")
            if (!toolName.contains(":") || toolName == "package_proxy") {
                wrappedToolCalls.put(toolCall)
                continue
            }

            val rawArguments = function.optString("arguments", "{}")
            val originalArguments = JSONObject(if (rawArguments.isBlank()) "{}" else rawArguments)
            val wrappedFunction = JSONObject(function.toString()).apply {
                put("name", "package_proxy")
                put(
                    "arguments",
                    JSONObject().apply {
                        put("tool_name", toolName)
                        put("params", originalArguments)
                    }.toString()
                )
            }

            wrappedToolCalls.put(JSONObject(toolCall.toString()).apply {
                put("function", wrappedFunction)
            })
        }

        return wrappedToolCalls
    }

    private fun parseXmlToolResults(content: String): Pair<String, List<ToolResultRecord>?> {
        val matches = toolResultAnyPattern.findAll(content)
        if (!matches.any()) {
            return content to null
        }

        val results = mutableListOf<ToolResultRecord>()
        var textContent = content

        matches.forEach { match ->
            val fullContent = match.groupValues[2].trim()
            val contentMatch = contentTag.find(fullContent)
            val resultContent = if (contentMatch != null) {
                contentMatch.groupValues[1].trim()
            } else {
                fullContent
            }
            val resultName = nameAttr.find(match.value)?.groupValues?.getOrNull(1)
            results.add(ToolResultRecord(resultName, resultContent))
            textContent = textContent.replace(match.value, "").trim()
        }

        return textContent.trim() to results
    }

    private object XmlEscaper {
        fun escape(text: String): String {
            return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }

        fun unescape(text: String): String {
            return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
    }

    private fun escapeXml(text: String): String {
        return XmlEscaper.escape(text)
    }

    private fun sanitizeToolCallId(raw: String): String {
        val output = buildString(raw.length) {
            raw.forEach { ch ->
                if (ch.isLetterOrDigit() || ch == '_' || ch == '-') {
                    append(ch)
                } else {
                    append('_')
                }
            }
        }.replace(Regex("_+"), "_").trim('_')
        return if (output.isEmpty()) "call" else output
    }

    private fun generatedToolCallId(ordinal: Int): String {
        val raw = "${stableIdHashPart("tool_call:$ordinal")}_$ordinal"
        val cleaned = raw.filter { it.isLetterOrDigit() }
        if (cleaned.isEmpty()) return "call00000"
        if (cleaned.length == 9) return cleaned
        if (cleaned.length > 9) return cleaned.takeLast(9)
        val filler = stableIdHashPart(raw)
        return (cleaned + filler + "000000000").take(9)
    }

    private fun stableIdHashPart(raw: String): String {
        val hash = raw.hashCode()
        val positive = if (hash == Int.MIN_VALUE) 0 else abs(hash)
        val base = positive.toString(36).filter { it.isLetterOrDigit() }.lowercase()
        return if (base.isEmpty()) "0" else base
    }
}
