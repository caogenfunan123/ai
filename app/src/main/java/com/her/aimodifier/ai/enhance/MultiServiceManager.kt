package com.her.aimodifier.ai.enhance

import android.content.Context
import android.util.Log
import com.her.aimodifier.ai.provider.AIService
import com.her.aimodifier.ai.provider.AIServiceFactory
import com.her.aimodifier.ai.provider.ModelConfigData
import com.her.aimodifier.ai.provider.getModelByIndex
import com.her.aimodifier.ai.provider.getValidModelIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 功能类型枚举，用于区分不同 AI 任务场景下的服务配置。
 *
 * 不同功能可以使用不同的模型配置（例如对话用大模型，标题生成用小模型），
 * 由 [MultiServiceManager] 按类型分别创建和缓存 [AIService] 实例。
 */
enum class FunctionType {
    /** 主对话功能 */
    CHAT,

    /** 历史摘要功能 */
    SUMMARY,

    /** 会话标题生成 */
    TITLE_GENERATION,

    /** 翻译功能 */
    TRANSLATION,

    /** 图片识别功能 */
    IMAGE_RECOGNITION,

    /** 音频识别功能 */
    AUDIO_RECOGNITION,

    /** 视频识别功能 */
    VIDEO_RECOGNITION
}

/**
 * 管理多个 [AIService] 实例，根据功能类型提供不同的服务配置。
 *
 * 由于当前项目没有 FunctionalConfigManager / ModelConfigManager，
 * 本类简化为直接管理 [ModelConfigData] 到 [AIService] 的映射：
 * - 调用方通过 [setFunctionConfig] / [setCustomConfig] 注册配置；
 * - 内部按需调用 [AIServiceFactory.createService] 创建服务实例并缓存；
 * - 通过 [ServiceLease] 引用计数管理服务生命周期，支持刷新和释放。
 *
 * @param context Android Context，用于创建服务
 */
class MultiServiceManager(private val context: Context) {
    companion object {
        private const val TAG = "MultiServiceManager"
    }

    /**
     * 服务租约，引用计数方式持有 [AIService]。
     *
     * 调用方在使用完毕后必须调用 [close] 归还租约，
     * 当所有租约归还且服务已被标记为退役时，底层资源才会被释放。
     *
     * @param service 持有的 AI 服务实例
     * @param modelConfig 创建该服务所使用的模型配置
     * @param closeAction 归还租约时执行的回调（内部使用）
     */
    class ServiceLease internal constructor(
        private val closeAction: suspend () -> Unit,
        val service: AIService,
        val modelConfig: ModelConfigData
    ) {
        private val closed = AtomicBoolean(false)

        /** 归还租约，重复调用是安全的。 */
        suspend fun close() {
            if (closed.compareAndSet(false, true)) {
                closeAction()
            }
        }
    }

    /** 内部受管理的服务包装类，记录引用计数与生命周期状态。 */
    private class ManagedService(
        val service: AIService,
        val modelConfig: ModelConfigData,
        var activeLeases: Int = 0,
        var retired: Boolean = false,
        var released: Boolean = false
    )

    // 功能类型 -> 模型配置（由调用方注册）
    private val functionConfigs = mutableMapOf<FunctionType, ModelConfigData>()

    // 自定义配置ID -> 模型配置（由调用方注册）
    private val customConfigs = mutableMapOf<String, ModelConfigData>()

    // 功能类型 -> 受管理服务实例缓存
    private val serviceInstances = mutableMapOf<FunctionType, ManagedService>()

    // 缓存 key（configId#index） -> 受管理服务实例缓存
    private val customServiceInstances = mutableMapOf<String, ManagedService>()

    // 已退役但可能仍在被租约使用的服务集合
    private val retiredServices = mutableSetOf<ManagedService>()

    private val serviceMutex = Mutex()

    // 默认 AIService，通常是 CHAT 功能对应的服务
    private var defaultService: ManagedService? = null

