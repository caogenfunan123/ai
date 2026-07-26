package com.her.aimodifier.filesystem

import com.her.aimodifier.base.constants.PathConstants

/**
 * 安卓本机路径访问层。
 *
 * 与 [ProotPathMapper] 配合，业务层只用本机路径；
 * 需要把路径传给容器内命令时，通过 [ProotPathMapper.toContainer] 转换。
 */
object AndroidPath {

    /** workspace 源码目录（安卓本机绝对路径） */
    fun workspaceSource(workspaceId: String): String =
        PathConstants.workspaceSourceDir(workspaceId).absolutePath

    /** workspace 缓存目录 */
    fun workspaceCache(workspaceId: String): String =
        PathConstants.workspaceCacheDir(workspaceId).absolutePath

    /** 本地 GGUF 模型目录 */
    fun localModelDir(): String = PathConstants.localModelDir.absolutePath

    /** rootfs 部署根目录 */
    fun rootfsRoot(): String = PathConstants.rootfsRoot.absolutePath

    /** 工具链目录（容器视角 /opt/toolchain 对应的本机路径） */
    fun toolchainDir(): String = PathConstants.toolchainDir.absolutePath
}
