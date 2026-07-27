package com.her.aimodifier.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.her.aimodifier.ai.client.ChatMessage
import com.her.aimodifier.ai.enhance.AiReference
import com.her.aimodifier.ai.enhance.ConversationRoundManager
import com.her.aimodifier.ai.enhance.FileBindingService
import com.her.aimodifier.ai.enhance.FunctionType
import com.her.aimodifier.ai.enhance.MultiServiceManager
import com.her.aimodifier.ai.enhance.ReferenceManager
import com.her.aimodifier.ai.memory.GlobalPromptMemoryManager
import com.her.aimodifier.ai.provider.StructuredToolCallBridge
import com.her.aimodifier.ai.routing.AiTaskRouter
import com.her.aimodifier.data.repository.AiConfigRepository
import com.her.aimodifier.data.repository.ChatSessionRepository
import com.her.aimodifier.data.repository.LocalModelRepository
import com.her.aimodifier.data.repository.ModelConfigConverter
import com.her.aimodifier.di.ServiceLocator
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.utils.JsonUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI 对话 ViewModel。
 *
 * 功能：
 * - 流式渲染回复（用户消息立即追加，AI 回复逐 token 累积）
 * - 暂停 / 继续 / 取消
 * - 强制思考模式（注入 system prompt）
 * - 会话持久化（messagesJson 入库）
 * - 模型选择（云端 + 本地 GGUF）
 * - TOOL_CALL 自动解析与 MCP 链式执行
 * - 工具调用日志实时流
 * - 远程模型列表获取与缓存
 */
