package com.her.aimodifier.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.her.aimodifier.base.constants.AppConstants

/**
 * 工作区数据表。
 *
 * - 一个 workspace 对应一个独立项目目录
 * - 可选独立 AI 配置（[aiConfigId]）覆盖全局配置
 * - 关联多个 [ChatSessionEntity]
 */
@Entity(
    tableName = "workspace",
    indices = [Index(value = ["workspaceId"], unique = true)]
)
data class WorkspaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 业务唯一 ID（用于目录命名、跨设备引用） */
    val workspaceId: String,

    /** 工作区显示名称 */
    val name: String,

    /** 工作区来源：blank / local_import / git_clone */
    val source: String = AppConstants.WorkspaceSource.BLANK,

    /** Git 仓库 URL（source=git_clone 时有效） */
    val gitUrl: String? = null,

    /** 源码目录绝对路径（安卓本机路径） */
    val localPath: String,

    /** 关联的独立 AI 配置 ID（可选；为 null 时使用全局配置） */
    val aiConfigId: Long? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
