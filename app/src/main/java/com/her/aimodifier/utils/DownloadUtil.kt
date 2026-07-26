package com.her.aimodifier.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

object DownloadUtil {

    private const val CHANNEL_ID = "ai_modifier_download"
    private const val NOTIFICATION_ID = 1001
    private const val MAX_RETRIES = 3
    private const val BASE_RETRY_DELAY_MS = 1000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class DownloadTask(
        val url: String,
        val dest: File,
        val expectedSha256: String?,
        val onProgress: (Int) -> Unit,
        val onComplete: ((File) -> Unit)?,
        val onError: ((Throwable) -> Unit)?,
        val context: Context?,
        var retries: Int = 0
    )

    private val downloadQueue = ConcurrentLinkedQueue<DownloadTask>()
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _activeDownloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val activeDownloads: StateFlow<List<DownloadTask>> = _activeDownloads.asStateFlow()

    fun downloadWithProgress(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit = {}
    ): File {
        dest.parentFile?.mkdirs()

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            }

            val total = response.body?.contentLength() ?: -1L
            val source = response.body?.byteStream() ?: throw IllegalStateException("空响应体")
            val raf = RandomAccessFile(dest, "rw")
            try {
                val buf = ByteArray(64 * 1024)
                var downloaded = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) {
                        onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
                if (total <= 0) onProgress(100)
            } finally {
                raf.close()
            }
        }
        return dest
    }

    fun downloadWithResume(
        url: String,
        target: File,
        expectedSha256: String? = null,
        progress: (Int) -> Unit = {}
    ): File {
        target.parentFile?.mkdirs()

        val existingBytes = if (target.exists()) target.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            }

            val total = response.body?.contentLength() ?: -1L
            val totalExpected = if (existingBytes > 0) existingBytes + total else total

            val raf = RandomAccessFile(target, "rw")
            try {
                raf.seek(existingBytes)
                val source = response.body?.byteStream() ?: throw IllegalStateException("空响应体")
                val buf = ByteArray(64 * 1024)
                var downloaded = existingBytes
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    downloaded += n
                    if (totalExpected > 0) {
                        progress(((downloaded * 100) / totalExpected).toInt().coerceIn(0, 100))
                    }
                }
            } finally {
                raf.close()
            }
        }

        if (expectedSha256 != null) {
            val actual = HashUtil.sha256(target)
            check(actual.equals(expectedSha256, ignoreCase = true)) {
                "SHA256 校验失败：expected=$expectedSha256 actual=$actual"
            }
        }

        return target
    }

    suspend fun downloadWithRetry(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        onProgress: (Int) -> Unit = {},
        maxRetries: Int = MAX_RETRIES
    ): File {
        var lastError: Throwable? = null
        repeat(maxRetries) { attempt ->
            try {
                return downloadWithResume(
                    url = url,
                    target = dest,
                    expectedSha256 = expectedSha256,
                    progress = onProgress
                )
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries - 1) {
                    val delayMs = BASE_RETRY_DELAY_MS * (1L shl attempt.coerceAtMost(4))
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("下载失败，已重试 $maxRetries 次")
    }

    fun enqueueDownload(
        url: String,
        dest: File,
        context: Context? = null,
        expectedSha256: String? = null,
        onProgress: (Int) -> Unit = {},
        onComplete: ((File) -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        val task = DownloadTask(
            url = url,
            dest = dest,
            expectedSha256 = expectedSha256,
            onProgress = onProgress,
            onComplete = onComplete,
            onError = onError,
            context = context
        )
        downloadQueue.add(task)
        processQueue()
    }

    private fun processQueue() {
        if (_isDownloading.value) return
        val task = downloadQueue.poll() ?: return
        _isDownloading.value = true
        _activeDownloads.value = listOf(task)

        downloadScope.launch {
            try {
                downloadWithRetry(
                    url = task.url,
                    dest = task.dest,
                    expectedSha256 = task.expectedSha256,
                    onProgress = { progress ->
                        task.onProgress(progress)
                        task.context?.let { sendDownloadNotification(it, task.dest.name, progress) }
                    }
                )
                task.onComplete?.invoke(task.dest)
            } catch (e: Exception) {
                task.onError?.invoke(e)
            } finally {
                _isDownloading.value = false
                _activeDownloads.value = emptyList()
                task.context?.let { cancelNotification(it) }
                if (downloadQueue.isNotEmpty()) processQueue()
            }
        }
    }

    fun cancelAllDownloads() {
        downloadQueue.clear()
        _isDownloading.value = false
    }

    private fun sendDownloadNotification(context: Context, fileName: String, progress: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模型下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示模型下载进度"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("正在下载：$fileName")
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    fun destroy() {
        downloadQueue.clear()
        downloadScope.cancel()
    }
}