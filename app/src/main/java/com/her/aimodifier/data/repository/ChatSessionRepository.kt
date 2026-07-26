package com.her.aimodifier.data.repository

import com.her.aimodifier.data.database.dao.ChatSessionDao
import com.her.aimodifier.data.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * AI 会话仓库。
 *
 * - 会话绑定 workspaceId
 * - 消息以 JSON 持久化；运行时编辑消息列表后调用 [updateMessages]
 */
class ChatSessionRepository(private val dao: ChatSessionDao) {

    fun observeByWorkspace(workspaceId: String): Flow<List<ChatSessionEntity>> =
        dao.observeByWorkspace(workspaceId)

    suspend fun findById(id: Long): ChatSessionEntity? = dao.findById(id)

    /**
     * 在指定 workspace 下创建新会话。
     * 调用方负责保证 workspaceId 存在。
     */
    suspend fun create(
        workspaceId: String,
        title: String,
        modelName: String,
        useLocalModel: Boolean = false,
        systemPromptOverride: String? = null
    ): ChatSessionEntity {
        val entity = ChatSessionEntity(
            workspaceId = workspaceId,
            title = title,
            modelName = modelName,
            useLocalModel = useLocalModel,
            systemPromptOverride = systemPromptOverride
        )
        val id = dao.insert(entity)
        return entity.copy(id = id)
    }

    suspend fun update(entity: ChatSessionEntity) {
        dao.update(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateMessages(id: Long, messagesJson: String) {
        dao.updateMessages(id, messagesJson)
    }

    suspend fun updateTitle(id: Long, title: String) {
        dao.updateTitle(id, title)
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    suspend fun findByWorkspace(workspaceId: String): List<ChatSessionEntity> =
        dao.findByWorkspace(workspaceId)

    suspend fun findLatestByWorkspace(workspaceId: String): ChatSessionEntity? =
        dao.findLatestByWorkspace(workspaceId)

    suspend fun deleteByWorkspace(workspaceId: String) {
        dao.deleteByWorkspace(workspaceId)
    }
}
