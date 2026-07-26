package com.her.aimodifier.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object LogStream {

    enum class Level { INFO, WARN, ERROR, DEBUG }

    data class LogEntry(val level: Level, val message: String, val taskId: String)

    private val mutex = Mutex()
    private val streams = mutableMapOf<String, MutableSharedFlow<String>>()
    private val entryStreams = mutableMapOf<String, MutableSharedFlow<LogEntry>>()
    private val histories = mutableMapOf<String, MutableList<String>>()
    private val entryHistories = mutableMapOf<String, MutableList<LogEntry>>()

    private const val BUFFER = 256
    private const val HISTORY_LIMIT = 10000

    private val _scrollEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val scrollEvents: SharedFlow<Unit> = _scrollEvents.asSharedFlow()

    @Volatile
    var autoScrollEnabled: Boolean = true

    fun observe(taskId: String): SharedFlow<String> = getOrCreate(taskId).asSharedFlow()

    fun observeEntries(taskId: String): SharedFlow<LogEntry> = getOrCreateEntry(taskId).asSharedFlow()

    fun subscribe(taskId: String): Handle {
        getOrCreate(taskId)
        getOrCreateEntry(taskId)
        return Handle(taskId)
    }

    suspend fun emit(taskId: String, line: String) {
        getOrCreate(taskId).emit(line)
        val entry = parseEntry(taskId, line)
        getOrCreateEntry(taskId).emit(entry)
        mutex.withLock {
            histories.getOrPut(taskId) { mutableListOf() }.apply {
                add(line)
                if (size > HISTORY_LIMIT) removeAt(0)
            }
            entryHistories.getOrPut(taskId) { mutableListOf() }.apply {
                add(entry)
                if (size > HISTORY_LIMIT) removeAt(0)
            }
        }
        if (autoScrollEnabled) _scrollEvents.tryEmit(Unit)
    }

    fun tryEmit(taskId: String, line: String) {
        getOrCreate(taskId).tryEmit(line)
        val entry = parseEntry(taskId, line)
        getOrCreateEntry(taskId).tryEmit(entry)
        synchronized(histories) {
            histories.getOrPut(taskId) { mutableListOf() }.apply {
                add(line)
                if (size > HISTORY_LIMIT) removeAt(0)
            }
            entryHistories.getOrPut(taskId) { mutableListOf() }.apply {
                add(entry)
                if (size > HISTORY_LIMIT) removeAt(0)
            }
        }
        if (autoScrollEnabled) _scrollEvents.tryEmit(Unit)
    }

    fun history(taskId: String): List<String> = synchronized(histories) {
        histories[taskId]?.toList() ?: emptyList()
    }

    fun entryHistory(taskId: String): List<LogEntry> = synchronized(entryHistories) {
        entryHistories[taskId]?.toList() ?: emptyList()
    }

    fun exportToFile(path: String, taskId: String? = null) {
        val entries = if (taskId != null) {
            entryHistory(taskId)
        } else {
            synchronized(entryHistories) {
                entryHistories.values.flatten()
            }
        }
        val sb = StringBuilder()
        sb.appendLine("=== 日志导出 ===")
        sb.appendLine("导出时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())}")
        sb.appendLine("任务数：${entries.map { it.taskId }.distinct().size}")
        sb.appendLine("总条数：${entries.size}")
        sb.appendLine("================")
        sb.appendLine()
        entries.forEach { entry ->
            val prefix = when (entry.level) {
                Level.INFO -> "[INFO]"
                Level.WARN -> "[WARN]"
                Level.ERROR -> "[ERROR]"
                Level.DEBUG -> "[DEBUG]"
            }
            sb.appendLine("$prefix [${entry.taskId}] ${entry.message}")
        }
        File(path).writeText(sb.toString())
    }

    fun clear(taskId: String? = null) {
        if (taskId != null) {
            synchronized(histories) { histories.remove(taskId) }
            synchronized(entryHistories) { entryHistories.remove(taskId) }
            streams.remove(taskId)
            entryStreams.remove(taskId)
        } else {
            synchronized(histories) { histories.clear() }
            synchronized(entryHistories) { entryHistories.clear() }
            streams.clear()
            entryStreams.clear()
        }
    }

    fun close(taskId: String) {
        streams.remove(taskId)
        entryStreams.remove(taskId)
        synchronized(histories) { histories.remove(taskId) }
        synchronized(entryHistories) { entryHistories.remove(taskId) }
    }

    private fun parseEntry(taskId: String, line: String): LogEntry {
        return when {
            line.startsWith("[ERROR]") -> LogEntry(Level.ERROR, line.removePrefix("[ERROR]").trimStart(), taskId)
            line.startsWith("[WARN]") -> LogEntry(Level.WARN, line.removePrefix("[WARN]").trimStart(), taskId)
            line.startsWith("[DEBUG]") -> LogEntry(Level.DEBUG, line.removePrefix("[DEBUG]").trimStart(), taskId)
            else -> LogEntry(Level.INFO, line, taskId)
        }
    }

    private fun getOrCreate(taskId: String): MutableSharedFlow<String> {
        return streams.getOrPut(taskId) {
            MutableSharedFlow(replay = BUFFER, extraBufferCapacity = BUFFER)
        }
    }

    private fun getOrCreateEntry(taskId: String): MutableSharedFlow<LogEntry> {
        return entryStreams.getOrPut(taskId) {
            MutableSharedFlow(replay = BUFFER, extraBufferCapacity = BUFFER)
        }
    }

    class Handle(private val taskId: String) {
        suspend fun emit(line: String) = LogStream.emit(taskId, line)
        fun tryEmit(line: String) = LogStream.tryEmit(taskId, line)

        suspend fun info(msg: String) = emit("[INFO] $msg")
        suspend fun warn(msg: String) = emit("[WARN] $msg")
        suspend fun error(msg: String) = emit("[ERROR] $msg")
        suspend fun debug(msg: String) = emit("[DEBUG] $msg")

        fun infoSync(msg: String) = tryEmit("[INFO] $msg")
        fun warnSync(msg: String) = tryEmit("[WARN] $msg")
        fun errorSync(msg: String) = tryEmit("[ERROR] $msg")
        fun debugSync(msg: String) = tryEmit("[DEBUG] $msg")
    }
}