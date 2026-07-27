package com.her.aimodifier.ai.provider

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * API密钥提供程序接口
 * 抽象了API密钥的获取逻辑，以支持单个密钥和密钥池轮询。
 */
interface ApiKeyProvider {
    /** 获取当前可用的API Key */
    suspend fun getApiKey(): String

    /** 获取当前会参与轮询的API Key数量 */
    suspend fun getCandidateKeyCount(): Int

    /** 标记当前使用的Key为失败状态 */
    fun markCurrentKeyFailed()
}

/**
 * 单个API Key的简单提供程序，用于兼容旧配置。
 */
class SingleApiKeyProvider(private val apiKey: String) : ApiKeyProvider {
    override suspend fun getApiKey(): String {
        Log.d("ApiKeyProvider", "Using single API key: ${apiKey.take(4)}...${apiKey.takeLast(4)}")
        return apiKey
    }

    override suspend fun getCandidateKeyCount(): Int = if (apiKey.isNotBlank()) 1 else 0

    override fun markCurrentKeyFailed() {}
}

/**
 * 模型配置密钥存储接口，抽象了对配置中密钥状态的读写操作。
 * 由具体的配置管理器实现（如 ModelConfigManager）。
 */
interface ModelConfigKeyStore {
    /** 根据 configId 获取模型配置 */
    suspend fun getModelConfig(configId: String): ModelConfigData?

    /** 更新指定配置的当前密钥轮询索引 */
    suspend fun updateConfigKeyIndex(configId: String, newIndex: Int)

    /** 更新指定配置中某个密钥的可用性状态 */
    suspend fun updateApiKeyAvailability(
        configId: String,
        keyName: String,
        status: ApiKeyAvailabilityStatus
    )
}

/**
 * 多API Key提供程序，实现密钥的轮询和状态管理。
 * @param apiKeyPool API Key池
 * @param configId 配置ID（用于日志标识）
 * @param fallbackApiKey 回退用的单个API Key（当池为空时使用）
 */
class MultiApiKeyProvider(
    private val apiKeyPool: List<ApiKeyInfo>,
    private val configId: String = "",
    private val fallbackApiKey: String = ""
) : ApiKeyProvider {
    private val mutex = Mutex()

    @Volatile
    private var lastSelectedKeyName: String? = null

    @Volatile
    private var currentIndex = 0

    /** 内存中标记为失败的 Key 名称集合，下次 getApiKey 会跳过这些 Key */
    private val failedKeyNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override suspend fun getApiKey(): String {
        return mutex.withLock {
            // 筛选出启用的key
            val enabledKeys = apiKeyPool.filter { it.isEnabled }
            Log.d(
                "ApiKeyProvider",
                "Config $configId: Found ${enabledKeys.size} enabled keys out of ${apiKeyPool.size} total keys"
            )

            val hasAnyAvailabilityMark =
                enabledKeys.any { it.availabilityStatus != ApiKeyAvailabilityStatus.UNTESTED }
            val candidateKeys =
                if (hasAnyAvailabilityMark) {
                    enabledKeys.filter { it.availabilityStatus == ApiKeyAvailabilityStatus.AVAILABLE }
                } else {
                    enabledKeys
                }.filter { it.name !in failedKeyNames }

            if (candidateKeys.isEmpty()) {
                if (hasAnyAvailabilityMark || failedKeyNames.isNotEmpty()) {
                    Log.e(
                        "ApiKeyProvider",
                        "Config $configId: No AVAILABLE keys found in pool (failed: ${failedKeyNames.size})."
                    )
                    throw IllegalStateException(
                        "No AVAILABLE API keys in pool. Please test keys or clear availability marks."
                    )
                }
                // 如果池为空，尝试回退到单key
                if (fallbackApiKey.isNotBlank()) {
                    Log.d(
                        "ApiKeyProvider",
                        "Config $configId: No enabled keys in pool, falling back to single API key"
                    )
                    return@withLock fallbackApiKey
                }
                Log.e(
                    "ApiKeyProvider",
                    "Config $configId: API key pool is empty or all keys are disabled, and no fallback API key is available"
                )
                throw IllegalStateException("API key pool is empty or all keys are disabled, and no fallback API key is available.")
            }

            // 从当前索引开始寻找下一个有效的key
            val startIndex = currentIndex % candidateKeys.size
            val selectedKey = candidateKeys[startIndex]

            lastSelectedKeyName = selectedKey.name

            Log.d(
                "ApiKeyProvider",
                "Config $configId: Using key ${startIndex + 1}/${candidateKeys.size} - '${selectedKey.name}'"
            )

            // 更新下一个索引
            currentIndex = (startIndex + 1) % candidateKeys.size

            selectedKey.key
        }
    }

    override suspend fun getCandidateKeyCount(): Int {
        return mutex.withLock {
            val enabledKeys = apiKeyPool.filter { it.isEnabled }
            val hasAnyAvailabilityMark =
                enabledKeys.any { it.availabilityStatus != ApiKeyAvailabilityStatus.UNTESTED }
            val candidateKeys =
                if (hasAnyAvailabilityMark) {
                    enabledKeys.filter { it.availabilityStatus == ApiKeyAvailabilityStatus.AVAILABLE }
                } else {
                    enabledKeys
                }.filter { it.name !in failedKeyNames }

            candidateKeys.size
        }
    }

    override fun markCurrentKeyFailed() {
        val keyName = lastSelectedKeyName ?: return
        failedKeyNames.add(keyName)
        Log.w(
            "ApiKeyProvider",
            "Config $configId: Marking key '$keyName' as failed (UNAVAILABLE). Total failed: ${failedKeyNames.size}"
        )
        // 重置 lastSelectedKeyName 避免重复标记
        lastSelectedKeyName = null
    }
}

