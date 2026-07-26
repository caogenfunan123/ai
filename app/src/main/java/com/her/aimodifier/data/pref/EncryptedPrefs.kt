package com.her.aimodifier.data.pref

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.her.aimodifier.base.constants.AppConstants
import java.util.Base64

/**
 * 加密偏好存储。
 *
 * 基于 AndroidX Security 的 EncryptedSharedPreferences：
 * - MasterKey 由 Android Keystore 守护
 * - 值使用 AES-GCM 加密
 *
 * 主要存放：
 * - 数据库密钥（[dbPassphrase]）
 * - AI 中转站 ApiKey（敏感信息）冗余备份
 */
class EncryptedPrefs(context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            AppConstants.ENCRYPTED_PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * 数据库密钥（Base64 编码的 32 字节随机数）。
     * [ensureGenerated]：第一次访问时生成并持久化；之后直接读取。
     */
    val dbPassphrase: PassphraseHolder = PassphraseHolder(prefs)

    /** 全局强制 Prompt（持久化） */
    var globalSystemPrompt: String
        get() = prefs.getString(KEY_GLOBAL_PROMPT, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_GLOBAL_PROMPT, value).apply()
        }

    /** 当前选中的全局 AI 配置 ID */
    var activeGlobalAiConfigId: Long
        get() = prefs.getLong(KEY_ACTIVE_AI_CONFIG_ID, -1L)
        set(value) {
            prefs.edit().putLong(KEY_ACTIVE_AI_CONFIG_ID, value).apply()
        }

    /** 工具链镜像源 BaseUrl（空表示使用默认源） */
    var mirrorBaseUrl: String
        get() = prefs.getString(KEY_MIRROR_BASE_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_MIRROR_BASE_URL, value).apply()
        }

    /** 当前选中的工具版本（key=工具名，value=版本号） */
    fun setSelectedToolVersion(toolName: String, version: String) {
        prefs.edit().putString("$KEY_TOOL_VERSION_PREFIX$toolName", version).apply()
    }

    fun getSelectedToolVersion(toolName: String): String? =
        prefs.getString("$KEY_TOOL_VERSION_PREFIX$toolName", null)

    fun setApiKey(workspaceId: String?, apiKey: String) {
        val key = if (workspaceId == null) KEY_API_KEY_GLOBAL else "$KEY_API_KEY_WS$workspaceId"
        prefs.edit().putString(key, apiKey).apply()
    }

    fun getApiKey(workspaceId: String?): String? {
        val key = if (workspaceId == null) KEY_API_KEY_GLOBAL else "$KEY_API_KEY_WS$workspaceId"
        return prefs.getString(key, null)
    }

    class PassphraseHolder(private val prefs: android.content.SharedPreferences) {
        fun ensureGenerated(): ByteArray {
            val stored = prefs.getString(KEY_DB_PASSPHRASE, null)
            return if (stored != null) {
                Base64.getDecoder().decode(stored)
            } else {
                val random = java.security.SecureRandom()
                val bytes = ByteArray(32)
                random.nextBytes(bytes)
                prefs.edit()
                    .putString(KEY_DB_PASSPHRASE, Base64.getEncoder().encodeToString(bytes))
                    .apply()
                bytes
            }
        }
    }

    companion object {
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val KEY_GLOBAL_PROMPT = "global_system_prompt"
        private const val KEY_ACTIVE_AI_CONFIG_ID = "active_global_ai_config_id"
        private const val KEY_API_KEY_GLOBAL = "api_key_global"
        private const val KEY_API_KEY_WS = "api_key_ws_"
        private const val KEY_MIRROR_BASE_URL = "mirror_base_url"
        private const val KEY_TOOL_VERSION_PREFIX = "tool_version_"
    }
}
