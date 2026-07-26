package com.her.aimodifier.base

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.base.constants.PathConstants
import com.her.aimodifier.base.exception.GlobalExceptionHandler
import com.her.aimodifier.di.ServiceLocator

/**
 * 应用入口。
 *
 * 职责：
 * 1. 路径初始化
 * 2. 全局异常捕获
 * 3. 通知渠道
 * 4. [ServiceLocator] 装配（替代 Hilt，避免引入额外 KSP 处理器）
 */
class AiModifierApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. 路径常量
        PathConstants.init(this)

        // 2. 全局异常捕获
        GlobalExceptionHandler.install(this)

        // 3. 通知渠道
        registerNotificationChannels()

        // 4. 依赖装配
        ServiceLocator.init(this)

        // 5. SQLCipher native 库加载（Room 加密数据库依赖）
        try {
            net.sqlcipher.database.SQLiteDatabase.loadLibs(this)
        } catch (t: Throwable) {
            // 部分设备首次加载延迟，懒加载即可
        }
    }

    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                AppConstants.NotificationChannels.DOWNLOAD,
                getString(com.her.aimodifier.R.string.notification_channel_download),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                AppConstants.NotificationChannels.LONG_TASK,
                getString(com.her.aimodifier.R.string.notification_channel_long_task),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}
