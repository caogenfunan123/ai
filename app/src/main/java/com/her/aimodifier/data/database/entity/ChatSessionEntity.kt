package com.her.aimodifier.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 会话表。
 *
 * 一个 [WorkspaceEntity] 可有多个会话。
 * 消息内容本身（messages）以 JSON 字符串持久化；P3 阶段可拆为独立表。
 */
@Entity(
    tableName = "chat_session",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["workspaceId"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workspaceId"])]
)
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 所属 workspaceId（业务ID） */
    val workspaceId: String,

    /** 会话标题（用于侧边列表展示） */
    val title: String,

    /** 关联的模型名称（云端 / 本地 GGUF） */
    val modelName: String,

    /** 是否使用本地 GGUF 模型 */
    val useLocalModel: Boolean = false,

    /** 消息历史 JSON（仅保存消息结构，不存大段 token 流） */
    val messagesJson: String = "[]",

    /** 当前会话使用的强制 prompt（覆盖全局） */
    val systemPromptOverride: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
