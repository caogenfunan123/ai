package com.her.aimodifier.base.exception

import android.util.Log
import kotlin.system.exitProcess

/**
 * 全局异常捕获：
 * - 捕获未处理异常，输出到日志
 * - 兜底进入默认异常流程（避免静默崩溃）
 *
 * 在 [com.her.aimodifier.base.AiModifierApplication.onCreate] 中通过
 * [install] 注册。
 */
class GlobalExceptionHandler(
    private val context: android.content.Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        Log.e(TAG, "Uncaught exception on thread=${t.name}", e)
        // TODO: 写入崩溃日志文件到 PathConstants.downloadCacheDir/crash/
        // TODO: 通过 LiveEventBus / StateFlow 通知 UI 显示占位兜底页（如果能恢复）

        defaultHandler?.uncaughtException(t, e) ?: run {
            // 没有默认处理器时直接结束进程，避免卡死
            exitProcess(2)
        }
    }

    companion object {
        private const val TAG = "AiModifier/Crash"

        fun install(context: android.content.Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is GlobalExceptionHandler) return
            Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(context, current))
        }
    }
}
