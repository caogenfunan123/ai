package com.her.aimodifier.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.her.aimodifier.base.constants.AppConstants

/**
 * AI 中转站配置表。
 *
 * - scope = global：全局配置，[workspaceId] = null
 * - scope = workspace：单个 workspace 独立配置，[workspaceId] 指向具体 workspace
 *
 * 同一 workspace 至多一条 workspace-scope 记录。
 */
@Entity(
    tableName = "ai_config",
    indices = [
        Index(value = ["scope", "workspaceId"], unique = true)
    ]
)
data class AiConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 作用域：global / workspace */
    val scope: String = AppConstants.AiConfigScope.GLOBAL,

    /** workspace 业务ID（scope=workspace 时必填） */
    val workspaceId: String? = null,

    /** 中转站 / OpenAI 兼容端点 BaseURL */
    val baseUrl: String = "",

    /** API Key（密文存储建议在 Repository 层做对称加密后再写入） */
    val apiKey: String = "",

    /** 默认模型名称 */
    val defaultModel: String = "",

    /** 手动输入的模型列表（逗号分隔；当远端 /v1/models 不可用时使用） */
    val manualModels: String = "",

    /** 是否启用手动模型输入 */
    val manualModelMode: Boolean = false,

    /** 缓存的远端模型列表 JSON */
    val cachedModelsJson: String = "[]",

    /** 模型列表缓存时间戳 */
    val cachedModelsAt: Long = 0L,

    /** LLM 参数 */
    val timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
    val contextLength: Int = AppConstants.DEFAULT_CONTEXT_LENGTH,
    val temperature: Float = AppConstants.DEFAULT_TEMPERATURE,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