class ChatViewModel(
    private val sessionRepository: ChatSessionRepository = ServiceLocator.chatSessionRepository,
    private val aiConfigRepository: AiConfigRepository = ServiceLocator.aiConfigRepository,
    private val localModelRepository: LocalModelRepository = ServiceLocator.localModelRepository,
    private val taskRouter: AiTaskRouter = ServiceLocator.aiTaskRouter,
    private val promptMemory: GlobalPromptMemoryManager = ServiceLocator.promptMemory,
    private val multiServiceManager: MultiServiceManager = ServiceLocator.multiServiceManager,
    private val conversationRoundManager: ConversationRoundManager =
        ServiceLocator.conversationRoundManager,
    private val fileBindingService: FileBindingService = ServiceLocator.fileBindingService
) : ViewModel() {

    data class UiMessage(
        val role: String,
        val content: String,
        val thinking: Boolean = false,
        val streaming: Boolean = false,
        val type: String = "message",
        val references: List<AiReference> = emptyList()
    )

    data class ModelInfo(
        val name: String,
        val isLocal: Boolean,
        val modelId: Long? = null
    )

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _canPause = MutableStateFlow(false)
    val canPause: StateFlow<Boolean> = _canPause.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelInfo?>(null)
    val currentModel: StateFlow<ModelInfo?> = _currentModel.asStateFlow()

    private val _isToolCallRunning = MutableStateFlow(false)
    val isToolCallRunning: StateFlow<Boolean> = _isToolCallRunning.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    private val _fetchModelsError = MutableStateFlow<String?>(null)
    val fetchModelsError: StateFlow<String?> = _fetchModelsError.asStateFlow()

    /** 最近一次响应中提取到的引用列表 */
    private val _references = MutableStateFlow<List<AiReference>>(emptyList())
    val references: StateFlow<List<AiReference>> = _references.asStateFlow()

    /** 最近一次文件绑定结果（diff 字符串） */
    private val _lastFileBindingDiff = MutableStateFlow<String?>(null)
    val lastFileBindingDiff: StateFlow<String?> = _lastFileBindingDiff.asStateFlow()

    private var currentSessionId: Long = -1L
    private var currentWorkspaceId: String? = null
    private var streamJob: Job? = null
    private var toolCallJob: Job? = null
    private var paused = false

    init {
        viewModelScope.launch {
            aiConfigRepository.observeAll().collect { updateAvailableModels() }
        }
        viewModelScope.launch {
            localModelRepository.observeAll().collect { updateAvailableModels() }
        }
    }

    fun bindSession(sessionId: Long, workspaceId: String?) {
        currentSessionId = sessionId
        currentWorkspaceId = workspaceId
        viewModelScope.launch {
            sessionRepository.findById(sessionId)?.let { _ ->
            }
            updateAvailableModels()
            // 将当前生效的配置注册到 MultiServiceManager
            registerEffectiveConfigToServiceManager()
            // 新会话开始时重置对话轮次
            conversationRoundManager.initializeNewConversation()
        }
    }

    /**
     * 把当前生效的配置注册到 [MultiServiceManager]，作为 CHAT 功能使用。
     * 这样后续可通过 multiServiceManager.acquireServiceForFunction 拿到带限流/并发的服务。
     * 同时为 SUMMARY / TITLE_GENERATION 注册同一份配置，便于辅助任务复用。
     */
    private suspend fun registerEffectiveConfigToServiceManager() {
        val effective = aiConfigRepository.findEffective(currentWorkspaceId) ?: return
        val (entity, _) = effective
        val modelConfig = ModelConfigConverter.toModelConfigData(entity)
        multiServiceManager.setFunctionConfig(FunctionType.CHAT, modelConfig)
        // 辅助功能复用 CHAT 配置（若需要差异化，可单独配置）
        multiServiceManager.setFunctionConfig(FunctionType.SUMMARY, modelConfig)
        multiServiceManager.setFunctionConfig(FunctionType.TITLE_GENERATION, modelConfig)
    }

    /**
     * 通过 [MultiServiceManager] 的租约系统生成会话标题。
     *
     * 使用完毕后通过 [MultiServiceManager.ServiceLease.close] 归还租约，
     * 触发引用计数递减，必要时释放底层服务资源。
     *
     * @param firstUserMessage 用于生成标题的首条用户消息
     * @return 生成的标题，失败时返回 null
     */
    suspend fun generateTitleWithLease(firstUserMessage: String): String? {
        if (firstUserMessage.isBlank()) return null
        return runCatching {
            val lease = multiServiceManager.acquireServiceForFunction(FunctionType.TITLE_GENERATION)
            try {
                val history = listOf(
                    com.her.aimodifier.ai.provider.ChatMessageTurn(
                        role = "system",
                        content = "请根据用户的首条消息生成一个简短的会话标题（不超过10个字），只输出标题文本，不要加引号或其他标记。"
                    ),
                    com.her.aimodifier.ai.provider.ChatMessageTurn(
                        role = "user",
                        content = firstUserMessage
                    )
                )
                val sb = StringBuilder()
                lease.service.sendMessage(
                    context = ServiceLocator.appContext,
                    chatHistory = history,
                    temperature = 0.3f,
                    maxTokens = 32,
                    stream = true,
                    systemPrompt = null
                ).collect { chunk -> sb.append(chunk) }
                sb.toString().trim().take(30)
            } finally {
                lease.close()
            }
        }.onFailure { e ->
            android.util.Log.w("ChatViewModel", "生成标题失败: ${e.message}")
        }.getOrNull()
    }

    /**
     * 通过 [MultiServiceManager] 的租约系统生成本轮对话的摘要。
     *
     * @param conversationText 待摘要的对话文本
     * @param customRules 自定义摘要规则（可选）
     * @return 生成的摘要文本，失败时返回 null
     */
    suspend fun generateSummaryWithLease(
        conversationText: String,
        customRules: String = ""
    ): String? {
        if (conversationText.isBlank()) return null
        return runCatching {
            val lease = multiServiceManager.acquireServiceForFunction(FunctionType.SUMMARY)
            try {
                val rules = if (customRules.isBlank()) {
                    "请提炼以下对话的核心要点，保留关键信息和决策结论，使用简洁的中文表达。"
                } else {
                    customRules
                }
                val history = listOf(
                    com.her.aimodifier.ai.provider.ChatMessageTurn(
                        role = "system",
                        content = rules
                    ),
                    com.her.aimodifier.ai.provider.ChatMessageTurn(
                        role = "user",
                        content = conversationText
                    )
                )
                val sb = StringBuilder()
                lease.service.sendMessage(
                    context = ServiceLocator.appContext,
                    chatHistory = history,
                    temperature = 0.5f,
                    maxTokens = 1024,
                    stream = true,
                    systemPrompt = null
                ).collect { chunk -> sb.append(chunk) }
                sb.toString().trim()
            } finally {
                lease.close()
            }
        }.onFailure { e ->
            android.util.Log.w("ChatViewModel", "生成摘要失败: ${e.message}")
        }.getOrNull()
    }

    fun selectModel(model: ModelInfo) {
        _currentModel.value = model
    }

    fun fetchRemoteModels() {
        if (_isFetchingModels.value) return

        viewModelScope.launch {
            _isFetchingModels.value = true
            _fetchModelsError.value = null

            try {
                val decision = taskRouter.decide(
                    workspaceId = currentWorkspaceId,
                    complexity = AiTaskRouter.Complexity.SIMPLE,
                    forceCloud = true
                )

                if (decision.target == AiTaskRouter.RouteTarget.CLOUD) {
                    val result = taskRouter.fetchModels(decision)
                    result.onSuccess { models ->
                        val cloudModels = models.map { ModelInfo(name = it.id, isLocal = false) }
                        val localModels = _availableModels.value.filter { it.isLocal }
                        val all = cloudModels + localModels
                        _availableModels.value = all

                        if (_currentModel.value == null && all.isNotEmpty()) {
                            _currentModel.value = all.first()
                        }

                        aiConfigRepository.findEffective(currentWorkspaceId)?.let { (config, _) ->
                            if (models.isNotEmpty()) {
                                val modelsJson = JsonUtil.json.encodeToString(
                                    kotlinx.serialization.serializer<List<com.her.aimodifier.ai.provider.ModelOption>>(),
                                    models
                                )
                                aiConfigRepository.updateCachedModels(config.id, modelsJson)
                            }
                        }
                    }
                    result.onFailure {
                        _fetchModelsError.value = it.message ?: "获取模型列表失败"
                    }
                }
            } catch (e: Exception) {
                _fetchModelsError.value = e.message ?: "获取模型列表失败"
            } finally {
                _isFetchingModels.value = false
            }
        }
    }

    private suspend fun updateAvailableModels() {
        val effectiveConfig = aiConfigRepository.findEffective(currentWorkspaceId)
        val cloudModels = effectiveConfig?.let { (config, _) ->
            val modelsFromCache = parseCachedModels(config.cachedModelsJson)
            val modelsFromManual = if (config.manualModelMode) {
                config.manualModels.split(",").map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { com.her.aimodifier.ai.provider.ModelOption(id = it, name = it) }
            } else {
                emptyList()
            }

            val allCloudModels = (modelsFromCache + modelsFromManual).distinctBy { it.id }
            if (allCloudModels.isNotEmpty()) {
                allCloudModels.map { ModelInfo(name = it.id, isLocal = false) }
            } else if (config.defaultModel.isNotEmpty()) {
                listOf(ModelInfo(name = config.defaultModel, isLocal = false))
            } else {
                emptyList()
            }
        } ?: emptyList()

        val localModels = runCatching {
            localModelRepository.observeAll().first()
        }.getOrDefault(emptyList())
            .map { ModelInfo(name = it.name, isLocal = true, modelId = it.id) }

        val all = cloudModels + localModels
        _availableModels.value = all

        if (_currentModel.value == null && all.isNotEmpty()) {
            _currentModel.value = all.first()
        }
        if (_currentModel.value != null && _currentModel.value!! !in all) {
            _currentModel.value = all.firstOrNull()
        }
    }

    private fun parseCachedModels(json: String): List<com.her.aimodifier.ai.provider.ModelOption> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching {
            JsonUtil.json.decodeFromString(
                kotlinx.serialization.serializer<List<com.her.aimodifier.ai.provider.ModelOption>>(),
                json
            )
        }.getOrDefault(emptyList())
    }

    fun send(userText: String, forceThinking: Boolean = false) {
        if (_isStreaming.value) return
        if (userText.isBlank()) return

        _messages.update { it + UiMessage(role = "user", content = userText) }

        val placeholderIdx = _messages.value.size
        _messages.update { it + UiMessage(role = "assistant", content = "", streaming = true, thinking = forceThinking) }

        _isStreaming.value = true
        _canPause.value = true
        paused = false

        // 开始新一轮对话
        conversationRoundManager.startNewRound()
        conversationRoundManager.updateContent("")

        streamJob = viewModelScope.launch {
            var fullResponse = ""
            try {
                val selectedModel = _currentModel.value
                val forceLocal = selectedModel?.isLocal == true
                val forceCloud = selectedModel?.isLocal == false

                val decision = taskRouter.decide(
                    workspaceId = currentWorkspaceId,
                    complexity = if (userText.length > 200) AiTaskRouter.Complexity.COMPLEX
                    else AiTaskRouter.Complexity.SIMPLE,
                    forceLocal = forceLocal,
                    forceCloud = forceCloud
                )

                val messages = buildMessages(userText, forceThinking)
                val stream = taskRouter.stream(messages, decision)

                val sb = StringBuilder()
                stream.collect { chunk ->
                    if (paused) return@collect
                    sb.append(chunk)
                    fullResponse = sb.toString()
                    // 同步给 ConversationRoundManager 累积
                    conversationRoundManager.updateContent(fullResponse)
                    _messages.update { list ->
                        list.toMutableList().apply {
                            this[placeholderIdx] = this[placeholderIdx].copy(content = fullResponse)
                        }
                    }
                }

                // 提取引用并附加到该条消息
                val extractedRefs = ReferenceManager.extractReferences(fullResponse)
                if (extractedRefs.isNotEmpty()) {
                    _references.value = extractedRefs
                    _messages.update { list ->
                        list.toMutableList().apply {
                            this[placeholderIdx] = this[placeholderIdx].copy(references = extractedRefs)
                        }
                    }
                }

                // 检测文件绑定补丁并尝试应用
                if (fullResponse.contains("[START-")) {
                    applyFileBindingIfPresent("", fullResponse, placeholderIdx)
                }

                _messages.update { list ->
                    list.toMutableList().apply {
                        this[placeholderIdx] = this[placeholderIdx].copy(streaming = false, thinking = false)
                    }
                }
                persistSession()

                checkAndExecuteToolCall(fullResponse)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                _messages.update { list ->
                    list.toMutableList().apply {
                        this[placeholderIdx] = this[placeholderIdx].copy(
                            content = (if (fullResponse.isNotEmpty()) fullResponse + "\n" else "") + "[ERROR] ${e.message}",
                            streaming = false,
                            thinking = false
                        )
                    }
                }
            } finally {
                _isStreaming.value = false
                _canPause.value = false
            }
        }
    }

    /**
     * 当响应中包含 [START-REPLACE] / [START-DELETE] 补丁时，调用 [FileBindingService]
     * 应用补丁并展示 diff。
     *
     * @param originalContent 原始文件内容（当前未与文件系统挂钩，传空字符串触发拒绝逻辑）
     * @param aiGeneratedCode AI 生成的代码（含编辑块）
     * @param placeholderIdx 当前流式消息索引，便于追加 diff 信息
     */
    private suspend fun applyFileBindingIfPresent(
        originalContent: String,
        aiGeneratedCode: String,
        placeholderIdx: Int
    ) {
        try {
            val (_, diff) = fileBindingService.processFileBinding(
                originalContent = originalContent,
                aiGeneratedCode = aiGeneratedCode
            )
            _lastFileBindingDiff.value = diff
            _messages.update { list ->
                list.toMutableList().apply {
                    val cur = this[placeholderIdx]
                    this[placeholderIdx] = cur.copy(
                        content = cur.content + "\n\n```diff\n$diff\n```"
                    )
                }
            }
        } catch (e: Exception) {
            // 文件绑定失败不影响主流程
            android.util.Log.w("ChatViewModel", "文件绑定失败: ${e.message}", e)
        }
    }

    fun pause() {
        paused = true
        _canPause.value = false
    }

    fun cancel() {
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
        _canPause.value = false
        _messages.update { list ->
            list.toMutableList().apply {
                val lastAssistant = indexOfLast { it.role == "assistant" && it.streaming }
                if (lastAssistant >= 0) {
                    this[lastAssistant] = this[lastAssistant].copy(streaming = false)
                }
            }
        }
    }

    fun cancelToolCall() {
        toolCallJob?.cancel()
        toolCallJob = null
        _isToolCallRunning.value = false
    }

    fun clear() {
        if (_isStreaming.value) cancel()
        if (_isToolCallRunning.value) cancelToolCall()
        _messages.value = emptyList()
    }

    fun observeToolLogs(): StateFlow<List<UiMessage>> = _messages

    private suspend fun checkAndExecuteToolCall(response: String) {
        // 优先识别 StructuredToolCallBridge 的 XML 工具调用格式：
        //   <tool_xxx name="..."><param name="...">value</param></tool_xxx>
        val xmlToolCalls = extractXmlToolCalls(response)
        if (xmlToolCalls.isNotEmpty()) {
            appendSystemMessage("检测到 ${xmlToolCalls.size} 个 XML 工具调用")
            xmlToolCalls.forEach { (toolName, args) ->
                val taskId = args["taskId"] ?: toolName
                val workspaceId = args["workspaceId"]
                triggerToolCall(taskId, workspaceId, args)
            }
            return
        }

        // 兼容旧格式：//TOOL_CALL: {json}
        val regex = Regex("""//TOOL_CALL:\s*(\{[^{}]*\})""")
        val match = regex.find(response) ?: return

        val jsonStr = match.groupValues[1]
        val jsonObj: JsonObject = runCatching {
            JsonUtil.json.parseToJsonElement(jsonStr).jsonObject
        }.getOrNull() ?: return

        val taskId = jsonObj["taskId"]?.jsonPrimitive?.content
        val workspaceId = jsonObj["workspaceId"]?.jsonPrimitive?.content
        val args = jsonObj["args"]?.jsonObject

        if (taskId == null) return

        val argsMap = args?.toMap()?.mapValues { (_, v) ->
            when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> v.content
                else -> v.toString()
            }
        } ?: emptyMap()

        appendSystemMessage("检测到 TOOL_CALL: taskId=$taskId, args=${argsMap.keys}")
        triggerToolCall(taskId, workspaceId, argsMap)
    }

    /**
     * 使用 [StructuredToolCallBridge] 的格式规则从响应中提取 XML 工具调用。
     *
     * 返回 Pair(工具名, 参数Map) 列表。
     */
    private fun extractXmlToolCalls(response: String): List<Pair<String, Map<String, String>>> {
        // 复用桥接器的 XML→JSON 转换能力
        val xmlToJson = StructuredToolCallBridge.convertToolCallPayloadToXml(response)
        // convertToolCallPayloadToXml 仅做还原，若原文本就是 XML，会原样返回；
        // 这里使用桥接器内部相同的正则模式来直接提取 XML 工具调用
        val toolCallPattern = Regex(
            """<tool(?:_[A-Za-z0-9_]+)?\b[^>]*name="([^"]+)"[^>]*>([\s\S]*?)</tool(?:_[A-Za-z0-9_]+)?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val paramPattern = Regex("""<param\s+name="([^"]+)">([\s\S]*?)</param>""")
        val results = mutableListOf<Pair<String, Map<String, String>>>()

        toolCallPattern.findAll(response).forEach { match ->
            val toolName = match.groupValues[1]
            val toolBody = match.groupValues[2]
            val params = mutableMapOf<String, String>()
            paramPattern.findAll(toolBody).forEach { paramMatch ->
                val name = paramMatch.groupValues[1]
                val value = paramMatch.groupValues[2].trim()
                params[name] = value
            }
            results.add(toolName to params)
        }

        return results
    }

    fun triggerToolCall(taskId: String, workspaceId: String?, args: Map<String, Any?>) {
        if (_isToolCallRunning.value) return

        toolCallJob = viewModelScope.launch {
            _isToolCallRunning.value = true
            val mcpClient = ServiceLocator.mcpClient
            try {
                appendToolLog("▶ 开始执行工具链: taskId=$taskId")

                appendToolLog("── 步骤 1/3: 环境检测 ──")
                when (val envResult = mcpClient.mcpCall(
                    "android.toolchain_manager.toolchain_check_env"
                )) {
                    is McpCallResult.Success -> appendToolLog(envResult.result)
                    is McpCallResult.Error -> appendToolLog("❌ 环境检测失败: ${envResult.message}")
                    is McpCallResult.Stream -> appendToolLog("⏳ 流式结果（跳过）")
                }

                appendToolLog("── 步骤 2/3: 任务准备 ($taskId) ──")
                when (val prepResult = mcpClient.mcpCall(
                    "android.toolchain_manager.toolchain_prepare_task",
                    mapOf("taskId" to taskId)
                )) {
                    is McpCallResult.Success -> appendToolLog(prepResult.result)
                    is McpCallResult.Error -> {
                        appendToolLog("❌ 任务准备失败: ${prepResult.message}")
                        return@launch
                    }
                    is McpCallResult.Stream -> appendToolLog("⏳ 流式结果（跳过）")
                }

                val command = args["command"] as? String
                if (command.isNullOrEmpty()) {
                    appendToolLog("⚠ TOOL_CALL 中未指定 command 参数，跳过执行")
                    return@launch
                }

                val runArgs = mutableMapOf<String, Any?>(
                    "command" to command
                ).apply {
                    args["cwd"]?.let { put("cwd", it) }
                    args["timeoutMs"]?.let { put("timeoutMs", it) }
                }

                appendToolLog("── 步骤 3/3: 执行命令 ──")
                appendToolLog("\$ $command")

                mcpClient.mcpCallStream(
                    "android.toolchain_manager.toolchain_run_command",
                    runArgs
                ).collect { chunk ->
                    appendToolLog(chunk.trimEnd())
                }

                appendToolLog("✅ 工具链执行完毕")
                appendSystemMessage("工具链执行完成: $taskId")
            } catch (e: CancellationException) {
                appendToolLog("⏹ 工具调用已取消")
            } catch (e: Exception) {
                appendToolLog("❌ 工具调用异常: ${e.message}")
            } finally {
                _isToolCallRunning.value = false
                toolCallJob = null
            }
        }
    }

    private fun appendSystemMessage(content: String) {
        _messages.update { it + UiMessage(role = "system", content = content, type = "system") }
    }

    private fun appendToolLog(content: String) {
        if (content.isBlank()) return
        _messages.update { it + UiMessage(role = "system", content = content, type = "tool_log") }
    }

    private fun buildMessages(userText: String, forceThinking: Boolean): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        promptMemory.buildSystemMessage(currentWorkspaceId)?.let { list += it }
        if (forceThinking) {
            list += ChatMessage(
                role = "system",
                content = "请先用【思考】标记输出推理过程，再用【结果】标记输出最终回答。"
            )
        }
        _messages.value.filter { it.role == "user" || it.role == "assistant" }.takeLast(10).forEach {
            list += ChatMessage(role = it.role, content = it.content)
        }
        return list
    }

    private suspend fun persistSession() {
        if (currentSessionId <= 0) return
        val json = _messages.value.joinToString("\n") { "${it.role}: ${it.content}" }
        sessionRepository.updateMessages(currentSessionId, json)
    }
}
