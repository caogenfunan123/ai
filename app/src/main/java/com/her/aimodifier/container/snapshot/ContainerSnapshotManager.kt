package com.her.aimodifier.container.snapshot

import android.content.Context
import com.her.aimodifier.base.constants.PathConstants
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 容器快照管理器。
 *
 * 快照机制：把当前 rootfs 整体打包为 tar.zst，恢复时解压覆盖。
 *
 * 快照元数据存于 [PathConstants.rootfsRoot]/.snapshots/index.json。
 */
class ContainerSnapshotManager(private val context: Context) {

    private val snapshotsDir: File = File(PathConstants.rootfsRoot, ".snapshots").apply { mkdirs() }
    private val indexFile: File = File(snapshotsDir, "index.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class SnapshotMeta(
        val id: String,
        val name: String,
        val fileName: String,
        val createdAt: Long,
        val sizeBytes: Long,
        val description: String? = null
    )

    /** 列出所有快照 */
    fun list(): List<SnapshotMeta> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SnapshotMeta>>(indexFile.readText())
        }.getOrDefault(emptyList())
    }

    /**
     * 创建快照。
     * @return 快照 ID
     */
    fun create(name: String, description: String? = null): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        val fileName = "snap-$id.tar.zst"
        val target = File(snapshotsDir, fileName)
        // TODO: P1 阶段实现 tar+zst 打包；当前仅创建占位文件
        target.writeText("# placeholder snapshot of rootfs")
        val meta = SnapshotMeta(
            id = id,
            name = name,
            fileName = fileName,
            createdAt = System.currentTimeMillis(),
            sizeBytes = target.length(),
            description = description
        )
        val updated = list() + meta
        indexFile.writeText(json.encodeToString(updated))
        return id
    }

    /** 加载快照（恢复 rootfs） */
    fun load(snapshotId: String): Boolean {
        val meta = list().firstOrNull { it.id == snapshotId } ?: return false
        val snapFile = File(snapshotsDir, meta.fileName)
        if (!snapFile.exists()) return false
        // TODO: P1 阶段实现 tar+zst 解压覆盖；当前仅返回 true 占位
        return true
    }

    /** 删除快照 */
    fun delete(snapshotId: String): Boolean {
        val current = list()
        val meta = current.firstOrNull { it.id == snapshotId } ?: return false
        File(snapshotsDir, meta.fileName).delete()
        indexFile.writeText(json.encodeToString(current.filterNot { it.id == snapshotId }))
        return true
    }
}
