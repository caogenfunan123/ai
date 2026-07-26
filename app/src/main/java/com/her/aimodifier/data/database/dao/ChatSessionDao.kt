package com.her.aimodifier.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.her.aimodifier.data.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChatSessionEntity): Long

    @Update
    suspend fun update(entity: ChatSessionEntity)

    @Query("DELETE FROM chat_session WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM chat_session WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_session WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun observeByWorkspace(workspaceId: String): Flow<List<ChatSessionEntity>>

    @Query("UPDATE chat_session SET messagesJson = :messagesJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMessages(id: Long, messagesJson: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_session SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM chat_session WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    suspend fun findByWorkspace(workspaceId: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_session WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findLatestByWorkspace(workspaceId: String): ChatSessionEntity?

    @Query("DELETE FROM chat_session WHERE workspaceId = :workspaceId")
    suspend fun deleteByWorkspace(workspaceId: String)
}
