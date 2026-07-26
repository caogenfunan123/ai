package com.her.aimodifier.ui.local_model

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.her.aimodifier.data.database.entity.LocalModelEntity
import com.her.aimodifier.viewModel.LocalModelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelScreen(
    onBack: () -> Unit = {},
    viewModel: LocalModelViewModel = viewModel()
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val verificationResult by viewModel.verificationResult.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val memoryUsage by viewModel.memoryUsage.collectAsStateWithLifecycle()
    val autoUnloadEnabled by viewModel.autoUnloadEnabled.collectAsStateWithLifecycle()
    val lockedModels by viewModel.lockedModels.collectAsStateWithLifecycle()

    var showDownloadDialog by remember { mutableStateOf(false) }
    var showSnackbar by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refreshMemoryUsage()
        viewModel.refreshLoadedModels()
    }

    LaunchedEffect(verificationResult) {
        verificationResult?.let { result ->
            when (result) {
                is LocalModelViewModel.VerificationResult.Pass -> {
                    snackbarMessage = "哈希校验通过"
                    showSnackbar = true
                }
                is LocalModelViewModel.VerificationResult.Fail -> {
                    snackbarMessage = "模型已损坏"
                    showSnackbar = true
                }
                is LocalModelViewModel.VerificationResult.Damaged -> {
                    snackbarMessage = "模型已损坏：${result.reason}"
                    showSnackbar = true
                }
                is LocalModelViewModel.VerificationResult.NotFound -> {
                    snackbarMessage = "模型不存在"
                    showSnackbar = true
                }
                else -> {}
            }
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarMessage = it
            showSnackbar = true
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(showSnackbar) {
        if (showSnackbar) {
            snackbarHostState.showSnackbar(snackbarMessage)
            showSnackbar = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("本地 GGUF 模型") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (models.isEmpty()) {
            EmptyState(
                onDownloadClick = { showDownloadDialog = true },
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    MemoryInfoCard(
                        memoryUsageMb = memoryUsage,
                        autoUnloadEnabled = autoUnloadEnabled,
                        onAutoUnloadToggle = { viewModel.toggleAutoUnload(it) },
                        onRefresh = { viewModel.refreshMemoryUsage() }
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showDownloadDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.ArrowDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("下载模型")
                        }
                        FilledTonalButton(
                            onClick = { viewModel.clearCache() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("缓存清理")
                        }
                    }
                }

                item {
                    AnimatedVisibility(
                        visible = downloading,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Card {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.size(12.dp))
                                    Text(
                                        "正在下载模型...",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { if (downloadProgress >= 0) downloadProgress / 100f else 0f },
                                    modifier = Modifier.fillMaxWidth(),
                                    isIndeterminate = downloadProgress < 0
                                )
                                if (downloadProgress >= 0) {
                                    Text(
                                        "${downloadProgress}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                items(models, key = { it.id }) { model ->
                    val modelId = model.filePath.hashCode().toLong()
                    val isLocked = lockedModels.contains(modelId)
                    ModelItem(
                        model = model,
                        isLocked = isLocked,
                        verificationResult = verificationResult,
                        onLoad = { viewModel.load(model.id) },
                        onUnload = { viewModel.unload(model.id) },
                        onDelete = { viewModel.delete(model.id) },
                        onVerify = { viewModel.verifyIntegrity(model.id) },
                        onLock = { viewModel.lockModel(model.id) },
                        onUnlock = { viewModel.unlockModel(model.id) }
                    )
                }
            }
        }
    }

    if (showDownloadDialog) {
        DownloadModelDialog(
            onDismiss = { showDownloadDialog = false },
            onConfirm = { url, name, saveDir ->
                viewModel.downloadModel(url, name, saveDir)
                showDownloadDialog = false
            }
        )
    }
}

@Composable
private fun MemoryInfoCard(
    memoryUsageMb: Long,
    autoUnloadEnabled: Boolean,
    onAutoUnloadToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "内存使用",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${memoryUsageMb} MB",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (memoryUsageMb > 2048) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onRefresh) {
                    Text("刷新", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "自动卸载",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = autoUnloadEnabled,
                    onCheckedChange = onAutoUnloadToggle
                )
            }
            if (autoUnloadEnabled) {
                Text(
                    "模型闲置 30 秒后自动卸载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun ModelItem(
    model: LocalModelEntity,
    isLocked: Boolean,
    verificationResult: LocalModelViewModel.VerificationResult?,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onVerify: () -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit
) {
    val isVerifying = verificationResult is LocalModelViewModel.VerificationResult.Verifying
    val isCurrentVerifying = isVerifying && (verificationResult as? LocalModelViewModel.VerificationResult.Verifying)?.id == model.id
    val statusOk = model.status == LocalModelEntity.STATUS_OK
    val hashDisplay = model.sha256?.take(16)?.plus("...") ?: "未知"

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (model.loaded && isLocked) {
                            Spacer(Modifier.size(6.dp))
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "已锁定",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            formatSize(model.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "量化：${model.quant}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            "上下文：${model.contextLength}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "哈希：$hashDisplay",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (statusOk) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "OK",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "已损坏",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        model.filePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                    if (model.loaded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已加载到内存",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (isLocked) {
                                Text(
                                    " · 已锁定",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onVerify,
                    enabled = !isCurrentVerifying,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isCurrentVerifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(6.dp))
                    } else {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Text("校验")
                }
                if (model.loaded) {
                    if (isLocked) {
                        IconButton(onClick = onUnlock) {
                            Icon(
                                Icons.Default.LockOpen,
                                contentDescription = "解锁",
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    } else {
                        IconButton(onClick = onLock) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "锁定",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Button(
                        onClick = onUnload,
                        modifier = Modifier.weight(1f)
                    ) { Text("卸载") }
                } else {
                    Button(
                        onClick = onLoad,
                        modifier = Modifier.weight(1f)
                    ) { Text("加载") }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.ArrowDownload,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "暂无本地模型",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "点击下方按钮下载 GGUF 模型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDownloadClick
            ) {
                Icon(
                    Icons.Default.ArrowDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("下载模型")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String, saveDir: String) -> Unit
) {
    var modelUrl by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var saveDir by remember { mutableStateOf(LocalModelViewModel.SAVE_PRIVATE) }
    var expanded by remember { mutableStateOf(false) }

    val saveDirOptions = listOf(
        LocalModelViewModel.SAVE_PRIVATE to "私有目录",
        LocalModelViewModel.SAVE_PUBLIC to "公共目录",
        LocalModelViewModel.SAVE_SD_CARD to "SD 卡"
    )

    val selectedLabel = saveDirOptions.firstOrNull { it.first == saveDir }?.second ?: saveDir

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = modelUrl,
                    onValueChange = { modelUrl = it },
                    label = { Text("模型 URL") },
                    placeholder = { Text("https://.../model.gguf") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("模型名称") },
                    placeholder = { Text("例如：qwen2.5-7b") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("保存位置") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        saveDirOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            when (value) {
                                                LocalModelViewModel.SAVE_PRIVATE -> Icons.Default.Home
                                                LocalModelViewModel.SAVE_PUBLIC -> Icons.Default.ArrowDownload
                                                else -> Icons.Default.SdStorage
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Text(label)
                                    }
                                },
                                onClick = {
                                    saveDir = value
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (modelUrl.isNotBlank() && modelName.isNotBlank()) {
                        onConfirm(modelUrl.trim(), modelName.trim(), saveDir)
                    }
                },
                enabled = modelUrl.isNotBlank() && modelName.isNotBlank()
            ) { Text("下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}