    /**
     * 为指定功能类型注册模型配置。
     *
     * 如果该功能已有缓存的 服务实例，会先将其退役。
     *
     * @param functionType 功能类型
     * @param config 模型配置
     */
    suspend fun setFunctionConfig(functionType: FunctionType, config: ModelConfigData) {
        serviceMutex.withLock {
            functionConfigs[functionType] = config
            serviceInstances.remove(functionType)?.let { retireManagedServiceLocked(it) }
            if (functionType == FunctionType.CHAT) {
                defaultService = null
            }
            Log.d(TAG, "已为功能 $functionType 注册配置: ${config.name}")
        }
    }

    /**
     * 注册一个自定义配置（按 configId 索引）。
     *
     * @param configId 配置 ID
     * @param config 模型配置
     */
    suspend fun setCustomConfig(configId: String, config: ModelConfigData) {
        serviceMutex.withLock {
            customConfigs[configId] = config
            // 使该 configId 下所有模型索引的缓存失效
            val keysToRemove = customServiceInstances.keys.filter { it.startsWith("$configId#") }
            keysToRemove.forEach { key ->
                customServiceInstances.remove(key)?.let { retireManagedServiceLocked(it) }
            }
            Log.d(TAG, "已注册自定义配置: configId=$configId, name=${config.name}")
        }
    }

    /** 获取指定功能类型的 [AIService]，按需创建。 */
    suspend fun getServiceForFunction(functionType: FunctionType): AIService {
        return serviceMutex.withLock {
            getOrCreateServiceForFunctionLocked(functionType).service
        }
    }

    /** 根据配置 ID 和模型索引获取 [AIService]（不会修改功能映射）。 */
    suspend fun getServiceForConfig(configId: String, modelIndex: Int): AIService {
        return serviceMutex.withLock {
            getOrCreateServiceForConfigLocked(configId, modelIndex).service
        }
    }

    /** 以租约方式获取指定功能类型的服务，使用完毕必须调用 [ServiceLease.close]。 */
    suspend fun acquireServiceForFunction(functionType: FunctionType): ServiceLease {
        val managedService = serviceMutex.withLock {
            getOrCreateServiceForFunctionLocked(functionType).also { it.activeLeases += 1 }
        }
        return ServiceLease(
            closeAction = { releaseLease(managedService) },
            service = managedService.service,
            modelConfig = managedService.modelConfig
        )
    }

    /** 以租约方式获取指定配置 ID 与模型索引对应的服务，使用完毕必须调用 [ServiceLease.close]。 */
    suspend fun acquireServiceForConfig(configId: String, modelIndex: Int): ServiceLease {
        val managedService = serviceMutex.withLock {
            getOrCreateServiceForConfigLocked(configId, modelIndex).also { it.activeLeases += 1 }
        }
        return ServiceLease(
            closeAction = { releaseLease(managedService) },
            service = managedService.service,
            modelConfig = managedService.modelConfig
        )
    }

    private suspend fun getOrCreateServiceForFunctionLocked(
        functionType: FunctionType
    ): ManagedService {
        serviceInstances[functionType]?.let { return it }

        val config = functionConfigs[functionType]
            ?: run {
                Log.w(TAG, "功能 $functionType 未注册配置，回退到 CHAT 配置")
                functionConfigs[FunctionType.CHAT]
                    ?: throw IllegalStateException("功能 $functionType 未注册配置，且没有可用的 CHAT 回退配置")
            }

        val service = createServiceFromConfig(config, 0)
        val managedService = ManagedService(service = service, modelConfig = config)
        serviceInstances[functionType] = managedService

        if (functionType == FunctionType.CHAT) {
            defaultService = managedService
        }

        Log.d(TAG, "已为功能 $functionType 创建服务实例，使用配置 ${config.name}")
        return managedService
    }

