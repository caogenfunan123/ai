package com.her.aimodifier.container.toolchain

import android.content.Context
import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.base.constants.PathConstants
import com.her.aimodifier.utils.DownloadUtil
import com.her.aimodifier.utils.HashUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 工具链下载服务（最终定稿版）。
 *
 * 职责：
 * 1. 读取 assets/manifest/toolchain_manifest.json 中的任务→工具依赖
 * 2. 按任务 ID 预下载所需工具（[prefetchForTask]）
 * 3. 双路径部署：Root 设备部署到 rootToolchainDir，无 Root 部署到容器 /opt/toolchain
 * 4. 强制 SHA256 校验、损坏自动删除重试
 * 5. 架构守护：禁止 x86/x86_64 二进制
 *
 * 网络下载逻辑由 App 层实现（本类），容器只负责部署/校验/执行。
 */
class ToolchainDownloadService(
    private val context: Context,
    private val pathResolver: ToolchainPathResolver = ToolchainPathResolver(),
    private val mirrorConfig: MirrorConfig = MirrorConfig()
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ToolEntry(
        val name: String,
        val category: String,
        val containerPath: String,
        val rootPath: String
    )

    @Serializable
    data class TaskEntry(
        val taskId: String,
        val description: String,
        val tools: List<String>
    )

    @Serializable
    data class Manifest(
        val tools: List<ToolEntry>,
        val tasks: List<TaskEntry>,
        val toolVersions: Map<String, List<String>> = emptyMap()
    )

    data class PrefetchResult(
        val success: Boolean,
        val tools: List<String>,
        val error: String? = null,
        val details: String? = null
    )

    private val manifest: Manifest? by lazy { loadManifest() }

    private fun loadManifest(): Manifest? {
        return runCatching {
            context.assets.open("manifest/toolchain_manifest.json").use { input ->
                json.decodeFromString(Manifest.serializer(), input.bufferedReader().readText())
            }
        }.getOrNull()
    }

    /** 获取任务依赖的工具清单 */
    fun toolsForTask(taskId: String): List<ToolEntry> {
        val m = manifest ?: return emptyList()
        val task = m.tasks.firstOrNull { it.taskId == taskId } ?: return emptyList()
        return m.tools.filter { it.name in task.tools }
    }

    /** 列出全部工具 */
    fun allTools(): List<ToolEntry> = manifest?.tools ?: emptyList()

    /** 列出全部任务 */
    fun allTasks(): List<TaskEntry> = manifest?.tasks ?: emptyList()

    /** 工具版本清单（用于多版本切换） */
    fun toolVersions(): Map<String, List<String>> = manifest?.toolVersions ?: emptyMap()

    /**
     * 按任务 ID 预下载所需工具。
     *
     * 流程（每个工具）：
     * 1. 解析当前环境的目标路径
     * 2. 已存在且 SHA256 校验通过 → 跳过
     * 3. 否则下载（断点续传）+ SHA256 校验
     * 4. 校验失败 → 删除 + 重试一次
     * 5. 标记可执行
     */
    suspend fun prefetchForTask(taskId: String): PrefetchResult = withContext(Dispatchers.IO) {
        val tools = toolsForTask(taskId)
        if (tools.isEmpty()) {
            return@withContext PrefetchResult(
                success = false,
                tools = emptyList(),
                error = "NO_TOOLS_FOR_TASK",
                details = "任务 $taskId 未配置工具依赖，或 manifest 读取失败"
            )
        }

        // 架构守护：仅支持 arm64-v8a
        val arch = pathResolver.environment.arch
        if (arch != "arm64") {
            return@withContext PrefetchResult(
                success = false,
                tools = emptyList(),
                error = "UNSUPPORTED_ARCH",
                details = "仅支持 ARM64 设备，当前架构：$arch"
            )
        }

        val ok = mutableListOf<String>()
        for (tool in tools) {
            val target = pathResolver.resolveHostPath(tool.containerPath, tool.rootPath)

            // 已就绪则跳过
            if (target.exists() && target.length() > 0 && pathResolver.isToolReady(target)) {
                ok += tool.name
                continue
            }

            // 下载（含一次重试）
            val downloaded = runCatching {
                downloadToolWithRetry(tool, target)
            }
            if (downloaded.isSuccess) {
                target.setExecutable(true, false)
                ok += tool.name
            } else {
                return@withContext PrefetchResult(
                    success = false,
                    tools = ok,
                    error = "DOWNLOAD_FAILED",
                    details = "工具 ${tool.name} 下载失败：${downloaded.exceptionOrNull()?.message}"
                )
            }
        }
        PrefetchResult(success = true, tools = ok)
    }

    /**
     * 下载单个工具 + SHA256 校验 + 损坏重试一次。
     */
    private suspend fun downloadToolWithRetry(tool: ToolEntry, target: File) {
        val url = mirrorConfig.resolveToolUrl(tool.name)
        val expectedSha256 = mirrorConfig.expectedSha256(tool.name)

        // 第一次尝试
        val first = runCatching {
            DownloadUtil.downloadWithResume(url, target, expectedSha256)
        }
        if (first.isSuccess) return

        // 失败：删除损坏文件，重试一次
        target.delete()
        DownloadUtil.downloadWithResume(url, target, expectedSha256)
    }

    /**
     * 强制清理某工具（用于校验失败 / 用户手动重装）。
     */
    suspend fun cleanTool(toolName: String): Boolean = withContext(Dispatchers.IO) {
        val tool = manifest?.tools?.firstOrNull { it.name == toolName } ?: return@withContext false
        val target = pathResolver.resolveHostPath(tool.containerPath, tool.rootPath)
        target.deleteRecursively()
    }

    /**
     * 全部重装（按 manifest 顺序）。
     */
    suspend fun reinstallAll(onProgress: (String) -> Unit = {}): PrefetchResult =
        withContext(Dispatchers.IO) {
            val tools = manifest?.tools ?: return@withContext PrefetchResult(
                success = false, tools = emptyList(),
                error = "MANIFEST_MISSING", details = "manifest 读取失败"
            )
            val ok = mutableListOf<String>()
            for (tool in tools) {
                onProgress("正在重装：${tool.name}")
                val target = pathResolver.resolveHostPath(tool.containerPath, tool.rootPath)
                target.deleteRecursively()
                runCatching { downloadToolWithRetry(tool, target) }
                    .onSuccess {
                        target.setExecutable(true, false)
                        ok += tool.name
                    }
                    .onFailure {
                        return@withContext PrefetchResult(
                            success = false, tools = ok,
                            error = "REINSTALL_FAILED",
                            details = "${tool.name} 重装失败：${it.message}"
                        )
                    }
            }
            PrefetchResult(success = true, tools = ok)
        }

    /**
     * 清理编译缓存（所有 workspace 的 cache 目录）。
     */
    suspend fun cleanBuildCache(): Int = withContext(Dispatchers.IO) {
        var count = 0
        PathConstants.workspaceRoot.listFiles()?.forEach { wsDir ->
            val cache = File(wsDir, "cache")
            if (cache.exists() && cache.deleteRecursively()) count++
        }
        count
    }

    /**
     * 校验已部署工具完整性。
     * @return 损坏的工具名列表
     */
    suspend fun verifyIntegrity(): List<String> = withContext(Dispatchers.IO) {
        val damaged = mutableListOf<String>()
        for (tool in manifest?.tools ?: emptyList()) {
            val target = pathResolver.resolveHostPath(tool.containerPath, tool.rootPath)
            if (!target.exists() || target.length() == 0L) {
                damaged += tool.name
                continue
            }
            val expected = mirrorConfig.expectedSha256(tool.name)
            if (expected.isNotEmpty() && !HashUtil.verify(target, expected)) {
                damaged += tool.name
            }
        }
        damaged
    }
}
