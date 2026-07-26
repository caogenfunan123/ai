package com.her.aimodifier.container.deploy

import android.content.Context
import com.her.aimodifier.base.constants.PathConstants
import com.her.aimodifier.utils.ZstdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * rootfs 解压与初始化部署。
 *
 * 流程：
 * 1. 释放 assets/proot/rootfs-min.zst 到 cache
 * 2. 调用 [ZstdUtil] 解压为 rootfs.tar
 * 3. tar 解包到 [PathConstants.rootfsRoot]
 * 4. 推送 assets/proot/init.sh 到容器内 /root/init.sh
 * 5. 权限修复
 *
 * 部署幂等：检测到 etc/os-release 存在则跳过。
 */
class RootfsDeployer(private val context: Context) {

    enum class State { NOT_DEPLOYED, DEPLOYING, DEPLOYED, FAILED }

    @Volatile
    var state: State = State.NOT_DEPLOYED
        private set

    fun isDeployed(): Boolean = state == State.DEPLOYED &&
        File(PathConstants.rootfsRoot, "etc/os-release").exists()

    suspend fun deploy(progress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (isDeployed()) return@withContext
        state = State.DEPLOYING
        try {
            progress(5)
            val rootfsZst = releaseAsset("proot/rootfs-min.zst", "rootfs-min.zst")
            progress(20)

            val rootfsTar = File(context.cacheDir, "rootfs.tar")
            if (!rootfsTar.exists() || rootfsTar.length() == 0L) {
                ZstdUtil.decompress(rootfsZst, rootfsTar)
            }
            progress(50)

            extractTar(rootfsTar, PathConstants.rootfsRoot)
            progress(75)

            pushInitScript()
            progress(90)

            fixPermissions()
            progress(100)

            state = State.DEPLOYED
        } catch (t: Throwable) {
            state = State.FAILED
            throw t
        }
    }

    /**
     * 释放 assets 资源到 cache 目录。
     * assets 必须内置该文件，否则抛错（rootfs 不支持占位降级）。
     */
    private fun releaseAsset(assetPath: String, cacheName: String): File {
        val target = File(context.cacheDir, cacheName)
        if (target.exists() && target.length() > 0) return target
        val available = runCatching { context.assets.open(assetPath) }.isSuccess
        check(available) { "assets 缺少必要文件: $assetPath" }
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    /**
     * 解 tar 到目标目录。
     * 用系统 tar 命令实现（Android 自带 toybox tar）。
     *
     * 校验：rootfs.tar 是 zst 解压后的未压缩 tar，"ustar\0" magic 位于偏移 257。
     * 若文件非空但 magic 不符，可能是 gzip 压缩的 tar.gz，尝试加 -z 自动解压。
     */
    private fun extractTar(tarFile: File, targetDir: File) {
        targetDir.mkdirs()
        if (tarFile.length() == 0L) {
            error("rootfs.tar 为空，检查 assets/proot/rootfs-min.zst 是否有效")
        }
        val useGzip = !isValidUstar(tarFile) && isGzipMagic(tarFile)
        val args = if (useGzip) {
            listOf("tar", "-xzf", tarFile.absolutePath, "-C", targetDir.absolutePath)
        } else {
            listOf("tar", "-xf", tarFile.absolutePath, "-C", targetDir.absolutePath)
        }
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        val process = pb.start()
        process.inputStream.bufferedReader().useLines { /* drain */ }
        val code = process.waitFor()
        check(code == 0) { "tar 解压失败 exit=$code args=${args.joinToString(" ")}" }
        // 校验解压结果：必须存在 etc/os-release
        check(File(targetDir, "etc/os-release").exists()) {
            "rootfs 解压完成但缺少 etc/os-release，可能是损坏的 rootfs"
        }
    }

    /** tar 的 "ustar\0" magic 位于偏移 257 */
    private fun isValidUstar(file: File): Boolean = runCatching {
        if (file.length() < 265) return false
        file.inputStream().use { input ->
            val skip = input.skip(257)
            if (skip != 257L) return false
            val magic = ByteArray(5)
            val read = input.read(magic)
            read == 5 && String(magic) == "ustar"
        }
    }.getOrDefault(false)

    /** gzip magic: 1f 8b */
    private fun isGzipMagic(file: File): Boolean = runCatching {
        if (file.length() < 2) return false
        file.inputStream().use { input ->
            val magic = ByteArray(2)
            input.read(magic)
            magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        }
    }.getOrDefault(false)

    private fun pushInitScript() {
        val target = File(PathConstants.containerHome, "init.sh")
        runCatching {
            context.assets.open("proot/init.sh").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure {
            // assets 没有时使用内联默认脚本
            target.writeText(DEFAULT_INIT_SH)
        }
        target.setExecutable(true, false)
    }

    private fun fixPermissions() {
        listOf("root", "tmp", "var", "opt", "etc").forEach { dir ->
            File(PathConstants.rootfsRoot, dir).apply {
                mkdirs()
                setExecutable(true, false)
                setReadable(true, false)
                setWritable(true, false)
            }
        }
    }

    fun destroy() {
        state = State.NOT_DEPLOYED
        PathConstants.rootfsRoot.deleteRecursively()
        PathConstants.rootfsRoot.mkdirs()
    }

    private companion object {
        const val DEFAULT_INIT_SH = """#!/system/bin/sh
set -e
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/toolchain"
export HOME=/root
export LANG=C.UTF-8
mkdir -p /root /tmp /var /opt /opt/toolchain /root/workspace /opt/models
chmod 0755 /root /tmp /var /opt 2>/dev/null || true
echo "[init] ok"
"""
    }
}
