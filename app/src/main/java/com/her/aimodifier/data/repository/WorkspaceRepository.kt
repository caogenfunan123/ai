package com.her.aimodifier.data.repository

import com.her.aimodifier.base.constants.PathConstants
import com.her.aimodifier.data.database.dao.WorkspaceDao
import com.her.aimodifier.data.database.entity.WorkspaceEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 工作区数据仓库。
 *
 * 业务逻辑（新建/导入/Git 拉取）在 [com.her.aimodifier.workspace.WorkspaceManager] 中编排，
 * 本 Repository 仅负责数据持久化与目录创建。
 */
class WorkspaceRepository(private val dao: WorkspaceDao) {

    fun observeAll(): Flow<List<WorkspaceEntity>> = dao.observeAll()

    suspend fun findById(workspaceId: String): WorkspaceEntity? = dao.findByWorkspaceId(workspaceId)

    /**
     * 创建一个新的 workspaceId（业务 UUID），并预创建对应目录。
     * 数据库记录由 [com.her.aimodifier.workspace.WorkspaceManager] 调用 [insert] 写入。
     */
    fun generateWorkspaceId(): String = UUID.randomUUID().toString().replace("-", "")

    suspend fun insert(
        name: String,
        source: String,
        localPath: String,
        gitUrl: String? = null,
        aiConfigId: Long? = null
    ): WorkspaceEntity {
        val workspaceId = generateWorkspaceId()
        // 确保目录存在
        PathConstants.workspaceSourceDir(workspaceId).mkdirs()
        PathConstants.workspaceCacheDir(workspaceId).mkdirs()
        val entity = WorkspaceEntity(
            workspaceId = workspaceId,
            name = name,
            source = source,
            gitUrl = gitUrl,
            localPath = localPath,
            aiConfigId = aiConfigId
        )
        val id = dao.insert(entity)
        return entity.copy(id = id)
    }

    suspend fun update(entity: WorkspaceEntity) {
        dao.update(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(workspaceId: String) {
        dao.deleteByWorkspaceId(workspaceId)
        // 删除关联目录（保守起见，仅删除缓存，源码保留待用户手动处理）
        PathConstants.workspaceCacheDir(workspaceId).deleteRecursively()
    }

    suspend fun count(): Int = dao.count()
}
