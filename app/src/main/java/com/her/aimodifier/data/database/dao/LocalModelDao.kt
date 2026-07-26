package com.her.aimodifier.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.her.aimodifier.data.database.entity.LocalModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalModelEntity): Long

    @Update
    suspend fun update(entity: LocalModelEntity)

    @Query("DELETE FROM local_model WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM local_model WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): LocalModelEntity?

    @Query("SELECT * FROM local_model WHERE filePath = :filePath LIMIT 1")
    suspend fun findByPath(filePath: String): LocalModelEntity?

    @Query("SELECT * FROM local_model ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<LocalModelEntity>>

    @Query("UPDATE local_model SET loaded = :loaded WHERE id = :id")
    suspend fun setLoaded(id: Long, loaded: Boolean)

    @Query("UPDATE local_model SET loaded = 0")
    suspend fun unloadAll()

    @Query("UPDATE local_model SET status = :status, sha256 = :hash WHERE id = :id")
    suspend fun updateHashAndStatus(id: Long, hash: String?, status: String)

    @Query("SELECT SUM(sizeBytes) FROM local_model")
    suspend fun totalModelSize(): Long?
}
