package com.her.aimodifier.data.repository

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

    suspend fun upsertGlobal(
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
        contextLength: Int = AppConstants.DEFAULT_CONTEXT_LENGTH,
        temperature: Float = AppConstants.DEFAULT_TEMPERATURE
    ): AiConfigEntity {
        val existing = dao.findGlobal()
        val entity = (existing ?: AiConfigEntity(scope = AppConstants.AiConfigScope.GLOBAL)).copy(
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModel = defaultModel,
            timeoutMs = timeoutMs,
            contextLength = contextLength,
            temperature = temperature,
            updatedAt = System.currentTimeMillis()
        )
        val id = dao.upsert(entity)
        prefs.setApiKey(null, apiKey)
        prefs.activeGlobalAiConfigId = if (existing == null) id else existing.id
        return entity.copy(id = if (existing == null) id else existing.id)
    }

    suspend fun upsertWorkspace(
        workspaceId: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
        contextLength: Int = AppConstants.DEFAULT_CONTEXT_LENGTH,
        temperature: Float = AppConstants.DEFAULT_TEMPERATURE
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
            updatedAt = System.currentTimeMillis()
        )
        val id = dao.upsert(entity)
        prefs.setApiKey(workspaceId, apiKey)
        return entity.copy(id = if (existing == null) id else existing.id)
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