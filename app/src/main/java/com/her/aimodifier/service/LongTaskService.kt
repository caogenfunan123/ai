package com.her.aimodifier.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.her.aimodifier.R
import com.her.aimodifier.base.constants.AppConstants

/**
 * 长任务保活服务。
 *
 * 用于：容器内编译 / Hook 注入 / 网络抓包 等长任务的进程保活。
 * 业务方在启动长任务时调用 [start]，任务结束调用 [stop]。
 */
class LongTaskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskName = intent?.getStringExtra(EXTRA_TASK_NAME) ?: "任务"
        startForeground(NOTIFICATION_ID, buildNotification(taskName))
        return START_STICKY
    }

    private fun buildNotification(taskName: String) =
        NotificationCompat.Builder(this, AppConstants.NotificationChannels.LONG_TASK)
            .setContentTitle(taskName)
            .setContentText(getString(R.string.notification_long_task_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    companion object {
        const val NOTIFICATION_ID = 1002
        const val EXTRA_TASK_NAME = "task_name"

        fun start(context: Context, taskName: String) {
            val intent = Intent(context, LongTaskService::class.java).apply {
                putExtra(EXTRA_TASK_NAME, taskName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LongTaskService::class.java))
        }
    }
}
