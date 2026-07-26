package com.her.aimodifier.container.toolchain

import com.her.aimodifier.data.pref.EncryptedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 镜像源配置（最终定稿）。
 *
 * 用户可在设置中填写国内镜像中转站地址，加速所有 SDK/Gradle/工具下载。
 *
 * 默认源：https://dl.compilerbox.dev/toolchain/arm64/
 * 用户自定义：通过 [EncryptedPrefs.mirrorBaseUrl] 持久化
 *
 * 工具 SHA256 校验值也由本类提供（P1 阶段从远端 sha256.json 拉取，
 * 当前返回空串表示不强制校验，等远端 manifest 完善后接入）。
 */
class MirrorConfig(
    private val prefs: EncryptedPrefs? = null
) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 当前生效的镜像源 BaseUrl */
    val baseUrl: String
        get() = (prefs?.mirrorBaseUrl?.takeIf { it.isNotEmpty() }) ?: DEFAULT_BASE_URL

    /** 拼装某工具的完整下载 URL */
    fun resolveToolUrl(toolName: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base/$toolName"
    }

    /**
     * 某工具的预期 SHA256。
     *
     * TODO: 从远端拉取 sha256.json 后填入；当前返回空串表示不强制校验。
     */
    fun expectedSha256(toolName: String): String = ""

    /** 设置镜像源 */
    fun setMirror(url: String) {
        prefs?.mirrorBaseUrl = url
    }

    /** 重置为默认源 */
    fun reset() {
        prefs?.mirrorBaseUrl = ""
    }

    /**
     * 保存自定义镜像源 URL。
     *
     * @param url 镜像源地址
     */
    suspend fun saveCustomMirror(url: String) {
        withContext(Dispatchers.IO) {
            prefs?.mirrorBaseUrl = url.trim()
        }
    }

    /**
     * 获取当前自定义镜像源 URL。
     *
     * @return 自定义 URL，未设置时返回 null
     */
    suspend fun getCustomMirror(): String? = withContext(Dispatchers.IO) {
        prefs?.mirrorBaseUrl?.takeIf { it.isNotBlank() }
    }

    /**
     * 测试与指定 URL 的连接是否可用。
     *
     * @param url 待测试的 URL
     * @return 连接成功返回 true
     */
    suspend fun testConnection(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url.trimEnd('/') + "/")
                .head()
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code in 300..399
            }
        }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://dl.compilerbox.dev/toolchain/arm64"
    }
}