package com.her.aimodifier.container.manager

import android.content.Context
import com.her.aimodifier.base.constants.PathConstants
import com.her.aimodifier.container.deploy.ProotBinaryInstaller
import com.her.aimodifier.container.deploy.RootfsDeployer
import com.her.aimodifier.container.toolchain.ToolchainDownloadService
import com.her.aimodifier.filesystem.ProotPathMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * PRoot 容器管理器。
 *
 * 生命周期：
 * - [init] / [start]：部署 rootfs + 启动容器进程
 * - [exec]：在容器内执行命令
 * - [stop]：停止容器
 * - [destroy]：销毁容器与 rootfs
 *
 * 新增能力：
 * - 自动重启：容器异常退出时自动重新部署
 * - 空闲自动休眠：30s 无操作后自动停止容器
 * - 健康状态监控：暴露 healthState 供 UI 订阅
 * - 部署进度回调：0-100 进度上报
 */
class ProotContainerManager(
    private val context: Context,
    private val toolchainDownloadService: ToolchainDownloadService
) {
    private val deployer = RootfsDeployer(context)
    private val binaryInstaller = ProotBinaryInstaller(context)
    private val pathMapper = ProotPathMapper()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var running = false

    @Volatile
    private var autoRestart = true

    @Volatile
    private var autoSleep = true

    @Volatile
    private var idleTimeoutMs = 30_000L

    private var idleJob: Job? = null

    private val _healthState = MutableStateFlow(HealthState.STOPPED)
    val healthState: StateFlow<HealthState> = _healthState.asStateFlow()

    private val _deployProgress = MutableStateFlow(0)
    val deployProgress: StateFlow<Int> = _deployProgress.asStateFlow()

    private val _containerStatus = MutableStateFlow(ContainerStatus())
    val containerStatus: StateFlow<ContainerStatus> = _containerStatus.asStateFlow()

    /** 容器是否已部署 */
    fun isDeployed(): Boolean = deployer.isDeployed()

    /** 容器是否在运行中 */
    fun isRunning(): Boolean = running

    /** 是否启用自动重启 */
    fun setAutoRestart(enabled: Boolean) {
        autoRestart = enabled
    }

    /** 是否启用空闲自动休眠 */
    fun setAutoSleep(enabled: Boolean) {
        autoSleep = enabled
        if (enabled && running) {
            resetIdleTimer()
        } else {
            cancelIdleTimer()
        }
    }

    /** 初始化：部署 rootfs + 安装 PRoot 二进制（幂等） */
    suspend fun init(progress: (Int) -> Unit = {}) {
        _healthState.value = HealthState.DEPLOYING
        _deployProgress.value = 0
        try {
            binaryInstaller.install()
            progress(10)
            _deployProgress.value = 10

            deployer.deploy { p ->
                val mapped = 10 + (p * 80 / 100)
                progress(mapped)
                _deployProgress.value = mapped
            }
            progress(100)
            _deployProgress.value = 100
            _healthState.value = HealthState.HEALTHY
        } catch (t: Throwable) {
            _healthState.value = HealthState.ERROR
            throw t
        }
    }

    /** 启动容器（实际由首次 exec 触发，本方法仅状态标记 + init.sh 自检） */
    suspend fun start() {
        if (!isDeployed()) init()
        // 执行 init.sh 自检
        exec("/root/init.sh", "/root") { /* ignore */ }
        running = true
        _healthState.value = HealthState.HEALTHY
        updateStatus()
        resetIdleTimer()
    }

    /** 停止容器（无独立进程时仅状态重置） */
    fun stop() {
        running = false
        _healthState.value = HealthState.STOPPED
        cancelIdleTimer()
        updateStatus()
    }

    /**
     * 在容器内执行命令，按行回调输出。
     *
     * @param command 容器视角的命令
     * @param cwd 容器视角的工作目录
     * @param onLine 每行输出回调
     * @return 退出码
     */
    fun exec(command: String, cwd: String = "/root", onLine: (String) -> Unit): Int {
        if (!isDeployed()) {
            onLine("[ERROR] 容器未部署")
            return -1
        }
        val prootCmd = buildProotCommand(command, cwd)
        val process = Runtime.getRuntime().exec(prootCmd)
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach(onLine)
        }
        process.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { onLine("[stderr] $it") }
        }
        val code = process.waitFor()
        running = true
        resetIdleTimer()

        if (code != 0 && autoRestart) {
            scheduleAutoRestart()
        }

        return code
    }

    /**
     * 构造 PRoot 命令。
     *
     * 关键参数：
     * - -r <rootfs>：指定 rootfs 根
     * - -b <host:container>：bind mount
     * - -w <cwd>：工作目录
     * - -l <lib_path>：添加额外的库搜索路径（供 PRoot 宿主侧查找依赖）
     * - /bin/sh -c ...：容器内执行的实际命令
     *
     * Android 动态链接器路径兼容：
     * - /system/bin/linker (Android 8+)
     * - /system/bin/linker64 (部分 64 位设备)
     * - 通过 proot-loader.so 桥接 Linux ELF → Android 环境
     */
    private fun buildProotCommand(command: String, cwd: String): Array<String> {
        val prootBin = File(context.filesDir, "tools/bin/proot").absolutePath
        val loaderBin = File(context.filesDir, "tools/bin/proot-loader.so").absolutePath
        val libDir = File(context.filesDir, "tools/lib").absolutePath
        val rootfs = PathConstants.rootfsRoot.absolutePath

        val linker = resolveLinker()

        val binds = listOf(
            "${PathConstants.workspaceRoot.absolutePath}:${PathConstants.Container.WORKSPACE_MOUNT}",
            "${PathConstants.localModelDir.absolutePath}:${PathConstants.Container.MODELS}",
            "/dev:/dev",
            "/proc:/proc",
            "/sys:/sys"
        ).flatMap { listOf("-b", it) }.toTypedArray()

        return arrayOf(
            linker,
            loaderBin,
            prootBin,
            "-r", rootfs,
            *binds,
            "-l", libDir,
            "-w", cwd,
            "/bin/sh", "-c", command
        )
    }

    /** 解析 Android 动态链接器路径 */
    private fun resolveLinker(): String {
        val candidates = listOf("/system/bin/linker64", "/system/bin/linker")
        return candidates.firstOrNull { File(it).exists() } ?: "/system/bin/linker"
    }

    /** 销毁容器：停止 + 删除 rootfs */
    fun destroy() {
        stop()
        deployer.destroy()
        _deployProgress.value = 0
        updateStatus()
    }

    // ---- 自动重启逻辑 ----

    private fun scheduleAutoRestart() {
        _healthState.value = HealthState.RESTARTING
        managerScope.launch {
            delay(1000)
            if (!isActive) return@launch
            runCatching {
                if (!isDeployed()) {
                    init { /* progress already tracked */ }
                }
                start()
            }.onFailure {
                _healthState.value = HealthState.ERROR
            }
        }
    }

    // ---- 空闲自动休眠 ----

    private fun resetIdleTimer() {
        if (!autoSleep) return
        cancelIdleTimer()
        idleJob = managerScope.launch {
            delay(idleTimeoutMs)
            if (!isActive) return@launch
            if (running) {
                stop()
            }
        }
    }

    private fun cancelIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    // ---- 状态更新 ----

    private fun updateStatus() {
        _containerStatus.value = ContainerStatus(
            isDeployed = isDeployed(),
            isRunning = running,
            healthState = _healthState.value,
            deployProgress = _deployProgress.value,
            autoRestart = autoRestart,
            autoSleep = autoSleep
        )
    }

    /** 关闭管理器，取消所有协程 */
    fun shutdown() {
        cancelIdleTimer()
        managerScope.cancel()
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 30_000L
    }

    /** 容器健康状态枚举 */
    enum class HealthState {
        STOPPED,
        DEPLOYING,
        HEALTHY,
        RESTARTING,
        ERROR
    }

    /** 容器状态快照 */
    data class ContainerStatus(
        val isDeployed: Boolean = false,
        val isRunning: Boolean = false,
        val healthState: HealthState = HealthState.STOPPED,
        val deployProgress: Int = 0,
        val autoRestart: Boolean = true,
        val autoSleep: Boolean = true
    )
}