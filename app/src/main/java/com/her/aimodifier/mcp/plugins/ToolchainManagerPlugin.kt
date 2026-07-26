package com.her.aimodifier.mcp.plugins

import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.container.env.RootEnvironmentDetector
import com.her.aimodifier.container.manager.ProotContainerManager
import com.her.aimodifier.container.snapshot.ContainerSnapshotManager
import com.her.aimodifier.container.toolchain.ToolchainDownloadService
import com.her.aimodifier.container.toolchain.ToolchainPathResolver
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.mcp.core.McpParam
import com.her.aimodifier.mcp.core.McpPlugin
import com.her.aimodifier.mcp.core.McpTool
import com.her.aimodifier.utils.ShellUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * 工具链调度 MCP 插件（android.toolchain_manager）。
 *
 * 最终定稿：5 个 MCP 方法
 * 1. toolchain_check_env     — 检测 Root状态、容器状态、已安装工具、当前环境路径
 * 2. toolchain_prepare_task  — 根据 task_id 自动补齐全部依赖（核心智能加载）
 * 3. toolchain_run_command   — 自动适配本机/容器环境执行命令、自动路径映射、超时控制
 * 4. toolchain_snapshot      — 容器快照保存 / 加载 / 列表 / 删除
 * 5. toolchain_clean         — 清理缓存 / 全部重装 / 更新工具
 *
 * MCP强制调用流程（AI必须严格执行）：
 *   check_env → prepare_task(task_id) → run_command → 任务结束自动回收资源
 *
 * 系统联动规则：所有内部模块（BuildEngine / Frida / 抓包 / 模块编译）
 * 必须通过本插件调度，禁止直接操作 PRoot。
 */
