package com.her.aimodifier.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.her.aimodifier.data.database.entity.WorkspaceEntity
import com.her.aimodifier.data.repository.AiConfigRepository
import com.her.aimodifier.data.repository.WorkspaceRepository
import com.her.aimodifier.di.ServiceLocator
import com.her.aimodifier.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 工作区管理 ViewModel（最终定稿）。
 *
 * 支持：
 * - 列出所有工作区
 * - 新建空白项目 / 导入本地目录 / Git 拉取
 * - 删除工作区
 */
class WorkspaceViewModel(
    private val workspaceManager: WorkspaceManager = com.her.aimodifier.di.ServiceLocator.let {
        // ServiceLocator 暂未导出 workspaceManager，运行时构造
        WorkspaceManager(
            it.workspaceRepository,
            it.chatSessionRepository
        )
    },
    private val workspaceRepository: WorkspaceRepository = ServiceLocator.workspaceRepository,
    private val aiConfigRepository: AiConfigRepository = ServiceLocator.aiConfigRepository
) : ViewModel() {

    val workspaces: StateFlow<List<WorkspaceEntity>> =
        workspaceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 新建空白项目 */
    fun createBlank(name: String, defaultModel: String = "gpt-4o-mini") {
        viewModelScope.launch {
            _creating.value = true
            _error.value = null
            runCatching {
                workspaceManager.createBlank(name, defaultModel)
            }.onFailure { _error.value = it.message }
            _creating.value = false
        }
    }

    /** 导入本地目录 */
    fun importLocal(name: String, sourceDir: String, defaultModel: String = "gpt-4o-mini") {
        viewModelScope.launch {
            _creating.value = true
            _error.value = null
            runCatching {
                workspaceManager.importLocal(name, sourceDir, defaultModel)
            }.onFailure { _error.value = it.message }
            _creating.value = false
        }
    }

    /** Git 拉取项目 */
    fun cloneFromGit(name: String, gitUrl: String, branch: String?, defaultModel: String = "gpt-4o-mini") {
        viewModelScope.launch {
            _creating.value = true
            _error.value = null
            runCatching {
                workspaceManager.cloneFromGit(name, gitUrl, branch, defaultModel)
            }.onFailure { _error.value = it.message }
            _creating.value = false
        }
    }

    fun delete(workspaceId: String) {
        viewModelScope.launch {
            workspaceManager.delete(workspaceId)
        }
    }

    fun clearError() { _error.value = null }
}
