package com.her.aimodifier.container.toolchain

import com.her.aimodifier.base.constants.PathConstants
import com.her.aimodifier.container.env.EnvironmentInfo
import com.her.aimodifier.container.env.RootEnvironmentDetector
import java.io.File

/**
 * 工具链路径解析器（最终定稿双环境适配）。
 *
 * 全局最高强制规则：
 * - 有 Root → 优先本机 [PathConstants.rootToolchainDir] 原生 ARM64 二进制
 * - 无 Root → 强制走 PRoot 容器内 /opt/toolchain
 *
 * manifest 中 tool 的 containerPath/rootPath 字段为相对路径，
 * 由本类根据当前环境拼接为绝对路径。
 */
class ToolchainPathResolver(
    private val rootEnvDetector: RootEnvironmentDetector = RootEnvironmentDetector()
) {

    /** 当前部署环境（缓存检测结果） */
    val environment: EnvironmentInfo by lazy { rootEnvDetector.detect() }

    /** 是否走 Root 本机原生执行 */
    val useRootNative: Boolean get() = environment.hasRoot

    /** 当前生效的工具链根目录（本机视角） */
    val toolchainRoot: File
        get() = if (useRootNative) PathConstants.rootToolchainDir
        else PathConstants.containerToolchainDir

    /**
     * 把 manifest 中的 containerPath / rootPath 解析为当前环境的本机绝对路径。
     *
     * @param containerPath 容器视角路径，如 "/opt/toolchain/jdk"
     * @param rootPath Root 本机相对路径，如 "toolchain/jdk"
     */
    fun resolveHostPath(containerPath: String, rootPath: String): File {
        val rel = if (useRootNative) {
            rootPath.removePrefix("toolchain/")
        } else {
            containerPath.removePrefix(PathConstants.Container.TOOLCHAIN + "/")
        }
        return File(toolchainRoot, rel).apply { parentFile?.mkdirs() }
    }

    /** 把 manifest 中的 tool 解析为容器视角路径（传给容器内命令时使用） */
    fun resolveContainerPath(containerPath: String): String = containerPath

    /**
     * 拼装当前环境的 PATH 变量。
     *
     * Root 本机：toolchain/<sub>/bin:...
     * 容器内：/opt/toolchain/<sub>/bin:...（由 init.sh 注入）
     */
    fun buildPathEnv(): String {
        val subs = listOf("jdk/bin", "android-sdk/cmdline-tools/latest/bin",
            "android-sdk/platform-tools", "ndk/toolchains/llvm/prebuilt/aarch64-linux-android/bin",
            "gradle/bin", "frida/tools/bin", "apktool", "lspatch",
            "magisk-tools", "mitmproxy/bin", "extra-bin")
        return buildString {
            subs.forEach { sub ->
                val prefix = if (useRootNative) "${toolchainRoot.absolutePath}/$sub"
                else "${PathConstants.Container.TOOLCHAIN}/$sub"
                append(prefix).append(":")
            }
            append("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        }
    }

    /** 校验工具文件是否就绪（存在 + 可执行） */
    fun isToolReady(hostFile: File): Boolean =
        hostFile.exists() && hostFile.length() > 0 && hostFile.canExecute()
}
