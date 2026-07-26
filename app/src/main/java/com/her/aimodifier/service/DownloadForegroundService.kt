package com.her.aimodifier.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.her.aimodifier.R
import com.her.aimodifier.base.constants.AppConstants

/**
 * 下载前台服务。
 *
 * 用途：息屏持续下载镜像 / 模型，避免被系统杀进程。
 * 调用方通过 [start] 启动，传文件名 + 进度更新；下载完调用 [stop]。
 */
class DownloadForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: "文件"
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        startForeground(
            NOTIFICATION_ID,
            buildNotification(fileName, progress)
        )
        return START_NOT_STICKY
    }

    private fun buildNotification(fileName: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, AppConstants.NotificationChannels.DOWNLOAD)
            .setContentTitle("正在下载：$fileName")
            .setContentText(getString(R.string.notification_download_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress <= 0)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_PROGRESS = "progress"

        fun start(context: Context, fileName: String) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                putExtra(EXTRA_FILE_NAME, fileName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }
}