    private suspend fun getOrCreateServiceForConfigLocked(
        configId: String,
        modelIndex: Int
    ): ManagedService {
        val normalizedIndex = modelIndex.coerceAtLeast(0)
        val cacheKey = "$configId#$normalizedIndex"
        customServiceInstances[cacheKey]?.let { return it }

        val config = customConfigs[configId]
            ?: throw IllegalStateException("找不到 configId=$configId 对应的配置，请先调用 setCustomConfig 注册")

        val service = createServiceFromConfig(config, normalizedIndex)
        val managedService = ManagedService(service = service, modelConfig = config)
        customServiceInstances[cacheKey] = managedService

        Log.d(TAG, "已为自定义配置创建服务实例，configId=$configId，模型索引=$normalizedIndex")
        return managedService
    }

    /** 获取默认服务（通常是 CHAT 功能的服务）。 */
    suspend fun getDefaultService(): AIService {
        return serviceMutex.withLock {
            (defaultService ?: getOrCreateServiceForFunctionLocked(FunctionType.CHAT)).service
        }
    }

    /** 取消所有受管理服务的流式传输。 */
    suspend fun cancelAllStreaming() {
        serviceMutex.withLock {
            val services = collectAllServicesLocked()
            services.forEach { service ->
                try {
                    service.cancelStreaming()
                } catch (e: Exception) {
                    Log.e(TAG, "取消服务流式传输时出错", e)
                }
            }
        }
    }

    /** 重置所有受管理服务的 token 计数器。 */
    suspend fun resetAllTokenCounters() {
        serviceMutex.withLock {
            val services = collectAllServicesLocked()
            services.forEach { service ->
                try {
                    service.resetTokenCounts()
                } catch (e: Exception) {
                    Log.e(TAG, "重置服务 token 计数器时出错", e)
                }
            }
        }
    }

    /** 重置指定功能类型服务的 token 计数器。 */
    suspend fun resetTokenCountersForFunction(functionType: FunctionType) {
        val service = getServiceForFunction(functionType)
        try {
            service.resetTokenCounts()
        } catch (e: Exception) {
            Log.e(TAG, "重置功能 $functionType 的 token 计数器时出错", e)
        }
    }

    /**
     * 刷新指定功能类型的服务实例，当配置更改时调用此方法。
     */
    suspend fun refreshServiceForFunction(functionType: FunctionType) {
        serviceMutex.withLock {
            serviceInstances.remove(functionType)?.let { retireManagedServiceLocked(it) }

            if (functionType == FunctionType.CHAT) {
                defaultService = null
                val customServices = customServiceInstances.values.toList()
                customServiceInstances.clear()
                customServices.forEach { service ->
                    retireManagedServiceLocked(service)
                }
            }

            Log.d(TAG, "已移除功能 $functionType 的服务实例缓存")
        }
    }

    /** 刷新所有服务实例，当全局设置更改时调用此方法。 */
    suspend fun refreshAllServices() {
        serviceMutex.withLock {
            val services = mutableSetOf<ManagedService>()
            services.addAll(serviceInstances.values)
            services.addAll(customServiceInstances.values)
            services.addAll(retiredServices)
            defaultService?.let { services.add(it) }

            serviceInstances.clear()
            customServiceInstances.clear()
            retiredServices.clear()
            defaultService = null
            services.forEach { service ->
                closeManagedServiceLocked(service, cancelStreaming = true)
            }
            Log.d(TAG, "已清除所有服务实例缓存并释放资源")
        }
    }

    private fun collectAllServicesLocked(): Set<AIService> {
        val services = mutableSetOf<AIService>()
        services.addAll(serviceInstances.values.map { it.service })
        services.addAll(customServiceInstances.values.map { it.service })
        services.addAll(retiredServices.map { it.service })
        defaultService?.let { services.add(it.service) }
        return services
    }

    private suspend fun releaseLease(managedService: ManagedService) {
        serviceMutex.withLock {
            managedService.activeLeases = (managedService.activeLeases - 1).coerceAtLeast(0)
            closeRetiredServiceLocked(managedService)
        }
    }

    private fun retireManagedServiceLocked(managedService: ManagedService) {
        managedService.retired = true
        retiredServices.add(managedService)
        closeRetiredServiceLocked(managedService)
    }