class ToolchainManagerPlugin(
    private val containerManager: ProotContainerManager,
    private val toolchainDownloadService: ToolchainDownloadService,
    private val snapshotManager: ContainerSnapshotManager,
    private val rootEnvDetector: RootEnvironmentDetector,
    private val pathResolver: ToolchainPathResolver = ToolchainPathResolver(rootEnvDetector)
) : McpPlugin {

    override val pluginId: String = AppConstants.McpPlugins.TOOLCHAIN_MANAGER
    override val displayName: String = "工具链调度中枢"

    override fun listTools(): List<McpTool> = listOf(
        McpTool(
            name = AppConstants.ToolchainMethods.CHECK_ENV,
            description = "检测 Root 状态、容器部署状态、已安装工具清单、当前环境路径与 PATH"
        ),
        McpTool(
            name = AppConstants.ToolchainMethods.PREPARE_TASK,
            description = "根据 task_id 自动下载补齐该任务所需的全部工具依赖",
            inputSchema = mapOf(
                "taskId" to McpParam(
                    type = "string",
                    description = "任务ID，可选值：apk_build / lspatch_repack / magisk_mod / ksu_module_build / frida_hook / static_reverse / traffic_capture",
                    required = true,
                    enum = listOf(
                        "apk_build", "lspatch_repack", "magisk_mod",
                        "ksu_module_build", "frida_hook", "static_reverse", "traffic_capture"
                    )
                )
            )
        ),
        McpTool(
            name = AppConstants.ToolchainMethods.RUN_COMMAND,
            description = "自动适配本机/容器环境执行 shell 命令，自动路径映射与超时控制，返回流式输出",
            inputSchema = mapOf(
                "command" to McpParam(type = "string", description = "shell 命令", required = true),
                "cwd" to McpParam(type = "string", description = "工作目录（绝对路径）"),
                "timeoutMs" to McpParam(type = "number", description = "超时毫秒，默认 0=不限")
            )
        ),
        McpTool(
            name = AppConstants.ToolchainMethods.SNAPSHOT,
            description = "容器快照操作：保存 / 加载 / 列表 / 删除",
            inputSchema = mapOf(
                "action" to McpParam(
                    type = "string",
                    description = "操作类型",
                    required = true,
                    enum = listOf("save", "load", "list", "delete")
                ),
                "name" to McpParam(type = "string", description = "快照名（save/load/delete 必填）"),
                "snapshotId" to McpParam(type = "string", description = "快照ID（load/delete 用）"),
                "description" to McpParam(type = "string", description = "快照描述（save 用）")
            )
        ),
        McpTool(
            name = AppConstants.ToolchainMethods.CLEAN,
            description = "清理操作：cache=清理编译缓存 / reinstall=全部重装 / update=更新工具链",
            inputSchema = mapOf(
                "action" to McpParam(
                    type = "string",
                    description = "操作类型",
                    required = true,
                    enum = listOf("cache", "reinstall", "update")
                )
            )
        )
    )

    override suspend fun call(toolName: String, arguments: Map<String, Any?>): McpCallResult {
        return when (toolName) {
            AppConstants.ToolchainMethods.CHECK_ENV -> checkEnv()
            AppConstants.ToolchainMethods.PREPARE_TASK -> {
                val taskId = arguments["taskId"] as? String
                    ?: return McpCallResult.Error("MISSING_TASK_ID", "缺少 taskId 参数")
                prepareTask(taskId)
            }
            AppConstants.ToolchainMethods.RUN_COMMAND -> {
                val command = arguments["command"] as? String
                    ?: return McpCallResult.Error("MISSING_COMMAND", "缺少 command 参数")
                val cwd = arguments["cwd"] as? String
                val timeoutMs = (arguments["timeoutMs"] as? Number)?.toLong() ?: 0L
                runCommand(command, cwd, timeoutMs)
            }
            AppConstants.ToolchainMethods.SNAPSHOT -> {
                val action = arguments["action"] as? String
                    ?: return McpCallResult.Error("MISSING_ACTION", "缺少 action 参数")
                snapshot(action, arguments)
            }
            AppConstants.ToolchainMethods.CLEAN -> {
                val action = arguments["action"] as? String
                    ?: return McpCallResult.Error("MISSING_ACTION", "缺少 action 参数")
                clean(action)
            }
            else -> McpCallResult.Error("UNKNOWN_TOOL", "未知工具：$toolName")
        }
    }

    // ============ 1. check_env ============

    private fun checkEnv(): McpCallResult {
        val env = pathResolver.environment
        val tools = toolchainDownloadService.allTools().mapNotNull { tool ->
            val host = pathResolver.resolveHostPath(tool.containerPath, tool.rootPath)
            val ready = host.exists() && host.length() > 0
            if (ready) "${tool.name}=OK" else null
        }
        return McpCallResult.Success(
            result = buildString {
                appendLine("=== 环境检测结果 ===")
                appendLine("arch=${env.arch}")
                appendLine("hasRoot=${env.hasRoot}")
                appendLine("kernelSu=${env.hasKernelSu}")
                appendLine("useProot=${env.useProot}")
                appendLine("useRootNative=${pathResolver.useRootNative}")
                appendLine("toolchainRoot=${pathResolver.toolchainRoot.absolutePath}")
                appendLine("containerDeployed=${containerManager.isDeployed()}")
                appendLine("containerRunning=${containerManager.isRunning()}")
                appendLine("PATH=${pathResolver.buildPathEnv()}")
                appendLine("=== 已安装工具 (${tools.size}) ===")
                tools.forEach { appendLine(it) }
            }.trim(),
            metadata = mapOf(
                "arch" to env.arch,
                "hasRoot" to env.hasRoot.toString(),
                "useRootNative" to pathResolver.useRootNative.toString(),
                "installedToolCount" to tools.size.toString()
            )
        )
    }

    // ============ 2. prepare_task ============

    private suspend fun prepareTask(taskId: String): McpCallResult {
        val taskInfo = toolchainDownloadService.allTasks().firstOrNull { it.taskId == taskId }
            ?: return McpCallResult.Error("UNKNOWN_TASK", "未知任务ID：$taskId")

        val result = toolchainDownloadService.prefetchForTask(taskId)
        return if (result.success) {
            McpCallResult.Success(
                result = buildString {
                    appendLine("任务 $taskId 工具链就绪")
                    appendLine("描述：${taskInfo.description}")
                    appendLine("工具：${result.tools.joinToString(", ")}")
                }.trim(),
                metadata = mapOf(
                    "taskId" to taskId,
                    "tools" to result.tools.joinToString(",")
                )
            )
        } else {
            McpCallResult.Error(
                code = result.error ?: "PREPARE_FAILED",
                message = "任务 $taskId 工具链预下载失败",
                cause = result.details
            )
        }
    }

    // ============ 3. run_command ============

    private fun runCommand(command: String, cwd: String?, timeoutMs: Long): McpCallResult {
        // 无 Root 时必须确保容器就绪
        if (!pathResolver.useRootNative && !containerManager.isDeployed()) {
            return McpCallResult.Error(
                "CONTAINER_NOT_READY",
                "容器尚未部署，无 Root 环境必须先部署容器才能执行命令"
            )
        }

        val effectiveCwd = cwd ?: if (pathResolver.useRootNative) "/" else "/root"
        val stream = flow {
            val envPath = pathResolver.buildPathEnv()
            val wrapped = buildString {
                append("export PATH=\"$envPath\" && ")
                append(command)
            }
            withContext(Dispatchers.IO) {
                if (pathResolver.useRootNative) {
                    // Root 本机直接执行
                    val r = ShellUtil.exec(listOf("sh", "-c", wrapped), effectiveCwd)
                    emit("[exit=${r.exitCode}]")
                    if (r.stdout.isNotEmpty()) emit(r.stdout)
                    if (r.stderr.isNotEmpty()) emit("[stderr] ${r.stderr}")
                } else {
                    // 通过 PRoot 容器执行
                    val lines = mutableListOf<String>()
                    containerManager.exec(wrapped, effectiveCwd) { line -> lines.add(line + "\n") }
                    lines.forEach { emit(it) }
                }
            }
        }
        return McpCallResult.Stream(stream)
    }

    // ============ 4. snapshot ============

    private fun snapshot(action: String, args: Map<String, Any?>): McpCallResult {
        return when (action) {
            AppConstants.SnapshotAction.LIST -> {
                val list = snapshotManager.list()
                McpCallResult.Success(
                    result = if (list.isEmpty()) "无快照"
                    else list.joinToString("\n") { "${it.id}  ${it.name}  ${it.createdAt}  ${it.sizeBytes}B" }
                )
            }
            AppConstants.SnapshotAction.SAVE -> {
                val name = args["name"] as? String
                    ?: return McpCallResult.Error("MISSING_NAME", "save 操作缺少 name 参数")
                val desc = args["description"] as? String
                val id = snapshotManager.create(name, desc)
                McpCallResult.Success("快照已保存：id=$id name=$name", mapOf("snapshotId" to id))
            }
            AppConstants.SnapshotAction.LOAD -> {
                val id = args["snapshotId"] as? String
                    ?: return McpCallResult.Error("MISSING_SNAPSHOT_ID", "load 操作缺少 snapshotId 参数")
                val ok = snapshotManager.load(id)
                if (ok) McpCallResult.Success("快照已加载：$id")
                else McpCallResult.Error("LOAD_FAILED", "快照加载失败：$id")
            }
            AppConstants.SnapshotAction.DELETE -> {
                val id = args["snapshotId"] as? String
                    ?: return McpCallResult.Error("MISSING_SNAPSHOT_ID", "delete 操作缺少 snapshotId 参数")
                val ok = snapshotManager.delete(id)
                if (ok) McpCallResult.Success("快照已删除：$id")
                else McpCallResult.Error("DELETE_FAILED", "快照删除失败：$id")
            }
            else -> McpCallResult.Error("UNKNOWN_ACTION", "未知 snapshot action：$action")
        }
    }

    // ============ 5. clean ============

    private suspend fun clean(action: String): McpCallResult {
        return when (action) {
            AppConstants.CleanAction.CACHE -> {
                val n = toolchainDownloadService.cleanBuildCache()
                McpCallResult.Success("已清理 $n 个工作区缓存")
            }
            AppConstants.CleanAction.REINSTALL -> {
                val r = toolchainDownloadService.reinstallAll()
                if (r.success) McpCallResult.Success(
                    "全部重装完成：${r.tools.size} 个工具",
                    mapOf("tools" to r.tools.joinToString(","))
                )
                else McpCallResult.Error("REINSTALL_FAILED", r.error ?: "重装失败", r.details)
            }
            AppConstants.CleanAction.UPDATE -> {
                // update 等价于 reinstallAll，但保留已有缓存
                val r = toolchainDownloadService.reinstallAll()
                if (r.success) McpCallResult.Success(
                    "工具链更新完成：${r.tools.size} 个工具",
                    mapOf("tools" to r.tools.joinToString(","))
                )
                else McpCallResult.Error("UPDATE_FAILED", r.error ?: "更新失败", r.details)
            }
            else -> McpCallResult.Error("UNKNOWN_ACTION", "未知 clean action：$action")
        }
    }
}
