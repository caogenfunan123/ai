package com.her.aimodifier.base.constants

import android.content.Context
import java.io.File

/**
 * 应用所有路径的集中管理（最终定稿版）。
 *
 * 路径分两类：
 * 1. 安卓本机路径（[androidBase]）
 *    - Root 设备：编译/逆向工具部署到 [rootToolchainDir]
 *    - 无 Root 设备：本机不存任何编译工具，全部走 PRoot 容器
 * 2. PRoot 容器内路径（[ProotPathMapper]）：[Container] 中定义
 *
 * [init] 必须在 [AiModifierApplication.onCreate] 中调用一次。
 */
object PathConstants {

    @Volatile
    private var initialized = false

    /** 应用根目录（等同 context.filesDir） */
    lateinit var androidBase: File
        private set

    /** Workspace 根目录：每个工作区一个子文件夹 */
    lateinit var workspaceRoot: File
        private set

    /** PRoot rootfs 部署目录（容器根，对应容器视角 /） */
    lateinit var rootfsRoot: File
        private set

    /** 容器内 home（rootfs 内 /root） */
    lateinit var containerHome: File
        private set

    /** 容器内 /opt/toolchain（本机路径） */
    lateinit var containerToolchainDir: File
        private set

    /** Root 本机工具链目录（/data/data/<pkg>/toolchain/） */
    lateinit var rootToolchainDir: File
        private set

    /** 本地 GGUF 模型目录 */
    lateinit var localModelDir: File
        private set

    /** 缓存目录（断点续传临时分片） */
    lateinit var downloadCacheDir: File
        private set

    /** 容器快照目录 */
    lateinit var snapshotDir: File
        private set

    /** PRoot 二进制依赖库目录（存放 libtalloc/libandroid-shmem 等） */
    lateinit var toolsLibDir: File
        private set

    /** 容器内固定挂载路径（容器视角） */
    object Container {
        const val ROOT = "/"
        const val HOME = "/root"
        const val WORKSPACE_MOUNT = "/root/workspace"
        const val TOOLCHAIN = "/opt/toolchain"
        const val MODELS = "/opt/models"

        // 容器内 /opt/toolchain 子目录（最终定稿）
        const val JDK = "/opt/toolchain/jdk"
        const val ANDROID_SDK = "/opt/toolchain/android-sdk"
        const val NDK = "/opt/toolchain/ndk"
        const val GRADLE = "/opt/toolchain/gradle"
        const val FRIDA = "/opt/toolchain/frida"
        const val APKTOOL = "/opt/toolchain/apktool"
        const val LSPATCH = "/opt/toolchain/lspatch"
        const val MAGISK_TOOLS = "/opt/toolchain/magisk-tools"
        const val MITMPROXY = "/opt/toolchain/mitmproxy"
        const val EXTRA_BIN = "/opt/toolchain/extra-bin"
    }

    /** Root 本机工具链子目录（与本机/容器共享同一逻辑名） */
    val RootToolchain = Container  // 复用同名常量，路径前缀差异由 ToolchainPathResolver 处理

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            androidBase = context.filesDir
            workspaceRoot = File(androidBase, "workspaces").apply { mkdirs() }
            rootfsRoot = File(androidBase, "rootfs").apply { mkdirs() }
            containerHome = File(rootfsRoot, "root").apply { mkdirs() }
            containerToolchainDir = File(rootfsRoot, "opt/toolchain").apply { mkdirs() }
            rootToolchainDir = File(androidBase, "toolchain").apply { mkdirs() }
            localModelDir = File(androidBase, "models").apply { mkdirs() }
            downloadCacheDir = File(context.cacheDir, "downloads").apply { mkdirs() }
            snapshotDir = File(androidBase, "snapshots").apply { mkdirs() }
            toolsLibDir = File(androidBase, "tools/lib").apply { mkdirs() }
            initialized = true
        }
    }

    /** 单个 workspace 的目录（按 workspaceId） */
    fun workspaceDir(workspaceId: String): File =
        File(workspaceRoot, workspaceId).apply { mkdirs() }

    /** workspace 的源码目录 */
    fun workspaceSourceDir(workspaceId: String): File =
        File(workspaceDir(workspaceId), "src").apply { mkdirs() }

    /** workspace 的缓存目录（编译产物等） */
    fun workspaceCacheDir(workspaceId: String): File =
        File(workspaceDir(workspaceId), "cache").apply { mkdirs() }

    /** workspace 的 .ai 配置目录 */
    fun workspaceAiDir(workspaceId: String): File =
        File(workspaceDir(workspaceId), ".ai").apply { mkdirs() }
}
