package com.her.aimodifier.data.repository

import com.her.aimodifier.ai.provider.ModelConfigData
import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.data.database.dao.AiConfigDao
import com.her.aimodifier.data.database.entity.AiConfigEntity
import com.her.aimodifier.data.pref.EncryptedPrefs
import kotlinx.coroutines.flow.Flow

/**
 * AI 配置仓库。
 *
 * 实现 global / workspace 双作用域：
 * - [findEffective]：workspace 优先，回退 global
 * - ApiKey 写入时同步冗余到 [EncryptedPrefs]，便于 SQLCipher 之外的安全访问
 *
 * 同时提供两种保存方式：
 * - 兼容旧调用的简版 [upsertGlobal] / [upsertWorkspace]（仅基础字段）
 * - 基于完整 [ModelConfigData] 的 [upsertFromModelConfig]（覆盖所有字段）
 */
class AiConfigRepository(
    private val dao: AiConfigDao,
    private val prefs: EncryptedPrefs
) {

    fun observeGlobal(): Flow<AiConfigEntity?> = dao.observeGlobal()

    fun observeByWorkspace(workspaceId: String): Flow<AiConfigEntity?> =
        dao.observeByWorkspace(workspaceId)

    fun observeAll(): Flow<List<AiConfigEntity>> = dao.observeAll()

    suspend fun findGlobal(): AiConfigEntity? = dao.findGlobal()

    suspend fun findByWorkspace(workspaceId: String): AiConfigEntity? =
        dao.findByWorkspace(workspaceId)

    /**
     * 按 workspace 优先 → global 回退的顺序返回有效配置。
     * @return Pair(配置, 是否命中 workspace 独立配置)
     */
    suspend fun findEffective(workspaceId: String?): Pair<AiConfigEntity, Boolean>? {
        if (workspaceId != null) {
            dao.findByWorkspace(workspaceId)?.let { return it to true }
        }
        dao.findGlobal()?.let { return it to false }
        return null
    }

    /**
     * 基于完整的 [ModelConfigData] 保存配置。
     *
     * 自动按 scope 选择是覆盖 global 还是覆盖对应 workspace 的配置，
     * 调用方只需提供完整数据模型即可。
     */
    suspend fun upsertFromModelConfig(
        config: ModelConfigData,
        scope: String,
        workspaceId: String? = null
    ): AiConfigEntity {
        val existing = when (scope) {
            AppConstants.AiConfigScope.WORKSPACE -> workspaceId?.let { dao.findByWorkspace(it) }
            else -> dao.findGlobal()
        }

        val entity = ModelConfigConverter.toEntity(config, scope, workspaceId)
            .copy(
                id = existing?.id ?: 0,
                manualModels = existing?.manualModels ?: "",
                manualModelMode = existing?.manualModelMode ?: false,
                cachedModelsJson = existing?.cachedModelsJson ?: "[]",
                cachedModelsAt = existing?.cachedModelsAt ?: 0L,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

        val id = dao.upsert(entity)
        prefs.setApiKey(workspaceId, config.apiKey)
        val finalId = if (existing == null) id else existing.id
        if (existing == null) {
            prefs.activeGlobalAiConfigId = id
        }
        return entity.copy(id = finalId)
    }

    /** 兼容旧调用的简版保存（仅基础字段，其余字段保留默认或已有值）。 */
    suspend fun upsertGlobal(
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
        contextLength: Int = AppConstants.DEFAULT_CONTEXT_LENGTH,
        temperature: Float = AppConstants.DEFAULT_TEMPERATURE,
        providerType: String = "DEEPSEEK",
        configName: String = "默认配置",
        enableToolCall: Boolean = false,
        systemPrompt: String = ""
    ): AiConfigEntity {
        val existing = dao.findGlobal()
        val entity = (existing ?: AiConfigEntity(scope = AppConstants.AiConfigScope.GLOBAL)).copy(
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModel = defaultModel,
            timeoutMs = timeoutMs,
            contextLength = contextLength,
            temperature = temperature,
            providerType = providerType,
            configName = configName,
            enableToolCall = enableToolCall,
            systemPrompt = systemPrompt,
            updatedAt = System.currentTimeMillis()
        )
        val id = dao.upsert(entity)
        prefs.setApiKey(null, apiKey)
        prefs.activeGlobalAiConfigId = if (existing == null) id else existing.id
        return entity.copy(id = if (existing == null) id else existing.id)
    }

    /** 兼容旧调用的简版保存（workspace 作用域）。 */
    suspend fun upsertWorkspace(
        workspaceId: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
        contextLength: Int = AppConstants.DEFAULT_CONTEXT_LENGTH,
        temperature: Float = AppConstants.DEFAULT_TEMPERATURE,
        providerType: String = "DEEPSEEK",
        configName: String = "默认配置",
        enableToolCall: Boolean = false,
        systemPrompt: String = ""
    ): AiConfigEntity {
        val existing = dao.findByWorkspace(workspaceId)
        val entity = (existing ?: AiConfigEntity(
            scope = AppConstants.AiConfigScope.WORKSPACE,
            workspaceId = workspaceId
        )).copy(
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModel = defaultModel,
            timeoutMs = timeoutMs,
            contextLength = contextLength,
            temperature = temperature,
            providerType = providerType,
            configName = configName,
            enableToolCall = enableToolCall,
            systemPrompt = systemPrompt,
            updatedAt = System.currentTimeMillis()
        )
        val id = dao.upsert(entity)
        prefs.setApiKey(workspaceId, apiKey)
        return entity.copy(id = if (existing == null) id else existing.id)
    }

    /** 局部更新：是否启用思考、思考质量等级。 */
    suspend fun updateThinkingSettings(
        configId: Long,
        enableThinking: Boolean,
        thinkingQualityLevel: Int
    ) {
        val entity = dao.findById(configId) ?: return
        dao.update(
            entity.copy(
                enableThinking = enableThinking,
                thinkingQualityLevel = thinkingQualityLevel,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 局部更新：限流与并发参数。 */
    suspend fun updateRateLimitSettings(
        configId: Long,
        requestLimitPerMinute: Int,
        maxConcurrentRequests: Int
    ) {
        val entity = dao.findById(configId) ?: return
        dao.update(
            entity.copy(
                requestLimitPerMinute = requestLimitPerMinute,
                maxConcurrentRequests = maxConcurrentRequests,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 局部更新：多 API Key 池配置。 */
    suspend fun updateApiKeyPool(
        configId: Long,
        apiKeyPoolJson: String,
        useMultipleApiKeys: Boolean
    ) {
        val entity = dao.findById(configId) ?: return
        dao.update(
            entity.copy(
                apiKeyPoolJson = apiKeyPoolJson,
                useMultipleApiKeys = useMultipleApiKeys,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 局部更新：摘要相关设置。 */
    suspend fun updateSummarySettings(
        configId: Long,
        enableSummary: Boolean,
        enableSummaryByMessageCount: Boolean,
        summaryMessageCountThreshold: Int,
        summaryTokenThreshold: Float,
        summaryCustomRules: String
    ) {
        val entity = dao.findById(configId) ?: return
        dao.update(
            entity.copy(
                enableSummary = enableSummary,
                enableSummaryByMessageCount = enableSummaryByMessageCount,
                summaryMessageCountThreshold = summaryMessageCountThreshold,
                summaryTokenThreshold = summaryTokenThreshold,
                summaryCustomRules = summaryCustomRules,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 局部更新：多模态处理开关。 */
    suspend fun updateMultimodalSettings(
        configId: Long,
        enableDirectImageProcessing: Boolean,
        enableDirectAudioProcessing: Boolean,
        enableDirectVideoProcessing: Boolean
    ) {
        val entity = dao.findById(configId) ?: return
        dao.update(
            entity.copy(
                enableDirectImageProcessing = enableDirectImageProcessing,
                enableDirectAudioProcessing = enableDirectAudioProcessing,
                enableDirectVideoProcessing = enableDirectVideoProcessing,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 局部更新：llama.cpp 配置。 */
    suspend fun updateLlamaSettings(
        configId: Long,
        threadCount: Int,
        contextSize: Int,
        batchSize: Int,
        uBatchSize: Int,
        gpuLayers: Int,
        useMmap: Boolean,
        flashAttention: Boolean,
        kvUnified: Boolean,
        offloadKqv: Boolean
    ) {
        val entity = dao.findById(configId) ?: return
        dao.update(
            entity.copy(
                llamaThreadCount = threadCount,
                llamaContextSize = contextSize,
                llamaBatchSize = batchSize,
                llamaUBatchSize = uBatchSize,
                llamaGpuLayers = gpuLayers,
                llamaUseMmap = useMmap,
                llamaFlashAttention = flashAttention,
                llamaKvUnified = kvUnified,
                llamaOffloadKqv = offloadKqv,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateCachedModels(
        configId: Long,
        modelsJson: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        dao.updateCachedModels(configId, modelsJson, timestamp)
    }

    suspend fun updateManualModels(configId: Long, manualModels: String, manualMode: Boolean) {
        dao.updateManualModels(configId, manualModels, manualMode)
    }

    suspend fun upsert(entity: AiConfigEntity): AiConfigEntity {
        val existing = entity.id.takeIf { it > 0 }?.let { dao.findById(it) }
        val id = dao.upsert(entity)
        if (existing == null || existing.apiKey != entity.apiKey) {
            prefs.setApiKey(entity.workspaceId, entity.apiKey)
        }
        return entity.copy(id = if (entity.id > 0) entity.id else id)
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}
