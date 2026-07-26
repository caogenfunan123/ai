package com.her.aimodifier.business

import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.mcp.core.McpClient
import java.io.File

/**
 * APK 编译引擎（最终定稿）。
 *
 * 系统联动规则：禁止直接操作 PRoot，全部通过 MCP 调度。
 *
 * 流程：
 * 1. 通过 MCP check_env 检测环境
 * 2. 通过 MCP prepare_task("apk_build") 自动补齐 JDK/SDK/NDK/Gradle 等
 * 3. 通过 MCP run_command 执行 gradle assembleDebug
 * 4. 编译完成后自动签名（继续通过 run_command 调 apksigner）
 */
class BuildEngine(mcpClient: McpClient) : McpBasedTask(mcpClient, AppConstants.TaskIds.APK_BUILD) {

    /**
     * 编译 APK。
     *
     * @param workspaceId 工作区ID（自动定位源码目录）
     * @param gradleTask gradle 任务名，默认 assembleDebug
     * @param executionId 业务执行ID
     */
    suspend fun buildApk(
        workspaceId: String,
        gradleTask: String = "assembleDebug",
        executionId: String = ""
    ): McpCallResult {
        val sourceDir = com.her.aimodifier.base.constants.PathConstants.workspaceSourceDir(workspaceId)
        val sourceContainer = if (com.her.aimodifier.di.ServiceLocator.pathResolver.useRootNative)
            sourceDir.absolutePath
        else
            com.her.aimodifier.di.ServiceLocator.prootPathMapper.toContainer(sourceDir.absolutePath)

        val cmd = buildString {
            append("cd ").append(sourceContainer).append(" && ")
            append("chmod +x ./gradlew 2>/dev/null; ")
            append("./gradlew clean ").append(gradleTask).append(" --stacktrace")
        }
        return execute(executionId, cmd, sourceContainer)
    }

    /** 签名 APK（继续通过 MCP 调度 apksigner） */
    suspend fun signApk(
        workspaceId: String,
        apkPath: String,
        keystorePath: String,
        keystorePass: String,
        keyAlias: String,
        keyPass: String
    ): McpCallResult {
        val cmd = buildString {
            append("apksigner sign --ks ").append(keystorePath)
            append(" --ks-pass pass:").append(keystorePass)
            append(" --ks-key-alias ").append(keyAlias)
            append(" --key-pass pass:").append(keyPass)
            append(" --out ").append(apkPath.replace(".apk", "-signed.apk"))
            append(" ").append(apkPath)
        }
        return execute("sign_${System.currentTimeMillis()}", cmd, null)
    }
}
