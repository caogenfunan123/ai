package com.her.aimodifier.container.deploy

import android.content.Context
import com.her.aimodifier.base.constants.PathConstants
import java.io.File

/**
 * PRoot 二进制安装器。
 *
 * 从 assets/proot/ 释放 proot + loader + 依赖库到 [PathConstants] 指定目录，
 * 并设置可执行权限。
 *
 * 期望 assets 结构：
 * - assets/proot/proot              (ARM64 ELF 二进制)
 * - assets/proot/proot-loader.so    (静态 loader)
 * - assets/proot/libtalloc.so.2     (PRoot 主依赖)
 * - assets/proot/libtalloc.so.2.4.3 (版本别名，部分路径查找需要)
 * - assets/proot/libandroid-shmem.so (Android shmem 兼容层)
 *
 * 释放策略：
 * - proot / proot-loader.so → tools/bin/
 * - libtalloc*.so / libandroid-shmem.so → tools/lib/
 *
 * 部署幂等：所有目标文件存在且大小 > 0 时跳过。
 */
class ProotBinaryInstaller(private val context: Context) {

    private val binDir: File
        get() = File(context.filesDir, "tools/bin").apply { mkdirs() }

    private val libDir: File
        get() = File(context.filesDir, "tools/lib").apply { mkdirs() }

    /** 已安装：proot 与全部依赖库均存在且非空 */
    fun isInstalled(): Boolean {
        val required = listOf(
            File(binDir, "proot"),
            File(binDir, "proot-loader.so"),
            File(libDir, "libtalloc.so.2"),
            File(libDir, "libandroid-shmem.so")
        )
        return required.all { it.exists() && it.length() > 0L }
    }

    /**
     * 释放 PRoot 二进制及其依赖库。
     * @return proot 可执行文件路径
     */
    fun install(): File {
        val proot = File(binDir, "proot")
        val loader = File(binDir, "proot-loader.so")
        val libtalloc = File(libDir, "libtalloc.so.2")
        val libtallocVer = File(libDir, "libtalloc.so.2.4.3")
        val libshmem = File(libDir, "libandroid-shmem.so")

        // bin/ 下的可执行文件
        listOf("proot" to proot, "proot-loader.so" to loader).forEach { (name, target) ->
            releaseAsset(name, target, executable = true)
        }
        // lib/ 下的共享库
        listOf(
            "libtalloc.so.2" to libtalloc,
            "libtalloc.so.2.4.3" to libtallocVer,
            "libandroid-shmem.so" to libshmem
        ).forEach { (name, target) ->
            releaseAsset(name, target, executable = false)
        }
        // 创建 soname 软链兜底：libtalloc.so.2 → libtalloc.so.2.4.3
        // 某些 loader 查找顺序会优先找带版本号文件
        if (libtallocVer.exists() && !libtalloc.exists()) {
            runCatching { libtalloc.createNewFile() }
        }
        return proot
    }

    private fun releaseAsset(assetName: String, target: File, executable: Boolean) {
        if (target.exists() && target.length() > 0L) return
        val assetPath = "proot/$assetName"
        val available = context.assets.list("proot")?.contains(assetName) == true
        if (available) {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (executable) {
                target.setExecutable(true, false)
            } else {
                target.setReadable(true, false)
            }
        } else {
            // assets 缺失时不写占位（避免被误判已安装），直接抛错由上层处理
            error("assets 缺少必要文件: $assetPath")
        }
    }
}
