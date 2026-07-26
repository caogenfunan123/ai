package com.her.aimodifier.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.data.database.entity.AiConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AiConfigEntity): Long

    @Update
    suspend fun update(entity: AiConfigEntity)

    @Query("SELECT * FROM ai_config WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): AiConfigEntity?

    @Query("DELETE FROM ai_config WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 全局配置（至多一条） */
    @Query("SELECT * FROM ai_config WHERE scope = 'global' LIMIT 1")
    suspend fun findGlobal(): AiConfigEntity?

    @Query("SELECT * FROM ai_config WHERE scope = 'global' LIMIT 1")
    fun observeGlobal(): Flow<AiConfigEntity?>

    /** workspace 独立配置 */
    @Query("SELECT * FROM ai_config WHERE scope = 'workspace' AND workspaceId = :workspaceId LIMIT 1")
    suspend fun findByWorkspace(workspaceId: String): AiConfigEntity?

    @Query("SELECT * FROM ai_config WHERE scope = 'workspace' AND workspaceId = :workspaceId LIMIT 1")
    fun observeByWorkspace(workspaceId: String): Flow<AiConfigEntity?>

    /** 所有配置列表 */
    @Query("SELECT * FROM ai_config ORDER BY scope, updatedAt DESC")
    fun observeAll(): Flow<List<AiConfigEntity>>

    /**
     * 按优先级查询：workspace 优先，回退 global。
     * 在 Repository 层做 fallback；这里仅提供单条查询。
     */
    @Query("SELECT * FROM ai_config WHERE scope = :scope LIMIT 1")
    suspend fun findByScope(scope: String = AppConstants.AiConfigScope.GLOBAL): AiConfigEntity?

    /** 局部更新缓存模型列表 */
    @Query("UPDATE ai_config SET cachedModelsJson = :json, cachedModelsAt = :ts, updatedAt = :ts WHERE id = :id")
    suspend fun updateCachedModels(id: Long, json: String, ts: Long = System.currentTimeMillis())

    /** 局部更新手动模型列表与开关 */
    @Query("UPDATE ai_config SET manualModels = :models, manualModelMode = :mode, updatedAt = :ts WHERE id = :id")
    suspend fun updateManualModels(id: Long, models: String, mode: Boolean, ts: Long = System.currentTimeMillis())
}
