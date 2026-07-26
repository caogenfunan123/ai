package com.her.aimodifier.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.her.aimodifier.ai.client.ChatMessage
import com.her.aimodifier.ai.memory.GlobalPromptMemoryManager
import com.her.aimodifier.ai.routing.AiTaskRouter
import com.her.aimodifier.data.repository.AiConfigRepository
import com.her.aimodifier.data.repository.ChatSessionRepository
import com.her.aimodifier.data.repository.LocalModelRepository
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
 */
class ChatViewModel(
    private val sessionRepository: ChatSessionRepository = ServiceLocator.chatSessionRepository,
    private val aiConfigRepository: AiConfigRepository = ServiceLocator.aiConfigRepository,
    private val localModelRepository: LocalModelRepository = ServiceLocator.localModelRepository,
    private val taskRouter: AiTaskRouter = ServiceLocator.aiTaskRouter,
    private val promptMemory: GlobalPromptMemoryManager = ServiceLocator.promptMemory
) : ViewModel() {

    /** 单条消息（UI 用） */
    data class UiMessage(
        val role: String,
        val content: String,
        val thinking: Boolean = false,
        val streaming: Boolean = false,
        val type: String = "message"
    )

    /** 模型信息 */
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
        }
    }

    fun selectModel(model: ModelInfo) {
        _currentModel.value = model
    }

    private suspend fun updateAvailableModels() {
        val cloudModels = aiConfigRepository.findEffective(currentWorkspaceId)?.let { (config, _) ->
            if (config.manualModelMode) {
                config.manualModels.split(",").map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { ModelInfo(name = it, isLocal = false) }
            } else {
                listOfNotNull(
                    config.defaultModel.takeIf { it.isNotEmpty() }
                        ?.let { ModelInfo(name = it, isLocal = false) }
                )
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

    /** 发送用户消息并启动流式回复 */
    fun send(userText: String, forceThinking: Boolean = false) {
        if (_isStreaming.value) return
        if (userText.isBlank()) return

        _messages.update { it + UiMessage(role = "user", content = userText) }

        val placeholderIdx = _messages.value.size
        _messages.update { it + UiMessage(role = "assistant", content = "", streaming = true, thinking = forceThinking) }

        _isStreaming.value = true
        _canPause.value = true
        paused = false

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
                    _messages.update { list ->
                        list.toMutableList().apply {
                            this[placeholderIdx] = this[placeholderIdx].copy(content = fullResponse)
                        }
                    }
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

    /** 暂停（停止接收后续 token，但不取消任务） */
    fun pause() {
        paused = true
        _canPause.value = false
    }

    /** 取消当前流式任务 */
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

    /** 取消当前工具调用 */
    fun cancelToolCall() {
        toolCallJob?.cancel()
        toolCallJob = null
        _isToolCallRunning.value = false
    }

    /** 清空当前会话消息 */
    fun clear() {
        if (_isStreaming.value) cancel()
        if (_isToolCallRunning.value) cancelToolCall()
        _messages.value = emptyList()
    }

    /** 观察工具调用日志 */
    fun observeToolLogs(): StateFlow<List<UiMessage>> = _messages

    private suspend fun checkAndExecuteToolCall(response: String) {
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

    /** 触发 MCP 链式执行 */
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