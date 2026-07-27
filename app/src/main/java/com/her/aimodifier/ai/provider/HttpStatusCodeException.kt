package com.her.aimodifier.ai.provider

import android.util.Log

/**
 * 标记异常携带 HTTP 状态码，供重试和密钥池轮询逻辑判断使用。
 */
interface HttpStatusCodeException {
    val statusCode: Int
}

/**
 * 判断是否应抑制密钥池轮询中遇到的 429（速率限制）中间提示。
 * 当存在多个候选密钥时，抑制单次 429 提示以便继续尝试其他密钥。
 *
 * @param apiKeyProvider 当前使用的密钥提供程序
 * @param exception 请求中抛出的异常
 * @param logTag 日志标签
 * @return 如果应抑制提示返回 true，否则返回 false
 */
suspend fun shouldSuppressKeyPoolRateLimitNotice(
    apiKeyProvider: ApiKeyProvider,
    exception: Exception,
    logTag: String
): Boolean {
    val httpException = exception as? HttpStatusCodeException ?: return false
    if (httpException.statusCode != 429) return false

    val candidateKeyCount = apiKeyProvider.getCandidateKeyCount()
    val shouldSuppress = candidateKeyCount > 1
    if (shouldSuppress) {
        Log.w(
            logTag,
            "多密钥轮询遇到 429，跳过本次中间提示，继续尝试其他密钥。candidateKeyCount=$candidateKeyCount",
            exception
        )
    }

    return shouldSuppress
}
