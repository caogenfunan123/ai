package com.her.aimodifier.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.her.aimodifier.di.ServiceLocator
import com.her.aimodifier.mcp.core.McpCallRecord
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.mcp.core.McpTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class McpPanelViewModel(
    private val pluginManager: com.her.aimodifier.mcp.plugins.AndroidControlPluginManager =
        ServiceLocator.pluginManager,
    private val mcpClient: com.her.aimodifier.mcp.core.McpClient = ServiceLocator.mcpClient
) : ViewModel() {

    data class PluginView(
        val pluginId: String,
        val displayName: String,
        val tools: List<McpTool>
    )

    private val _plugins = MutableStateFlow<List<PluginView>>(emptyList())
    val plugins: StateFlow<List<PluginView>> = _plugins.asStateFlow()

    private val _callHistory = MutableStateFlow<List<McpCallRecord>>(emptyList())
    val callHistory: StateFlow<List<McpCallRecord>> = _callHistory.asStateFlow()

    private val _testResult = MutableStateFlow<TestCallResult?>(null)
    val testResult: StateFlow<TestCallResult?> = _testResult.asStateFlow()

    private val _isCalling = MutableStateFlow(false)
    val isCalling: StateFlow<Boolean> = _isCalling.asStateFlow()

    init {
        refresh()
        refreshHistory()
    }

    fun refresh() {
        _plugins.value = pluginManager.allPlugins().map { p ->
            PluginView(
                pluginId = p.pluginId,
                displayName = p.displayName,
                tools = p.listTools()
            )
        }
    }

    fun refreshHistory() {
        _callHistory.value = mcpClient.getCallHistory()
    }

    fun clearHistory() {
        mcpClient.clearCallHistory()
        _callHistory.value = emptyList()
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun testCall(
        fullToolName: String,
        arguments: Map<String, Any?> = emptyMap()
    ) {
        viewModelScope.launch {
            _isCalling.value = true
            try {
                val result = mcpClient.mcpCall(fullToolName, arguments)
                _testResult.value = when (result) {
                    is McpCallResult.Success -> TestCallResult(
                        success = true,
                        output = result.result,
                        error = null
                    )
                    is McpCallResult.Error -> TestCallResult(
                        success = false,
                        output = null,
                        error = "[${result.code}] ${result.message}"
                    )
                    is McpCallResult.Stream -> TestCallResult(
                        success = true,
                        output = "[流式结果，请在对话窗口查看]",
                        error = null
                    )
                }
            } catch (e: Exception) {
                _testResult.value = TestCallResult(
                    success = false,
                    output = null,
                    error = e.message ?: "未知异常"
                )
            } finally {
                _isCalling.value = false
                refreshHistory()
            }
        }
    }

    fun allTools(): List<McpTool> = mcpClient.mcpListTools()
}

data class TestCallResult(
    val success: Boolean,
    val output: String?,
    val error: String?
)