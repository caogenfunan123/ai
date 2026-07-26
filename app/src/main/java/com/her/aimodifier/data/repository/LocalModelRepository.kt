package com.her.aimodifier.data.repository

import com.her.aimodifier.data.database.dao.LocalModelDao
import com.her.aimodifier.data.database.entity.LocalModelEntity
import kotlinx.coroutines.flow.Flow

class LocalModelRepository(private val dao: LocalModelDao) {

    fun observeAll(): Flow<List<LocalModelEntity>> = dao.observeAll()

    suspend fun findById(id: Long): LocalModelEntity? = dao.findById(id)

    suspend fun findByPath(filePath: String): LocalModelEntity? = dao.findByPath(filePath)

    suspend fun upsert(entity: LocalModelEntity): Long = dao.upsert(entity)

    suspend fun update(entity: LocalModelEntity) = dao.update(entity)

    suspend fun setLoaded(id: Long, loaded: Boolean) {
        dao.unloadAll()
        if (loaded) dao.setLoaded(id, true)
    }

    suspend fun unloadAll() {
        dao.unloadAll()
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    suspend fun updateHashAndStatus(id: Long, hash: String?, status: String) {
        dao.updateHashAndStatus(id, hash, status)
    }

    suspend fun totalModelSize(): Long = dao.totalModelSize() ?: 0L
}