    private fun closeRetiredServiceLocked(managedService: ManagedService) {
        if (managedService.retired && managedService.activeLeases == 0) {
            closeManagedServiceLocked(managedService, cancelStreaming = false)
        }
    }

    private fun closeManagedServiceLocked(
        managedService: ManagedService,
        cancelStreaming: Boolean
    ) {
        if (managedService.released) {
            return
        }
        managedService.released = true
        retiredServices.remove(managedService)
        try {
            if (cancelStreaming) {
                managedService.service.cancelStreaming()
            }
            managedService.service.release()
            Log.d(TAG, "已释放服务资源: providerModel=${managedService.service.providerModel}")
        } catch (e: Exception) {
            Log.e(TAG, "释放服务资源时出错", e)
        }
    }

    /**
     * 根据配置创建 [AIService] 实例。
     *
     * 内部会根据 modelIndex 选择具体的模型名称（支持逗号分隔的多模型配置），
     * 然后委托 [AIServiceFactory.createService] 完成实际创建。
     * 限流和并发控制由 [AIServiceFactory] 内部处理，此处不再二次包装。
     *
     * @param config 模型配置
     * @param modelIndex 模型索引（针对逗号分隔的多模型配置）
     * @return 创建出的 [AIService] 实例
     */
    suspend fun createServiceFromConfig(config: ModelConfigData, modelIndex: Int): AIService {
        // 使用公共函数计算有效索引
        val actualIndex = getValidModelIndex(config.modelName, modelIndex)

        // 记录越界警告
        if (actualIndex != modelIndex && modelIndex != 0) {
            val modelList = config.modelName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            Log.w(
                TAG,
                "模型索引 $modelIndex 超出范围(0-${modelList.size - 1})，自动使用第一个模型"
            )
        }

        // 根据实际索引选择具体模型
        val selectedModelName = getModelByIndex(config.modelName, actualIndex)

        // 创建一个临时配置，使用选中的模型名称
        val configWithSelectedModel = config.copy(modelName = selectedModelName)

        Log.d(
            TAG,
            "创建服务: 原始模型='${config.modelName}', 选中模型='$selectedModelName' " +
                    "(请求索引=$modelIndex, 实际索引=$actualIndex)"
        )

        return AIServiceFactory.createService(
            config = configWithSelectedModel,
            context = context
        )
    }

    /**
     * 获取指定功能类型的模型配置。
     *
     * @param functionType 功能类型
     * @return 模型配置数据，未注册时抛出 [IllegalStateException]
     */
    suspend fun getModelConfigForFunction(functionType: FunctionType): ModelConfigData {
        return serviceMutex.withLock {
            functionConfigs[functionType]
                ?: functionConfigs[FunctionType.CHAT]
                ?: throw IllegalStateException("功能 $functionType 未注册配置")
        }
    }

    /** 获取指定配置 ID 的模型配置。 */
    suspend fun getModelConfigForConfig(configId: String): ModelConfigData {
        return serviceMutex.withLock {
            customConfigs[configId]
                ?: throw IllegalStateException("找不到 configId=$configId 对应的配置")
        }
    }

    /**
     * 检查识图功能是否已配置且启用了直接图片处理。
     */
    suspend fun hasImageRecognitionConfigured(): Boolean {
        val config = serviceMutex.withLock {
            functionConfigs[FunctionType.IMAGE_RECOGNITION] ?: return false
        }
        return config.enableDirectImageProcessing
    }

    /** 检查音频识别功能是否已配置且启用了直接音频处理。 */
    suspend fun hasAudioRecognitionConfigured(): Boolean {
        val config = serviceMutex.withLock {
            functionConfigs[FunctionType.AUDIO_RECOGNITION] ?: return false
        }
        return config.enableDirectAudioProcessing
    }

    /** 检查视频识别功能是否已配置且启用了直接视频处理。 */
    suspend fun hasVideoRecognitionConfigured(): Boolean {
        val config = serviceMutex.withLock {
            functionConfigs[FunctionType.VIDEO_RECOGNITION] ?: return false
        }
        return config.enableDirectVideoProcessing
    }
}
