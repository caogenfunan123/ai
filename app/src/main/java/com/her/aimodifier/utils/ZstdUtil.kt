package com.her.aimodifier.utils

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Zstandard 压缩/解压工具。
 *
 * 用于：
 * - rootfs 镜像解压（assets/proot/rootfs-min.zst → rootfs 目录）
 * - 容器快照打包/恢复
 */
object ZstdUtil {

    /** 解压 .zst 文件到目标文件 */
    fun decompress(srcZst: File, target: File) {
        target.parentFile?.mkdirs()
        FileInputStream(srcZst).use { fis ->
            ZstdInputStream(fis).use { zis ->
                FileOutputStream(target).use { fos ->
                    zis.copyTo(fos)
                }
            }
        }
    }

    /** 解压 .zst 文件到目标目录（输出文件名取去掉 .zst 后缀） */
    fun decompressToDir(srcZst: File, targetDir: File): File {
        targetDir.mkdirs()
        val outName = srcZst.name.removeSuffix(".zst")
        val out = File(targetDir, outName)
        decompress(srcZst, out)
        return out
    }

    /** 压缩文件为 .zst */
    fun compress(src: File, targetZst: File) {
        targetZst.parentFile?.mkdirs()
        FileInputStream(src).use { fis ->
            FileOutputStream(targetZst).use { fos ->
                ZstdOutputStream(fos).use { zos ->
                    fis.copyTo(zos)
                }
            }
        }
    }
}
