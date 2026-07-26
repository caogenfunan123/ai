package com.her.aimodifier.utils

import java.io.File
import java.security.MessageDigest

/**
 * SHA256 校验工具。
 */
object HashUtil {

    fun sha256(file: File): String {
        if (!file.exists()) return ""
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 校验文件 SHA256 是否匹配预期 */
    fun verify(file: File, expectedSha256: String): Boolean =
        sha256(file).equals(expectedSha256, ignoreCase = true)
}
