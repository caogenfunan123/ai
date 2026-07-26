package com.her.aimodifier.filesystem

import com.her.aimodifier.base.constants.PathConstants
import java.io.File

/**
 * PRoot 容器内外路径双向映射。
 *
 * 容器视角（rootfs 内）：[PathConstants.Container] 中定义的固定路径
 * 本机视角：rootfsRoot + 容器内绝对路径
 *
 * 例：
 * - 容器内 /root/workspace   ←→  本机 <rootfs>/root/workspace
 * - 容器内 /opt/toolchain    ←→  本机 <rootfs>/opt/toolchain
 *
 * workspace 源码目录会通过 bind mount 挂载到容器内 /root/workspace/<workspaceId>
 */
class ProotPathMapper {

    /** 本机路径 → 容器内路径 */
    fun toContainer(androidPath: String): String {
        val rootfs = PathConstants.rootfsRoot.absolutePath
        return when {
            // workspace 源码目录挂载点
            androidPath.startsWith(PathConstants.workspaceRoot.absolutePath) -> {
                val rel = androidPath.removePrefix(PathConstants.workspaceRoot.absolutePath)
                "${PathConstants.Container.WORKSPACE_MOUNT}$rel"
            }
            // 本地模型目录挂载点
            androidPath.startsWith(PathConstants.localModelDir.absolutePath) -> {
                val rel = androidPath.removePrefix(PathConstants.localModelDir.absolutePath)
                "${PathConstants.Container.MODELS}$rel"
            }
            // rootfs 内文件：直接去掉 rootfs 前缀
            androidPath.startsWith(rootfs) -> {
                androidPath.removePrefix(rootfs).ifEmpty { PathConstants.Container.ROOT }
            }
            else -> androidPath // 不在映射范围内，原样返回
        }
    }

    /** 容器内路径 → 本机路径 */
    fun toAndroid(containerPath: String): String {
        val rootfs = PathConstants.rootfsRoot.absolutePath
        return when {
            containerPath == PathConstants.Container.ROOT -> rootfs
            containerPath.startsWith(PathConstants.Container.WORKSPACE_MOUNT) -> {
                val rel = containerPath.removePrefix(PathConstants.Container.WORKSPACE_MOUNT)
                "${PathConstants.workspaceRoot.absolutePath}$rel"
            }
            containerPath.startsWith(PathConstants.Container.MODELS) -> {
                val rel = containerPath.removePrefix(PathConstants.Container.MODELS)
                "${PathConstants.localModelDir.absolutePath}$rel"
            }
            containerPath.startsWith("/") -> File(rootfs, containerPath.drop(1)).absolutePath
            else -> containerPath
        }
    }

    /** workspace 源码在容器内的挂载路径 */
    fun workspaceMountPoint(workspaceId: String): String =
        "${PathConstants.Container.WORKSPACE_MOUNT}/$workspaceId"
}
