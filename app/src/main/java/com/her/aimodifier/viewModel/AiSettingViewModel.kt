package com.her.aimodifier.viewModel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.her.aimodifier.ai.client.ModelInfo
import com.her.aimodifier.ai.client.OpenAiStreamClient
import com.her.aimodifier.ai.provider.ApiKeyAvailabilityStatus
import com.her.aimodifier.ai.provider.ApiKeyInfo
import com.her.aimodifier.ai.provider.ApiProviderType
import com.her.aimodifier.ai.provider.ModelConfigData
import com.her.aimodifier.ai.provider.ModelListFetcher
import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.data.database.entity.AiConfigEntity
import com.her.aimodifier.data.repository.AiConfigRepository
import com.her.aimodifier.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class PresetProvider(
    val name: String,
    val type: ApiProviderType,
    val defaultEndpoint: String,
    val defaultModel: String = ""
)

/**
 * 预设的 API 提供商列表。
 *
 * 涵盖国内主流厂商、海外大模型平台、本地推理后端以及通用 OpenAI 兼容接入，
 * 用户在 AI 设置界面中可一键应用预设配置。
 */
val PRESET_PROVIDERS = listOf(
    // 国内厂商
    PresetProvider("DeepSeek", ApiProviderType.DEEPSEEK, "https://api.deepseek.com", "deepseek-chat"),
    PresetProvider("Moonshot (Kimi)", ApiProviderType.MOONSHOT, "https://api.moonshot.cn", "moonshot-v1-8k"),
    PresetProvider("智谱 AI (GLM)", ApiProviderType.ZHIPU, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    PresetProvider("百川智能", ApiProviderType.BAICHUAN, "https://api.baichuan-ai.com/v1", "Baichuan4"),
    PresetProvider("硅基流动 (SiliconFlow)", ApiProviderType.SILICONFLOW, "https://api.siliconflow.cn", "deepseek-ai/DeepSeek-V3"),
    PresetProvider("Mimo AI", ApiProviderType.MIMO, "https://api.mimo.ai", "mimo-chat"),
    PresetProvider("阶跃星辰 (StepFun)", ApiProviderType.IFLOW, "https://api.stepfun.com", "step-1-flash"),
    PresetProvider("通义千问 (Qwen)", ApiProviderType.QWEN, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    PresetProvider("豆包 (Doubao)", ApiProviderType.DOUBAO, "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k"),
    PresetProvider("百灵 (AlipayBailing)", ApiProviderType.ALIPAY_BAILING, "https://dashscope.aliyuncs.com/compatible-mode/v1", "bailing-1.0"),
    PresetProvider("InfiniAI", ApiProviderType.INFINIAI, "https://cloud.infini-ai.com/maas", "deepseek-v3"),
    // 海外大模型平台
    PresetProvider("Anthropic (Claude)", ApiProviderType.ANTHROPIC, "https://api.anthropic.com", "claude-3-5-sonnet-20240620"),
    PresetProvider("Google Gemini", ApiProviderType.GOOGLE, "https://generativelanguage.googleapis.com", "gemini-1.5-flash"),
    PresetProvider("Mistral", ApiProviderType.MISTRAL, "https://api.mistral.ai/v1", "mistral-large-latest"),
    PresetProvider("OpenRouter", ApiProviderType.OPENROUTER, "https://openrouter.ai/api/v1", "openrouter/auto"),
    PresetProvider("英伟达 (NVIDIA)", ApiProviderType.NVIDIA, "https://integrate.api.nvidia.com/v1", "meta/llama-3.1-405b-instruct"),
    PresetProvider("Groq", ApiProviderType.GROQ, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    PresetProvider("Together AI", ApiProviderType.TOGETHER, "https://api.together.xyz/v1", "meta-llama/Llama-3-70b-chat-hf"),
    PresetProvider("Fireworks AI", ApiProviderType.FIREWORKS, "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/llama-v3-70b-instruct"),
    PresetProvider("Perplexity", ApiProviderType.PERPLEXITY, "https://api.perplexity.ai", "llama-3.1-sonar-large-128k-online"),
    PresetProvider("Cohere", ApiProviderType.COHERE, "https://api.cohere.ai/v1", "command-r-plus"),
    PresetProvider("Grok (xAI)", ApiProviderType.GROK, "https://api.x.ai/v1", "grok-2-latest"),
    PresetProvider("OpenAI", ApiProviderType.OPENAI, "https://api.openai.com", "gpt-4o-mini"),
    // 本地推理后端
    PresetProvider("本地 (Ollama)", ApiProviderType.OLLAMA, "http://localhost:11434/v1", "llama3"),
    PresetProvider("本地 (LM Studio)", ApiProviderType.LMSTUDIO, "http://localhost:1234/v1", ""),
    PresetProvider("本地 (llama.cpp)", ApiProviderType.LLAMA_CPP, "http://localhost:8080", ""),
    PresetProvider("本地 (MNN)", ApiProviderType.MNN, "http://localhost:8081", ""),
    // 通用
    PresetProvider("OpenAI 通用兼容", ApiProviderType.OPENAI_GENERIC, "", "gpt-4o-mini"),
    PresetProvider("FourRouter", ApiProviderType.FOUR_ROUTER, "https://api.fourrouter.com/v1", "auto"),
    PresetProvider("NousPortal", ApiProviderType.NOUS_PORTAL, "https://inference.nousresearch.com/v1", "Hermes-3-Llama-3.1-405B"),
    PresetProvider("PPinfra", ApiProviderType.PPINFRA, "https://api.ppinfra.com/v3/openai", "ppinfra-deepseek-r1"),
    PresetProvider("Novita AI", ApiProviderType.NOVITA, "https://api.novita.ai/v3/openai", "deepseek/deepseek-r1")
)

/**
 * AI 配置 ViewModel。
 *
 * 支持：
 * - 多 API Key Pool 管理（添加/删除/启用/测试）
 * - 限流与并发配置
 * - 摘要、思考模式、多模态等高级选项
 * - 远程模型列表拉取与缓存
 * - 基于完整 [ModelConfigData] 的保存（覆盖所有字段）
 */
class AiSettingViewModel(
    private val aiConfigRepository: AiConfigRepository = ServiceLocator.aiConfigRepository,
    private val openAiClient: OpenAiStreamClient = ServiceLocator.openAiClient,
    private val context: Context = ServiceLocator.appContext
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

    /** 当前编辑中的 API Key 池（仅内存态，保存时同步到 DB） */
    private val _apiKeyPool = MutableStateFlow<List<ApiKeyInfo>>(emptyList())
    val apiKeyPool: StateFlow<List<ApiKeyInfo>> = _apiKeyPool.asStateFlow()

    /** 当前测试中的 Key 索引（用于 UI 显示进度） */
    private val _testingKeyIndex = MutableStateFlow(-1)
    val testingKeyIndex: StateFlow<Int> = _testingKeyIndex.asStateFlow()

    val presets: List<PresetProvider> = PRESET_PROVIDERS

    fun load(workspaceId: String?) {
        viewModelScope.launch {
            val effective = aiConfigRepository.findEffective(workspaceId)
            _config.value = effective?.first
            _manualMode.value = effective?.first?.manualModelMode == true
            // 同步加载 API Key 池到内存
            val cfg = effective?.first
            if (cfg != null) {
                _apiKeyPool.value = parseApiKeyPoolJson(cfg.apiKeyPoolJson)
            } else {
                _apiKeyPool.value = emptyList()
            }
        }
    }

    fun applyPreset(preset: PresetProvider) {
        val current = _config.value ?: AiConfigEntity()
        _config.value = current.copy(
            providerType = preset.type.name,
            baseUrl = preset.defaultEndpoint,
            defaultModel = preset.defaultModel
        )
    }

    /**
     * 保存完整配置（包括所有新字段）。
     *
     * 调用方提供完整的字段值后，构造 [ModelConfigData] 并通过
     * [AiConfigRepository.upsertFromModelConfig] 持久化。
     */
    fun saveFullConfig(
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        scope: String = AppConstants.AiConfigScope.GLOBAL,
        workspaceId: String? = null,
        providerType: ApiProviderType = ApiProviderType.DEEPSEEK,
        configName: String = "默认配置",
        temperature: Float = AppConstants.DEFAULT_TEMPERATURE,
        temperatureEnabled: Boolean = false,
        maxTokens: Int = 4096,
        maxTokensEnabled: Boolean = false,
        enableToolCall: Boolean = false,
        enableThinking: Boolean = false,
        thinkingQualityLevel: Int = 2,
        enableDirectImageProcessing: Boolean = false,
        enableDirectAudioProcessing: Boolean = false,
        enableDirectVideoProcessing: Boolean = false,
        enableGoogleSearch: Boolean = false,
        enableClaude1hPromptCache: Boolean = false,
        enableSummary: Boolean = true,
        enableSummaryByMessageCount: Boolean = true,
        summaryMessageCountThreshold: Int = 16,
        summaryTokenThreshold: Float = 0.70f,
        summaryCustomRules: String = "",
        requestLimitPerMinute: Int = 0,
        maxConcurrentRequests: Int = 0,
        systemPrompt: String = "",
        customHeaders: String = "{}",
        useMultipleApiKeys: Boolean = false,
        contextLength: Float = 64.0f,
        maxContextLength: Float = 200.0f,
        enableMaxContextMode: Boolean = false,
        topPEnabled: Boolean = false,
        topKEnabled: Boolean = false,
        presencePenaltyEnabled: Boolean = false,
        frequencyPenaltyEnabled: Boolean = false,
        repetitionPenaltyEnabled: Boolean = false,
        topP: Float = 1.0f,
        topK: Int = 0,
        presencePenalty: Float = 0.0f,
        frequencyPenalty: Float = 0.0f,
        repetitionPenalty: Float = 1.0f,
        hasCustomParameters: Boolean = false,
        customParameters: String = "[]",
        llamaThreadCount: Int = 4,
        llamaContextSize: Int = 2048,
        llamaBatchSize: Int = 512,
        llamaUBatchSize: Int = 512,
        llamaGpuLayers: Int = 0,
        llamaUseMmap: Boolean = false,
        llamaFlashAttention: Boolean = false,
        llamaKvUnified: Boolean = true,
        llamaOffloadKqv: Boolean = false,
        forwardType: Int = 0,
        timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
        keyRotationMode: String = "ROUND_ROBIN",
        currentKeyIndex: Int = 0
    ) {
        viewModelScope.launch {
            _saving.value = true
            _error.value = null
            _saveSuccess.value = false
            runCatching {
                val configData = ModelConfigData(
                    id = _config.value?.configId ?: "default",
                    name = configName,
                    apiKey = apiKey,
                    apiEndpoint = baseUrl,
                    modelName = defaultModel,
                    apiProviderType = providerType,
                    apiProviderTypeId = providerType.name,
                    useMultipleApiKeys = useMultipleApiKeys,
                    apiKeyPool = _apiKeyPool.value,
                    currentKeyIndex = currentKeyIndex,
                    keyRotationMode = keyRotationMode,
                    hasCustomParameters = hasCustomParameters,
                    maxTokensEnabled = maxTokensEnabled,
                    temperatureEnabled = temperatureEnabled,
                    topPEnabled = topPEnabled,
                    topKEnabled = topKEnabled,
                    presencePenaltyEnabled = presencePenaltyEnabled,
                    frequencyPenaltyEnabled = frequencyPenaltyEnabled,
                    repetitionPenaltyEnabled = repetitionPenaltyEnabled,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    topK = topK,
                    presencePenalty = presencePenalty,
                    frequencyPenalty = frequencyPenalty,
                    repetitionPenalty = repetitionPenalty,
                    customParameters = customParameters,
                    customHeaders = customHeaders,
                    contextLength = contextLength,
                    maxContextLength = maxContextLength,
                    enableMaxContextMode = enableMaxContextMode,
                    summaryTokenThreshold = summaryTokenThreshold,
                    enableSummary = enableSummary,
                    enableSummaryByMessageCount = enableSummaryByMessageCount,
                    summaryMessageCountThreshold = summaryMessageCountThreshold,
                    summaryCustomRules = summaryCustomRules,
                    llamaThreadCount = llamaThreadCount,
                    llamaContextSize = llamaContextSize,
                    llamaBatchSize = llamaBatchSize,
                    llamaUBatchSize = llamaUBatchSize,
                    llamaGpuLayers = llamaGpuLayers,
                    llamaUseMmap = llamaUseMmap,
                    llamaFlashAttention = llamaFlashAttention,
                    llamaKvUnified = llamaKvUnified,
                    llamaOffloadKqv = llamaOffloadKqv,
                    enableDirectImageProcessing = enableDirectImageProcessing,
                    enableDirectAudioProcessing = enableDirectAudioProcessing,
                    enableDirectVideoProcessing = enableDirectVideoProcessing,
                    enableGoogleSearch = enableGoogleSearch,
                    enableClaude1hPromptCache = enableClaude1hPromptCache,
                    enableToolCall = enableToolCall,
                    systemPrompt = systemPrompt,
                    requestLimitPerMinute = requestLimitPerMinute,
                    maxConcurrentRequests = maxConcurrentRequests,
                    enableThinking = enableThinking,
                    thinkingQualityLevel = thinkingQualityLevel,
                    forwardType = forwardType,
                    timeoutMs = timeoutMs
                )
                aiConfigRepository.upsertFromModelConfig(configData, scope, workspaceId)
            }.onSuccess {
                _saveSuccess.value = true
                // 重新加载配置以反映保存后的状态
                load(workspaceId)
            }.onFailure {
                _error.value = it.message
            }
            _saving.value = false
        }
    }

    /**
     * 兼容旧调用的简版保存（仅基础字段）。
     */
    fun save(
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        scope: String = AppConstants.AiConfigScope.GLOBAL,
        workspaceId: String? = null,
        timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
        contextLength: Int = AppConstants.DEFAULT_CONTEXT_LENGTH,
        temperature: Float = AppConstants.DEFAULT_TEMPERATURE,
        providerType: String = ApiProviderType.DEEPSEEK.name,
        configName: String = "默认配置",
        enableToolCall: Boolean = false,
        systemPrompt: String = ""
    ) {
        viewModelScope.launch {
            _saving.value = true
            _error.value = null
            _saveSuccess.value = false
            runCatching {
                if (scope == AppConstants.AiConfigScope.WORKSPACE && workspaceId != null) {
                    aiConfigRepository.upsertWorkspace(
                        workspaceId, baseUrl, apiKey, defaultModel,
                        timeoutMs, contextLength, temperature,
                        providerType = providerType,
                        configName = configName,
                        enableToolCall = enableToolCall,
                        systemPrompt = systemPrompt
                    )
                } else {
                    aiConfigRepository.upsertGlobal(
                        baseUrl, apiKey, defaultModel,
                        timeoutMs, contextLength, temperature,
                        providerType = providerType,
                        configName = configName,
                        enableToolCall = enableToolCall,
                        systemPrompt = systemPrompt
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

    // ============ 多 API Key 池管理 ============

    /** 添加一个 API Key 到池中（仅内存，保存时持久化） */
    fun addApiKey(name: String, key: String) {
        if (key.isBlank()) return
        val newPool = _apiKeyPool.value + ApiKeyInfo(
            key = key,
            name = name.ifBlank { "Key-${_apiKeyPool.value.size + 1}" },
            isEnabled = true
        )
        _apiKeyPool.value = newPool
    }

    /** 移除池中指定索引的 Key */
    fun removeApiKey(index: Int) {
        if (index < 0 || index >= _apiKeyPool.value.size) return
        _apiKeyPool.value = _apiKeyPool.value.toMutableList().apply { removeAt(index) }
    }

    /** 启用/禁用指定索引的 Key */
    fun toggleApiKeyEnabled(index: Int, enabled: Boolean) {
        if (index < 0 || index >= _apiKeyPool.value.size) return
        _apiKeyPool.value = _apiKeyPool.value.toMutableList().apply {
            this[index] = this[index].copy(isEnabled = enabled)
        }
    }

    /** 更新指定索引的 Key 名称 */
    fun renameApiKey(index: Int, newName: String) {
        if (index < 0 || index >= _apiKeyPool.value.size) return
        _apiKeyPool.value = _apiKeyPool.value.toMutableList().apply {
            this[index] = this[index].copy(name = newName)
        }
    }

    /** 将当前内存中的 Key 池持久化到 DB */
    fun persistApiKeyPool(useMultiple: Boolean) {
        val cfg = _config.value ?: return
        viewModelScope.launch {
            aiConfigRepository.updateApiKeyPool(
                cfg.id,
                serializeApiKeyPool(_apiKeyPool.value),
                useMultiple
            )
            _config.value = cfg.copy(
                apiKeyPoolJson = serializeApiKeyPool(_apiKeyPool.value),
                useMultipleApiKeys = useMultiple
            )
        }
    }

    /** 重置所有 Key 的可用性状态为未测试 */
    fun resetAllKeyStatuses() {
        val cfg = _config.value ?: return
        viewModelScope.launch {
            _apiKeyPool.value = _apiKeyPool.value.map {
                it.copy(availabilityStatus = ApiKeyAvailabilityStatus.UNTESTED)
            }
            aiConfigRepository.updateApiKeyPool(
                cfg.id,
                serializeApiKeyPool(_apiKeyPool.value),
                cfg.useMultipleApiKeys
            )
        }
    }

    /**
     * 测试池中所有启用的 Key 的可用性。
     *
     * 通过尝试拉取模型列表来验证 Key 是否有效。
     */
    fun testAllApiKeys() {
        val cfg = _config.value ?: return
        val pool = _apiKeyPool.value.filter { it.isEnabled }
        if (pool.isEmpty()) {
            _error.value = "API Key 池为空，请先添加 Key"
            return
        }

        viewModelScope.launch {
            for ((idx, keyInfo) in pool.withIndex()) {
                _testingKeyIndex.value = idx
                val isAvailable = try {
                    val providerType = ApiProviderType.fromProviderTypeId(cfg.providerType)
                        ?: ApiProviderType.OPENAI_GENERIC
                    val result = ModelListFetcher.getModelsList(
                        context = context,
                        apiKey = keyInfo.key,
                        apiEndpoint = cfg.baseUrl,
                        apiProviderType = providerType
                    )
                    result.isSuccess
                } catch (_: Exception) {
                    false
                }
                val newStatus = if (isAvailable) ApiKeyAvailabilityStatus.AVAILABLE
                else ApiKeyAvailabilityStatus.UNAVAILABLE

                _apiKeyPool.value = _apiKeyPool.value.map { existing ->
                    if (existing.name == keyInfo.name && existing.key == keyInfo.key) {
                        existing.copy(availabilityStatus = newStatus)
                    } else existing
                }
            }
            _testingKeyIndex.value = -1
            // 持久化测试结果
            aiConfigRepository.updateApiKeyPool(
                cfg.id,
                serializeApiKeyPool(_apiKeyPool.value),
                cfg.useMultipleApiKeys
            )
        }
    }

    // ============ 限流与并发配置 ============

    fun updateRateLimitSettings(requestLimitPerMinute: Int, maxConcurrentRequests: Int) {
        val cfg = _config.value ?: return
        viewModelScope.launch {
            aiConfigRepository.updateRateLimitSettings(
                cfg.id,
                requestLimitPerMinute,
                maxConcurrentRequests
            )
            _config.value = cfg.copy(
                requestLimitPerMinute = requestLimitPerMinute,
                maxConcurrentRequests = maxConcurrentRequests
            )
        }
    }

    fun updateThinkingSettings(enableThinking: Boolean, qualityLevel: Int) {
        val cfg = _config.value ?: return
        viewModelScope.launch {
            aiConfigRepository.updateThinkingSettings(
                cfg.id,
                enableThinking,
                qualityLevel
            )
            _config.value = cfg.copy(
                enableThinking = enableThinking,
                thinkingQualityLevel = qualityLevel
            )
        }
    }

    fun updateMultimodalSettings(
        image: Boolean,
        audio: Boolean,
        video: Boolean
    ) {
        val cfg = _config.value ?: return
        viewModelScope.launch {
            aiConfigRepository.updateMultimodalSettings(cfg.id, image, audio, video)
            _config.value = cfg.copy(
                enableDirectImageProcessing = image,
                enableDirectAudioProcessing = audio,
                enableDirectVideoProcessing = video
            )
        }
    }

    fun updateSummarySettings(
        enableSummary: Boolean,
        enableSummaryByMessageCount: Boolean,
        summaryMessageCountThreshold: Int,
        summaryTokenThreshold: Float,
        summaryCustomRules: String
    ) {
        val cfg = _config.value ?: return
        viewModelScope.launch {
            aiConfigRepository.updateSummarySettings(
                cfg.id,
                enableSummary,
                enableSummaryByMessageCount,
                summaryMessageCountThreshold,
                summaryTokenThreshold,
                summaryCustomRules
            )
            _config.value = cfg.copy(
                enableSummary = enableSummary,
                enableSummaryByMessageCount = enableSummaryByMessageCount,
                summaryMessageCountThreshold = summaryMessageCountThreshold,
                summaryTokenThreshold = summaryTokenThreshold,
                summaryCustomRules = summaryCustomRules
            )
        }
    }

    fun fetchModels() {
        val cfg = _config.value ?: return
        if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty()) {
            _error.value = "请先填写 API 地址和 API Key"
            return
        }
        viewModelScope.launch {
            _fetching.value = true
            _error.value = null
            runCatching {
                val providerType = ApiProviderType.fromProviderTypeId(cfg.providerType)
                    ?: ApiProviderType.OPENAI_GENERIC

                val result = ModelListFetcher.getModelsList(
                    context = context,
                    apiKey = cfg.apiKey,
                    apiEndpoint = cfg.baseUrl,
                    apiProviderType = providerType
                )

                result.onSuccess { modelOptions ->
                    val modelInfos = modelOptions.map { ModelInfo(id = it.id, owned_by = null) }
                    _models.value = modelInfos

                    _config.value?.id?.let { id ->
                        val modelsJson = kotlinx.serialization.json.Json.encodeToString(
                            kotlinx.serialization.serializer<List<com.her.aimodifier.ai.provider.ModelOption>>(),
                            modelOptions
                        )
                        aiConfigRepository.updateCachedModels(id, modelsJson)
                    }
                }
                result.onFailure {
                    throw it
                }
            }.onFailure {
                _error.value = "模型列表拉取失败：${it.message}，请启用手动输入"
                _manualMode.value = true
            }
            _fetching.value = false
        }
    }

    fun setManualMode(enabled: Boolean) {
        _manualMode.value = enabled
        val cfg = _config.value ?: return
        viewModelScope.launch {
            aiConfigRepository.updateManualModels(cfg.id, cfg.manualModels, enabled)
        }
    }

    fun saveManualModels(models: List<String>) {
        val cfg = _config.value ?: return
        viewModelScope.launch {
            aiConfigRepository.updateManualModels(cfg.id, models.joinToString(","), true)
            _manualMode.value = true
        }
    }

    fun clearError() { _error.value = null }
    fun clearSuccess() { _saveSuccess.value = false }

    // ============ 工具方法 ============

    private fun parseApiKeyPoolJson(json: String): List<ApiKeyInfo> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                ApiKeyInfo(
                    key = obj.optString("key", ""),
                    name = obj.optString("name", ""),
                    isEnabled = obj.optBoolean("isEnabled", true),
                    availabilityStatus = runCatching {
                        ApiKeyAvailabilityStatus.valueOf(
                            obj.optString("availabilityStatus", "UNTESTED")
                        )
                    }.getOrDefault(ApiKeyAvailabilityStatus.UNTESTED)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeApiKeyPool(pool: List<ApiKeyInfo>): String {
        if (pool.isEmpty()) return "[]"
        val array = JSONArray()
        pool.forEach { info ->
            array.put(JSONObject().apply {
                put("key", info.key)
                put("name", info.name)
                put("isEnabled", info.isEnabled)
                put("availabilityStatus", info.availabilityStatus.name)
            })
        }
        return array.toString()
    }
}
