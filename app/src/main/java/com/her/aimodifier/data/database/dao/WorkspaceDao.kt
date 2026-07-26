package com.her.aimodifier.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.her.aimodifier.data.database.entity.WorkspaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WorkspaceEntity): Long

    @Update
    suspend fun update(entity: WorkspaceEntity)

    @Query("DELETE FROM workspace WHERE workspaceId = :workspaceId")
    suspend fun deleteByWorkspaceId(workspaceId: String)

    @Query("SELECT * FROM workspace WHERE workspaceId = :workspaceId LIMIT 1")
    suspend fun findByWorkspaceId(workspaceId: String): WorkspaceEntity?

    @Query("SELECT * FROM workspace ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT COUNT(*) FROM workspace")
    suspend fun count(): Int
}
