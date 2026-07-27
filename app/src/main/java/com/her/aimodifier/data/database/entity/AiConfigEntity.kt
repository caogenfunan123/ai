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
 *
 * 字段与 [com.her.aimodifier.ai.provider.ModelConfigData] 一一对应，
 * 通过 [com.her.aimodifier.data.repository.ModelConfigConverter] 进行互转。
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

    /** 配置ID（字符串形式，用于多配置管理） */
    val configId: String = "default",

    /** 配置名称 */
    val configName: String = "默认配置",

    /** API提供商类型 */
    val providerType: String = "DEEPSEEK",

    /** 中转站 / API 端点 BaseURL */
    val baseUrl: String = "",

    /** API Key */
    val apiKey: String = "",

    /** 默认模型名称（支持逗号分隔的多模型） */
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
    val temperatureEnabled: Boolean = false,
    val maxTokensEnabled: Boolean = false,
    val maxTokens: Int = 4096,

    /** 自定义请求头（JSON格式） */
    val customHeaders: String = "{}",

    /** 是否启用Tool Call */
    val enableToolCall: Boolean = false,

    /** 是否启用直接图片处理 */
    val enableDirectImageProcessing: Boolean = false,

    /** 是否启用直接音频处理 */
    val enableDirectAudioProcessing: Boolean = false,

    /** 是否启用直接视频处理 */
    val enableDirectVideoProcessing: Boolean = false,

    /** 是否启用Google搜索 */
    val enableGoogleSearch: Boolean = false,

    /** 是否启用Claude 1h提示缓存 */
    val enableClaude1hPromptCache: Boolean = false,

    /** 是否启用思考模式 */
    val enableThinking: Boolean = false,

    /** 思考质量等级（0-3） */
    val thinkingQualityLevel: Int = 2,

    /** 系统提示词 */
    val systemPrompt: String = "",

    /** 上下文长度（K为单位） */
    val contextLengthK: Float = 64.0f,

    /** 最大上下文长度（K为单位） */
    val maxContextLengthK: Float = 200.0f,

    /** 是否启用最大上下文模式 */
    val enableMaxContextMode: Boolean = false,

    /** 摘要Token阈值（0.0-1.0） */
    val summaryTokenThreshold: Float = 0.70f,

    /** 是否启用摘要 */
    val enableSummary: Boolean = true,

    /** 是否按消息数启用摘要 */
    val enableSummaryByMessageCount: Boolean = true,

    /** 摘要消息数阈值 */
    val summaryMessageCountThreshold: Int = 16,

    /** 摘要自定义规则 */
    val summaryCustomRules: String = "",

    /** 多API Key配置（JSON数组） */
    val apiKeyPoolJson: String = "[]",

    /** 是否使用多API Key */
    val useMultipleApiKeys: Boolean = false,

    /** 当前Key索引 */
    val currentKeyIndex: Int = 0,

    /** Key轮询模式（ROUND_ROBIN / FAILOVER） */
    val keyRotationMode: String = "ROUND_ROBIN",

    /** 是否有自定义参数 */
    val hasCustomParameters: Boolean = false,

    /** topP启用 */
    val topPEnabled: Boolean = false,

    /** topK启用 */
    val topKEnabled: Boolean = false,

    /** presencePenalty启用 */
    val presencePenaltyEnabled: Boolean = false,

    /** frequencyPenalty启用 */
    val frequencyPenaltyEnabled: Boolean = false,

    /** repetitionPenalty启用 */
    val repetitionPenaltyEnabled: Boolean = false,

    /** topP值 */
    val topP: Float = 1.0f,

    /** topK值 */
    val topK: Int = 0,

    /** presencePenalty值 */
    val presencePenalty: Float = 0.0f,

    /** frequencyPenalty值 */
    val frequencyPenalty: Float = 0.0f,

    /** repetitionPenalty值 */
    val repetitionPenalty: Float = 1.0f,

    /** 自定义参数（JSON数组） */
    val customParameters: String = "[]",

    /** 每分钟请求限制 */
    val requestLimitPerMinute: Int = 0,

    /** 最大并发请求数 */
    val maxConcurrentRequests: Int = 0,

    /** Llama.cpp 线程数 */
    val llamaThreadCount: Int = 4,

    /** Llama.cpp 上下文大小 */
    val llamaContextSize: Int = 2048,

    /** Llama.cpp Batch大小 */
    val llamaBatchSize: Int = 512,

    /** Llama.cpp UBatch大小 */
    val llamaUBatchSize: Int = 512,

    /** Llama.cpp GPU层数 */
    val llamaGpuLayers: Int = 0,

    /** Llama.cpp 是否使用mmap */
    val llamaUseMmap: Boolean = false,

    /** Llama.cpp 是否启用FlashAttention */
    val llamaFlashAttention: Boolean = false,

    /** Llama.cpp 是否统一KV */
    val llamaKvUnified: Boolean = true,

    /** Llama.cpp 是否卸载KQV */
    val llamaOffloadKqv: Boolean = false,

    /** MNN 前向类型 */
    val forwardType: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
