package com.her.aimodifier.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.her.aimodifier.ai.client.ModelInfo
import com.her.aimodifier.ai.client.OpenAiStreamClient
import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.data.database.entity.AiConfigEntity
import com.her.aimodifier.data.repository.AiConfigRepository
import com.her.aimodifier.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AI 模型设置 ViewModel（最终定稿）。
 *
 * 功能：
 * - 加载/保存全局或 workspace 级配置
 * - 拉取远端 /v1/models 模型列表
 * - 容错：拉取失败时启用手动模型输入
 * - 作用域选择（global / 当前工作区）
 * - LLM 参数（超时、上下文长度、temperature）
 */
class AiSettingViewModel(
    private val aiConfigRepository: AiConfigRepository = ServiceLocator.aiConfigRepository,
    private val openAiClient: OpenAiStreamClient = ServiceLocator.openAiClient
) : ViewModel() {

    private val _config = MutableStateFlow<AiConfigEntity?>(null)
    val config: StateFlow<AiConfigEntity?> = _config.asStateFlow()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _fetching = MutableStateFlow(false)
    val fetching: StateFlow<Boolean> = _fetching.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _manualMode = MutableStateFlow(false)
    val manualMode: StateFlow<Boolean> = _manualMode.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    /** 加载配置：scope=global 或 workspace */
    fun load(workspaceId: String?) {
        viewModelScope.launch {
            val effective = aiConfigRepository.findEffective(workspaceId)
            _config.value = effective?.first
            _manualMode.value = effective?.first?.manualModelMode == true
        }
    }

    /** 保存配置 */
    fun save(
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        scope: String = AppConstants.AiConfigScope.GLOBAL,
        workspaceId: String? = null,
        timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
        contextLength: Int = AppConstants.DEFAULT_CONTEXT_LENGTH,
        temperature: Float = AppConstants.DEFAULT_TEMPERATURE
    ) {
        viewModelScope.launch {
            _saving.value = true
            _error.value = null
            _saveSuccess.value = false
            runCatching {
                if (scope == AppConstants.AiConfigScope.WORKSPACE && workspaceId != null) {
                    aiConfigRepository.upsertWorkspace(
                        workspaceId, baseUrl, apiKey, defaultModel,
                        timeoutMs, contextLength, temperature
                    )
                } else {
                    aiConfigRepository.upsertGlobal(
                        baseUrl, apiKey, defaultModel,
                        timeoutMs, contextLength, temperature
                    )
                }
            }.onSuccess {
                _saveSuccess.value = true
            }.onFailure {
                _error.value = it.message
            }
            _saving.value = false
        }
    }

    /** 拉取远端模型列表 */
    fun fetchModels() {
        val cfg = _config.value ?: return
        if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty()) {
            _error.value = "请先填写 baseUrl 和 apiKey"
            return
        }
        viewModelScope.launch {
            _fetching.value = true
            _error.value = null
            runCatching {
                val list = openAiClient.fetchModels(cfg.baseUrl, cfg.apiKey)
                _models.value = list
                _config.value?.id?.let { id ->
                    aiConfigRepository.updateCachedModels(id, kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ModelInfo.serializer()),
                        list
                    ))
                }
            }.onFailure {
                _error.value = "模型列表拉取失败：${it.message}，请启用手动输入"
                _manualMode.value = true
            }
            _fetching.value = false
        }
    }

    /** 切换手动模式 */
    fun setManualMode(enabled: Boolean) {
        _manualMode.value = enabled
        val cfg = _config.value ?: return
        viewModelScope.launch {
            aiConfigRepository.updateManualModels(cfg.id, cfg.manualModels, enabled)
        }
    }

    /** 保存手动模型列表 */
    fun saveManualModels(models: List<String>) {
        val cfg = _config.value ?: return
        viewModelScope.launch {
            aiConfigRepository.updateManualModels(cfg.id, models.joinToString(","), true)
            _manualMode.value = true
        }
    }

    fun clearError() { _error.value = null }

    fun clearSuccess() { _saveSuccess.value = false }
}