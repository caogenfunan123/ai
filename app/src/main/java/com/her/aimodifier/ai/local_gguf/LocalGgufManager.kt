package com.her.aimodifier.ai.local_gguf

import android.content.Context
import android.util.Log
import com.her.aimodifier.container.env.RootEnvironmentDetector
import com.her.aimodifier.utils.ShellUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LocalGgufManager(
    private val context: Context,
    private val rootEnvDetector: RootEnvironmentDetector = RootEnvironmentDetector()
) {

    private data class ModelSession(
        val process: Process,
        val modelPath: String,
        val port: Int,
        var lastAccessTime: Long = System.currentTimeMillis(),
        var locked: Boolean = false
    )

    private val sessions = mutableMapOf<Long, ModelSession>()

    private val loadMutex = Mutex()

    @Volatile
    private var autoUnloadEnabled: Boolean = true

    @Volatile
    private var autoUnloadTimeoutMs: Long = 30_000L

    private var autoUnloadJob: Job? = null

    private var nextPort: Int = 8080

    fun isRunning(modelId: Long? = null): Boolean {
        return if (modelId != null) {
            sessions[modelId]?.process?.isAlive == true
        } else {
            sessions.values.any { it.process.isAlive }
        }
    }

    fun loadedModelPath(modelId: Long? = null): String? {
        return if (modelId != null) {
            sessions[modelId]?.modelPath
        } else {
            sessions.values.firstOrNull()?.modelPath
        }
    }

    fun currentPort(modelId: Long = sessions.keys.firstOrNull() ?: 0L): Int {
        return sessions[modelId]?.port ?: nextPort
    }

    fun getLoadedModels(): List<String> {
        return sessions.values.map { it.modelPath }
    }

    fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val modelSizes = sessions.values.sumOf { session ->
            File(session.modelPath).length() / (1024 * 1024)
        }
        return usedHeap + modelSizes
    }

    fun isAutoUnloadEnabled(): Boolean = autoUnloadEnabled

    fun setAutoUnloadEnabled(enabled: Boolean) {
        autoUnloadEnabled = enabled
        if (enabled) startAutoUnloadWatcher() else stopAutoUnloadWatcher()
    }

    fun setAutoUnloadTimeout(timeoutMs: Long) {
        autoUnloadTimeoutMs = timeoutMs
    }

    fun lockModel(modelId: Long) {
        sessions[modelId]?.locked = true
        Log.d(TAG, "模型已锁定，防止自动卸载：modelId=$modelId")
    }

    fun unlockModel(modelId: Long) {
        sessions[modelId]?.locked = false
        Log.d(TAG, "模型已解锁：modelId=$modelId")
    }

    fun isModelLocked(modelId: Long): Boolean = sessions[modelId]?.locked ?: false

    fun touchModel(modelId: Long) {
        sessions[modelId]?.lastAccessTime = System.currentTimeMillis()
    }

    private val llamaServerBin: File
        get() = File(context.filesDir, "tools/bin/llama-server")

    suspend fun load(modelPath: String, port: Int = -1, ctxLen: Int = 4096): Boolean =
        withContext(Dispatchers.IO) {
            val modelId = modelPath.hashCode().toLong()

            if (sessions[modelId]?.process?.isAlive == true) {
                touchModel(modelId)
                Log.d(TAG, "模型已加载，刷新访问时间：$modelPath")
                return@withContext true
            }

            if (!File(modelPath).exists()) {
                Log.e(TAG, "模型文件不存在：$modelPath")
                return@withContext false
            }
            if (!llamaServerBin.exists() || llamaServerBin.length() == 0L) {
                Log.e(TAG, "llama-server 二进制未部署：${llamaServerBin.absolutePath}")
                return@withContext false
            }

            loadMutex.withLock {
                if (sessions.values.any { it.process.isAlive }) {
                    Log.w(TAG, "已有模型在加载中，请等待当前操作完成")
                    return@withLock false
                }

                val assignedPort = if (port > 0) port else nextPort++

                val cmd = arrayOf(
                    llamaServerBin.absolutePath,
                    "-m", modelPath,
                    "--port", assignedPort.toString(),
                    "--host", "127.0.0.1",
                    "-c", ctxLen.toString(),
                    "-t", "4",
                    "--nobrowser"
                )

                val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
                val process = pb.start()

                sessions[modelId] = ModelSession(
                    process = process,
                    modelPath = modelPath,
                    port = assignedPort,
                    lastAccessTime = System.currentTimeMillis()
                )

                Thread {
                    process.inputStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { Log.d(TAG, "llama-server: $it") }
                    }
                }.start()

                val ok = withTimeoutOrNull(30_000L) {
                    while (true) {
                        if (healthCheck(assignedPort)) return@withTimeoutOrNull true
                        delay(500)
                    }
                    false
                } ?: false

                if (!ok) {
                    Log.e(TAG, "llama-server 健康检查超时")
                    process.destroyForcibly()
                    sessions.remove(modelId)
                } else {
                    Log.i(TAG, "模型加载成功：$modelPath, port=$assignedPort")
                    if (autoUnloadEnabled) startAutoUnloadWatcher()
                }
                ok
            }
        }

    suspend fun unload(modelId: Long? = null) = withContext(Dispatchers.IO) {
        if (modelId != null) {
            val session = sessions.remove(modelId)
            session?.process?.destroyForcibly()
            Log.i(TAG, "模型已卸载：${session?.modelPath}")
        } else {
            sessions.values.forEach { it.process.destroyForcibly() }
            sessions.clear()
            Log.i(TAG, "所有模型已卸载")
        }
        if (sessions.isEmpty()) stopAutoUnloadWatcher()
    }

    suspend fun autoUnloadIdle(timeoutMs: Long = autoUnloadTimeoutMs) {
        val now = System.currentTimeMillis()
        val toUnload = sessions.entries.filter { (_, session) ->
            !session.locked && (now - session.lastAccessTime) > timeoutMs
        }
        toUnload.forEach { (modelId, session) ->
            Log.i(TAG, "自动卸载空闲模型：${session.modelPath}")
            unload(modelId)
        }
    }

    private fun startAutoUnloadWatcher() {
        if (autoUnloadJob?.isActive == true) return
        autoUnloadJob = GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                delay(autoUnloadTimeoutMs / 2)
                autoUnloadIdle(autoUnloadTimeoutMs)
                if (sessions.isEmpty()) break
            }
        }
    }

    private fun stopAutoUnloadWatcher() {
        autoUnloadJob?.cancel()
        autoUnloadJob = null
    }

    private fun healthCheck(port: Int): Boolean {
        return runCatching {
            val conn = URL("http://127.0.0.1:$port/v1/models").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "AiModifier/LocalGguf"
    }
}