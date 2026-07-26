package com.her.aimodifier.workspace

import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.base.constants.PathConstants
import com.her.aimodifier.data.database.entity.ChatSessionEntity
import com.her.aimodifier.data.database.entity.WorkspaceEntity
import com.her.aimodifier.data.repository.ChatSessionRepository
import com.her.aimodifier.data.repository.WorkspaceRepository
import com.her.aimodifier.utils.ShellUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 工作区管理器（门面）。
 *
 * 三种创建方式：
 * 1. [createBlank]：空白项目，创建空目录 + 初始化 Git
 * 2. [importLocal]：导入本地目录，复制源码到 workspace
 * 3. [cloneFromGit]：Git 拉取项目
 *
 * 创建后自动拉起一个 [ChatSessionEntity] 与之绑定。
 */
class WorkspaceManager(
    private val workspaceRepository: WorkspaceRepository,
    private val chatSessionRepository: ChatSessionRepository,
    private val git: GitSync = ShellGitSync()
) {

    /** 工作区创建结果 */
    data class Created(
        val entity: WorkspaceEntity,
        val sessionId: Long
    )

    /** 1. 空白项目 */
    suspend fun createBlank(name: String, defaultModel: String): Created = withContext(Dispatchers.IO) {
        val entity = workspaceRepository.insert(
            name = name,
            source = AppConstants.WorkspaceSource.BLANK,
            localPath = "" // 占位，下面填
        )
        val updated = entity.copy(localPath = PathConstants.workspaceSourceDir(entity.workspaceId).absolutePath)
        workspaceRepository.update(updated)

        // 初始化 Git（可选）
        runCatching { git.init(updated.localPath) }

        // 自动拉起 AI 会话
        val session = chatSessionRepository.create(
            workspaceId = updated.workspaceId,
            title = name,
            modelName = defaultModel
        )
        Created(updated, session.id)
    }

    /** 2. 导入本地目录 */
    suspend fun importLocal(
        name: String,
        sourceDir: String,
        defaultModel: String
    ): Created = withContext(Dispatchers.IO) {
        val source = File(sourceDir)
        require(source.exists() && source.isDirectory) { "源目录不存在：$sourceDir" }

        val entity = workspaceRepository.insert(
            name = name,
            source = AppConstants.WorkspaceSource.LOCAL_IMPORT,
            localPath = "" // 占位
        )
        val target = PathConstants.workspaceSourceDir(entity.workspaceId)
        // 复制源码到 workspace
        source.copyRecursively(target, overwrite = true)

        val updated = entity.copy(localPath = target.absolutePath)
        workspaceRepository.update(updated)

        val session = chatSessionRepository.create(
            workspaceId = updated.workspaceId,
            title = name,
            modelName = defaultModel
        )
        Created(updated, session.id)
    }

    /** 3. Git 拉取项目 */
    suspend fun cloneFromGit(
        name: String,
        gitUrl: String,
        branch: String? = null,
        defaultModel: String
    ): Created = withContext(Dispatchers.IO) {
        val entity = workspaceRepository.insert(
            name = name,
            source = AppConstants.WorkspaceSource.GIT_CLONE,
            localPath = "",
            gitUrl = gitUrl
        )
        val target = PathConstants.workspaceSourceDir(entity.workspaceId)

        // 调用 git clone
        val cloneResult = git.clone(gitUrl, target.absolutePath, branch)
        if (!cloneResult.success) {
            // 失败回滚：删除 workspace 记录
            workspaceRepository.delete(entity.workspaceId)
            throw RuntimeException("Git clone 失败：${cloneResult.error}")
        }

        val updated = entity.copy(localPath = target.absolutePath)
        workspaceRepository.update(updated)

        val session = chatSessionRepository.create(
            workspaceId = updated.workspaceId,
            title = name,
            modelName = defaultModel
        )
        Created(updated, session.id)
    }

    /** 列出所有工作区 */
    fun observeAll() = workspaceRepository.observeAll()

    /** 删除工作区（源码保留，仅删除缓存与数据库记录） */
    suspend fun delete(workspaceId: String) {
        chatSessionRepository.deleteByWorkspace(workspaceId)
        workspaceRepository.delete(workspaceId)
    }

    /** 获取或创建与工作区绑定的最新会话 */
    suspend fun getOrCreateSession(workspaceId: String, defaultModel: String): ChatSessionEntity {
        val existing = chatSessionRepository.findLatestByWorkspace(workspaceId)
        if (existing != null) return existing
        val workspace = workspaceRepository.findById(workspaceId)
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        return chatSessionRepository.create(
            workspaceId = workspaceId,
            title = workspace.name,
            modelName = defaultModel
        )
    }

    /** 切换工作区时返回绑定的最新会话（用于 UI 切换） */
    suspend fun switchWorkspace(workspaceId: String): ChatSessionEntity? {
        return chatSessionRepository.findLatestByWorkspace(workspaceId)
    }

    /** 列出指定工作区下的所有会话 */
    suspend fun listSessions(workspaceId: String): List<ChatSessionEntity> {
        return chatSessionRepository.findByWorkspace(workspaceId)
    }
}

/** Git 同步接口（便于替换实现） */
interface GitSync {
    fun init(targetDir: String): Boolean
    fun clone(url: String, targetDir: String, branch: String?): GitResult
}

data class GitResult(val success: Boolean, val error: String? = null)

/** 通过 ShellUtil 调用系统 git（如容器内 git） */
class ShellGitSync : GitSync {
    override fun init(targetDir: String): Boolean {
        val r = ShellUtil.exec(listOf("git", "init", targetDir))
        return r.exitCode == 0
    }

    override fun clone(url: String, targetDir: String, branch: String?): GitResult {
        val cmd = mutableListOf("git", "clone", "--depth", "1")
        if (branch != null) cmd += listOf("--branch", branch)
        cmd += listOf(url, targetDir)
        val r = ShellUtil.exec(cmd)
        return if (r.exitCode == 0) GitResult(true)
        else GitResult(false, r.stderr.ifEmpty { "git clone 失败，exit=${r.exitCode}" })
    }
}