/**
 * API密钥池可用性测试器，用于批量测试密钥池中各密钥的可用性。
 */
object ApiKeyPoolAvailabilityTester {

    data class KeyTestResult(
        val keyName: String,
        val status: ApiKeyAvailabilityStatus,
        val error: String? = null
    )

    /**
     * 测试指定配置中所有启用的密钥的可用性。
     * @param config 要测试的模型配置
     * @param configStore 配置存储接口，用于更新密钥状态
     * @param testKey 测试单个密钥的函数，返回是否可用
     * @return 每个密钥的测试结果列表
     */
    suspend fun testAllKeys(
        config: ModelConfigData,
        configStore: ModelConfigKeyStore,
        testKey: suspend (apiKey: String) -> Boolean
    ): List<KeyTestResult> {
        val results = mutableListOf<KeyTestResult>()
        val enabledKeys = config.apiKeyPool.filter { it.isEnabled }

        for (keyInfo in enabledKeys) {
            val result = try {
                val isAvailable = testKey(keyInfo.key)
                val status = if (isAvailable) ApiKeyAvailabilityStatus.AVAILABLE else ApiKeyAvailabilityStatus.UNAVAILABLE
                configStore.updateApiKeyAvailability(config.id, keyInfo.name, status)
                KeyTestResult(
                    keyName = keyInfo.name,
                    status = status,
                    error = if (isAvailable) null else "Key test returned unavailable"
                )
            } catch (e: Exception) {
                configStore.updateApiKeyAvailability(
                    config.id,
                    keyInfo.name,
                    ApiKeyAvailabilityStatus.UNAVAILABLE
                )
                KeyTestResult(
                    keyName = keyInfo.name,
                    status = ApiKeyAvailabilityStatus.UNAVAILABLE,
                    error = e.message
                )
            }
            results.add(result)
            Log.d(
                "ApiKeyPoolTester",
                "Config ${config.name}: Key '${keyInfo.name}' -> ${result.status}${if (result.error != null) " (${result.error})" else ""}"
            )
        }

        return results
    }

    /**
     * 将指定配置中所有密钥的状态重置为未测试。
     * @param config 模型配置
     * @param configStore 配置存储接口
     */
    suspend fun resetAllKeyStatuses(
        config: ModelConfigData,
        configStore: ModelConfigKeyStore
    ) {
        for (keyInfo in config.apiKeyPool) {
            configStore.updateApiKeyAvailability(
                config.id,
                keyInfo.name,
                ApiKeyAvailabilityStatus.UNTESTED
            )
        }
        Log.d("ApiKeyPoolTester", "Config ${config.name}: Reset all key statuses to UNTESTED")
    }
}
