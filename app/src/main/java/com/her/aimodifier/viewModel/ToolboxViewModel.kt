package com.her.aimodifier.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.her.aimodifier.container.env.EnvironmentInfo
import com.her.aimodifier.container.manager.ProotContainerManager
import com.her.aimodifier.container.snapshot.ContainerSnapshotManager
import com.her.aimodifier.container.toolchain.MirrorConfig
import com.her.aimodifier.container.toolchain.ToolchainDownloadService
import com.her.aimodifier.container.toolchain.ToolchainPathResolver
import com.her.aimodifier.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 工具箱 ViewModel（最终定稿）。
 *
 * 功能：
 * - 显示环境检测结果
 * - 容器部署状态
 * - 一键清理编译缓存
 * - 一键更新工具链
 * - 容器快照列表
 * - 镜像源配置（自定义 URL / 连接测试 / 保存）
 * - 容器健康状态监控（部署进度 / 运行状态 / 自动重启 / 自动休眠）
 */
class ToolboxViewModel(
    private val pathResolver: ToolchainPathResolver = ServiceLocator.pathResolver,
    private val downloadService: ToolchainDownloadService = ServiceLocator.toolchainDownloadService,
    private val snapshotManager: ContainerSnapshotManager = ServiceLocator.snapshotManager,
    private val containerManager: ProotContainerManager = ServiceLocator.containerManager,
    private val mirrorConfig: MirrorConfig = ServiceLocator.mirrorConfig
) : ViewModel() {

    private val _env = MutableStateFlow<EnvironmentInfo?>(null)
    val env: StateFlow<EnvironmentInfo?> = _env.asStateFlow()

    private val _deployed = MutableStateFlow(false)
    val deployed: StateFlow<Boolean> = _deployed.asStateFlow()

    private val _snapshots = MutableStateFlow<List<ContainerSnapshotManager.SnapshotMeta>>(emptyList())
    val snapshots: StateFlow<List<ContainerSnapshotManager.SnapshotMeta>> = _snapshots.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _installedTools = MutableStateFlow<List<String>>(emptyList())
    val installedTools: StateFlow<List<String>> = _installedTools.asStateFlow()

    // ---- 镜像源配置 ----

    private val _mirrorUrl = MutableStateFlow("")
    val mirrorUrl: StateFlow<String> = _mirrorUrl.asStateFlow()

    private val _mirrorTestResult = MutableStateFlow<MirrorTestResult?>(null)
    val mirrorTestResult: StateFlow<MirrorTestResult?> = _mirrorTestResult.asStateFlow()

    private val _mirrorTesting = MutableStateFlow(false)
    val mirrorTesting: StateFlow<Boolean> = _mirrorTesting.asStateFlow()

    // ---- 容器状态 ----

    val containerStatus: StateFlow<ProotContainerManager.ContainerStatus> = containerManager.containerStatus
    val healthState: StateFlow<ProotContainerManager.HealthState> = containerManager.healthState
    val deployProgress: StateFlow<Int> = containerManager.deployProgress

    init {
        refreshEnv()
        refreshSnapshots()
        loadMirrorUrl()
    }

    fun refreshEnv() {
        _env.value = pathResolver.environment
        _deployed.value = containerManager.isDeployed()
        _installedTools.value = downloadService.allTools().mapNotNull { tool ->
            val host = pathResolver.resolveHostPath(tool.containerPath, tool.rootPath)
            if (host.exists() && host.length() > 0) tool.name else null
        }
    }

    fun refreshSnapshots() {
        _snapshots.value = snapshotManager.list()
    }

    /** 部署容器（无 Root 时） */
    fun deployContainer() {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                containerManager.init { /* progress */ }
                containerManager.start()
            }.onSuccess {
                _message.value = "容器部署完成"
                refreshEnv()
            }.onFailure {
                _message.value = "容器部署失败：${it.message}"
            }
            _busy.value = false
        }
    }

    /** 一键清理编译缓存 */
    fun cleanCache() {
        viewModelScope.launch {
            _busy.value = true
            val n = downloadService.cleanBuildCache()
            _message.value = "已清理 $n 个工作区缓存"
            _busy.value = false
        }
    }

    /** 一键更新工具链 */
    fun updateToolchain() {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                downloadService.reinstallAll { msg -> _message.value = msg }
            }.onSuccess {
                _message.value = "工具链更新完成"
                refreshEnv()
            }.onFailure {
                _message.value = "更新失败：${it.message}"
            }
            _busy.value = false
        }
    }

    /** 创建快照 */
    fun createSnapshot(name: String, description: String? = null) {
        viewModelScope.launch {
            snapshotManager.create(name, description)
            refreshSnapshots()
            _message.value = "快照已创建：$name"
        }
    }

    /** 加载快照 */
    fun loadSnapshot(snapshotId: String) {
        viewModelScope.launch {
            val ok = snapshotManager.load(snapshotId)
            _message.value = if (ok) "快照已加载" else "快照加载失败"
        }
    }

    /** 删除快照 */
    fun deleteSnapshot(snapshotId: String) {
        viewModelScope.launch {
            snapshotManager.delete(snapshotId)
            refreshSnapshots()
        }
    }

    fun clearMessage() { _message.value = null }

    // ---- 镜像源配置 ----

    private fun loadMirrorUrl() {
        viewModelScope.launch {
            val saved = mirrorConfig.getCustomMirror()
            _mirrorUrl.value = saved ?: ""
        }
    }

    fun onMirrorUrlChange(url: String) {
        _mirrorUrl.value = url
    }

    fun testMirrorConnection(url: String) {
        viewModelScope.launch {
            _mirrorTesting.value = true
            _mirrorTestResult.value = null
            val ok = mirrorConfig.testConnection(url)
            _mirrorTestResult.value = MirrorTestResult(
                url = url,
                success = ok,
                timestamp = System.currentTimeMillis()
            )
            _mirrorTesting.value = false
        }
    }

    fun saveMirrorUrl(url: String) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                mirrorConfig.saveCustomMirror(url)
            }.onSuccess {
                _message.value = "镜像源已保存"
                loadMirrorUrl()
            }.onFailure {
                _message.value = "保存失败：${it.message}"
            }
            _busy.value = false
        }
    }

    fun resetMirror() {
        viewModelScope.launch {
            mirrorConfig.reset()
            _mirrorUrl.value = ""
            _mirrorTestResult.value = null
            _message.value = "已重置为默认镜像源"
        }
    }

    // ---- 容器状态控制 ----

    fun setAutoRestart(enabled: Boolean) {
        containerManager.setAutoRestart(enabled)
    }

    fun setAutoSleep(enabled: Boolean) {
        containerManager.setAutoSleep(enabled)
    }

    fun stopContainer() {
        viewModelScope.launch {
            containerManager.stop()
            _message.value = "容器已停止"
        }
    }

    fun restartContainer() {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                containerManager.stop()
                containerManager.init { /* progress */ }
                containerManager.start()
            }.onSuccess {
                _message.value = "容器已重启"
                refreshEnv()
            }.onFailure {
                _message.value = "容器重启失败：${it.message}"
            }
            _busy.value = false
        }
    }

    /** 镜像源测试结果 */
    data class MirrorTestResult(
        val url: String,
        val success: Boolean,
        val timestamp: Long
    )
}