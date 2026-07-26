package com.her.aimodifier.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.her.aimodifier.base.constants.PathConstants
import com.her.aimodifier.data.database.entity.LocalModelEntity
import com.her.aimodifier.data.repository.LocalModelRepository
import com.her.aimodifier.di.ServiceLocator
import com.her.aimodifier.utils.DownloadUtil
import com.her.aimodifier.utils.HashUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LocalModelViewModel(
    private val repository: LocalModelRepository = ServiceLocator.localModelRepository,
    private val ggufManager: com.her.aimodifier.ai.local_gguf.LocalGgufManager = ServiceLocator.localGgufManager
) : ViewModel() {

    val models: StateFlow<List<LocalModelEntity>> =
        repository.observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _downloadProgress = MutableStateFlow(-1)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _verificationResult = MutableStateFlow<VerificationResult?>(null)
    val verificationResult: StateFlow<VerificationResult?> = _verificationResult.asStateFlow()

    private val _cacheClearedSize = MutableStateFlow(0L)
    val cacheClearedSize: StateFlow<Long> = _cacheClearedSize.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _memoryUsage = MutableStateFlow(0L)
    val memoryUsage: StateFlow<Long> = _memoryUsage.asStateFlow()

    private val _autoUnloadEnabled = MutableStateFlow(true)
    val autoUnloadEnabled: StateFlow<Boolean> = _autoUnloadEnabled.asStateFlow()

    private val _lockedModels = MutableStateFlow<Set<Long>>(emptySet())
    val lockedModels: StateFlow<Set<Long>> = _lockedModels.asStateFlow()

    private val _loadedModelNames = MutableStateFlow<List<String>>(emptyList())
    val loadedModelNames: StateFlow<List<String>> = _loadedModelNames.asStateFlow()

    fun addModel(name: String, filePath: String, quant: String, sizeBytes: Long, contextLength: Int) {
        viewModelScope.launch {
            repository.upsert(
                LocalModelEntity(
                    name = name,
                    filePath = filePath,
                    quant = quant,
                    sizeBytes = sizeBytes,
                    contextLength = contextLength
                )
            )
        }
    }

    fun load(id: Long) {
        viewModelScope.launch {
            val m = repository.findById(id) ?: return@launch
            repository.setLoaded(id, true)
            ggufManager.load(m.filePath)
            refreshMemoryUsage()
            refreshLoadedModels()
        }
    }

    fun unload(id: Long) {
        viewModelScope.launch {
            val m = repository.findById(id) ?: return@launch
            val modelId = m.filePath.hashCode().toLong()
            ggufManager.unload(modelId)
            repository.setLoaded(id, false)
            refreshMemoryUsage()
            refreshLoadedModels()
        }
    }

    fun unloadAll() {
        viewModelScope.launch {
            ggufManager.unload()
            repository.unloadAll()
            refreshMemoryUsage()
            refreshLoadedModels()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    fun lockModel(id: Long) {
        viewModelScope.launch {
            val m = repository.findById(id) ?: return@launch
            val modelId = m.filePath.hashCode().toLong()
            ggufManager.lockModel(modelId)
            _lockedModels.value = _lockedModels.value + modelId
        }
    }

    fun unlockModel(id: Long) {
        viewModelScope.launch {
            val m = repository.findById(id) ?: return@launch
            val modelId = m.filePath.hashCode().toLong()
            ggufManager.unlockModel(modelId)
            _lockedModels.value = _lockedModels.value - modelId
        }
    }

    fun toggleAutoUnload(enabled: Boolean) {
        _autoUnloadEnabled.value = enabled
        ggufManager.setAutoUnloadEnabled(enabled)
    }

    fun refreshMemoryUsage() {
        _memoryUsage.value = ggufManager.getMemoryUsage()
    }

    fun refreshLoadedModels() {
        _loadedModelNames.value = ggufManager.getLoadedModels()
    }

    fun downloadModel(url: String, name: String, saveDir: String) {
        viewModelScope.launch {
            _downloading.value = true
            _downloadProgress.value = 0
            _message.value = null

            runCatching {
                val dir = resolveSaveDir(saveDir)
                val fileName = url.substringAfterLast('/').ifBlank { "$name.gguf" }
                val targetFile = File(dir, fileName)

                withContext(Dispatchers.IO) {
                    DownloadUtil.downloadWithResume(
                        url = url,
                        target = targetFile,
                        progress = { p -> _downloadProgress.value = p }
                    )
                }

                val sha256 = withContext(Dispatchers.IO) { HashUtil.sha256(targetFile) }
                val sizeBytes = targetFile.length()

                repository.upsert(
                    LocalModelEntity(
                        name = name,
                        filePath = targetFile.absolutePath,
                        sizeBytes = sizeBytes,
                        quant = "Q4_K_M",
                        sha256 = sha256,
                        sourceUrl = url,
                        status = LocalModelEntity.STATUS_OK
                    )
                )

                _downloadProgress.value = 100
                _message.value = "模型下载完成：$name"
            }.onFailure { e ->
                _message.value = "下载失败：${e.message}"
            }

            _downloading.value = false
        }
    }

    fun verifyIntegrity(id: Long) {
        viewModelScope.launch {
            _verificationResult.value = VerificationResult.Verifying(id)

            val model = repository.findById(id)
                ?: run {
                    _verificationResult.value = VerificationResult.NotFound(id)
                    return@launch
                }

            val file = File(model.filePath)
            if (!file.exists()) {
                repository.updateHashAndStatus(id, model.sha256, LocalModelEntity.STATUS_DAMAGED)
                _verificationResult.value = VerificationResult.Damaged(id, "文件不存在")
                return@launch
            }

            val actualSha256 = withContext(Dispatchers.IO) { HashUtil.sha256(file) }
            val storedSha256 = model.sha256

            val ok = if (storedSha256.isNullOrBlank()) {
                true
            } else {
                actualSha256.equals(storedSha256, ignoreCase = true)
            }

            if (ok) {
                repository.updateHashAndStatus(id, actualSha256, LocalModelEntity.STATUS_OK)
                _verificationResult.value = VerificationResult.Pass(id, actualSha256)
            } else {
                repository.updateHashAndStatus(id, actualSha256, LocalModelEntity.STATUS_DAMAGED)
                _verificationResult.value = VerificationResult.Fail(id, storedSha256, actualSha256)
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            val cacheDir = PathConstants.downloadCacheDir
            var cleared = 0L
            if (cacheDir.exists()) {
                cleared = calculateDirSize(cacheDir)
                withContext(Dispatchers.IO) {
                    cacheDir.deleteRecursively()
                    cacheDir.mkdirs()
                }
            }
            _cacheClearedSize.value = cleared
            _message.value = "缓存已清理：${formatSize(cleared)}"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun resetVerificationResult() {
        _verificationResult.value = null
    }

    private fun resolveSaveDir(saveDir: String): File {
        return when (saveDir) {
            SAVE_PRIVATE -> PathConstants.localModelDir
            SAVE_PUBLIC -> {
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "models")
                dir.mkdirs()
                dir
            }
            SAVE_SD_CARD -> {
                val dir = File(android.os.Environment.getExternalStorageDirectory(), "AiModifier/models")
                dir.mkdirs()
                dir
            }
            else -> {
                val dir = File(saveDir)
                dir.mkdirs()
                dir
            }
        }
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                size += if (file.isFile) file.length() else calculateDirSize(file)
            }
        }
        return size
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    sealed class VerificationResult {
        data class Verifying(val id: Long) : VerificationResult()
        data class Pass(val id: Long, val sha256: String) : VerificationResult()
        data class Fail(val id: Long, val expected: String?, val actual: String) : VerificationResult()
        data class Damaged(val id: Long, val reason: String) : VerificationResult()
        data class NotFound(val id: Long) : VerificationResult()
    }

    companion object {
        const val SAVE_PRIVATE = "private"
        const val SAVE_PUBLIC = "public"
        const val SAVE_SD_CARD = "sdcard"
    }
}