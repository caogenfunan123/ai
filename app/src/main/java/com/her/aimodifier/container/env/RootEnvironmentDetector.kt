package com.her.aimodifier.container.env

import android.os.Build
import java.io.File

/**
 * 环境检测工具。
 *
 * 检测项：
 * - CPU 架构
 * - 是否有 Root（su 二进制）
 * - 是否有 KernelSU（/data/adb/ksu）
 *
 * 决策：
 * - 有 Root → 优先本机执行
 * - 无 Root → 强制 PRoot 容器
 */
class RootEnvironmentDetector {

    fun detect(): EnvironmentInfo {
        val arch = detectArch()
        val hasRoot = checkSuBinary()
        val hasKernelSu = checkKernelSu()
        val useProot = !hasRoot
        return EnvironmentInfo(
            arch = arch,
            hasRoot = hasRoot,
            hasKernelSu = hasKernelSu,
            useProot = useProot
        )
    }

    private fun detectArch(): String {
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            listOf(Build.CPU_ABI)
        }
        return when {
            abis.contains("arm64-v8a") -> "arm64"
            abis.contains("x86_64") -> "x86_64"
            abis.contains("armeabi-v7a") -> "arm32"
            else -> "unknown"
        }
    }

    /** 检测 su 二进制是否可执行 */
    private fun checkSuBinary(): Boolean {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su"
        )
        return suPaths.any { File(it).exists() } || runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            p.waitFor() == 0 && p.inputStream.bufferedReader().readText().isNotBlank()
        }.getOrDefault(false)
    }

    private fun checkKernelSu(): Boolean =
        File("/data/adb/ksu").exists() || File("/data/adb/ksud").exists()
}

data class EnvironmentInfo(
    val arch: String,
    val hasRoot: Boolean,
    val hasKernelSu: Boolean,
    val useProot: Boolean
)
