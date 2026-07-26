package com.her.aimodifier.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.data.repository.AiConfigRepository
import com.her.aimodifier.data.pref.EncryptedPrefs
import com.her.aimodifier.di.ServiceLocator
import com.her.aimodifier.utils.JsonUtil
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class FullConfigExport(
    val version: String = "1.0",
    val exportedAt: Long = System.currentTimeMillis(),
    val aiConfig: AiConfigExport? = null,
    val mirrorConfig: MirrorConfigExport? = null,
    val toolchainConfig: ToolchainConfigExport? = null,
    val globalPrompt: String = ""
)

@Serializable
data class AiConfigExport(
    val scope: String = "global",
    val baseUrl: String = "",
    val apiKey: String = "",
    val defaultModel: String = "",
    val manualModelMode: Boolean = false,
    val manualModels: String = "",
    val timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
    val contextLength: Int = AppConstants.DEFAULT_CONTEXT_LENGTH,
    val temperature: Float = AppConstants.DEFAULT_TEMPERATURE
)

@Serializable
data class MirrorConfigExport(
    val mirrorBaseUrl: String = ""
)

@Serializable
data class ToolchainConfigExport(
    val selectedVersions: Map<String, String> = emptyMap()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigExportScreen(
    onBack: () -> Unit = {},
    aiConfigRepository: AiConfigRepository = ServiceLocator.aiConfigRepository,
    prefs: EncryptedPrefs = ServiceLocator.encryptedPrefs
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showExportPreview by remember { mutableStateOf(false) }
    var showImportPreview by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf("") }
    var importJson by remember { mutableStateOf("") }
    var importPreview by remember { mutableStateOf<FullConfigExport?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val globalConfig = aiConfigRepository.findGlobal()
        val export = FullConfigExport(
            aiConfig = globalConfig?.let { cfg ->
                AiConfigExport(
                    scope = "global",
                    baseUrl = cfg.baseUrl,
                    apiKey = cfg.apiKey,
                    defaultModel = cfg.defaultModel,
                    manualModelMode = cfg.manualModelMode,
                    manualModels = cfg.manualModels,
                    timeoutMs = cfg.timeoutMs,
                    contextLength = cfg.contextLength,
                    temperature = cfg.temperature
                )
            },
            mirrorConfig = MirrorConfigExport(
                mirrorBaseUrl = prefs.mirrorBaseUrl
            ),
            toolchainConfig = ToolchainConfigExport(
                selectedVersions = buildToolchainVersionMap(prefs)
            ),
            globalPrompt = prefs.globalSystemPrompt
        )
        exportJson = JsonUtil.prettyJson.encodeToString(export)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(exportJson.toByteArray(Charsets.UTF_8))
                }
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("配置导出成功")
                }
            } catch (e: Exception) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("导出失败：${e.message}")
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val jsonText = input.readBytes().toString(Charsets.UTF_8)
                importJson = jsonText
                val preview = JsonUtil.json.decodeFromString(FullConfigExport.serializer(), jsonText)
                importPreview = preview
                showImportPreview = true
            }
        } catch (e: Exception) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("导入失败：${e.message}")
            }
        }
    }

    fun doImport() {
        val preview = importPreview ?: return
        coroutineScope.launch {
            isProcessing = true
            try {
                preview.aiConfig?.let { ai ->
                    aiConfigRepository.upsertGlobal(
                        baseUrl = ai.baseUrl,
                        apiKey = ai.apiKey,
                        defaultModel = ai.defaultModel,
                        timeoutMs = ai.timeoutMs,
                        contextLength = ai.contextLength,
                        temperature = ai.temperature
                    )
                    if (ai.manualModelMode) {
                        aiConfigRepository.updateManualModels(
                            aiConfigRepository.findGlobal()?.id ?: -1,
                            ai.manualModels,
                            true
                        )
                    }
                }
                preview.mirrorConfig?.let { mirror ->
                    prefs.mirrorBaseUrl = mirror.mirrorBaseUrl
                }
                preview.toolchainConfig?.let { tc ->
                    tc.selectedVersions.forEach { (tool, version) ->
                        prefs.setSelectedToolVersion(tool, version)
                    }
                }
                prefs.globalSystemPrompt = preview.globalPrompt

                snackbarHostState.showSnackbar("配置导入成功")
                showImportPreview = false
                importPreview = null
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("导入失败：${e.message}")
            } finally {
                isProcessing = false
            }
        }
    }

    fun doResetAll() {
        coroutineScope.launch {
            isProcessing = true
            try {
                aiConfigRepository.findGlobal()?.let { cfg ->
                    aiConfigRepository.delete(cfg.id)
                }
                prefs.mirrorBaseUrl = ""
                prefs.globalSystemPrompt = ""

                val versionKeys = listOf(
                    "toolchain_check_env", "toolchain_prepare_task",
                    "toolchain_run_command", "toolchain_snapshot",
                    "toolchain_clean", "aapt2", "apktool", "jadx",
                    "dex2jar", "frida-tools", "mitmproxy"
                )
                versionKeys.forEach { key ->
                    prefs.setSelectedToolVersion(key, "")
                }

                snackbarHostState.showSnackbar("已重置所有自定义配置")
                exportJson = ""
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("重置失败：${e.message}")
            } finally {
                isProcessing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置导入/导出") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "配置管理",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "导出或导入全部 AI、镜像源和工具链配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "导出配置",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "将当前所有配置导出为 JSON 文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showExportPreview = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("预览并导出")
                            }
                            OutlinedButton(
                                onClick = {
                                    exportLauncher.launch("aimodifier_config_${System.currentTimeMillis()}.json")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("直接导出文件")
                            }
                        }
                    }
                }
            }

            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "导入配置",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "从 JSON 文件恢复全部配置（将覆盖现有设置）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("选择 JSON 文件导入")
                        }
                    }
                }
            }

            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "恢复默认",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "清除所有自定义配置（AI 中转站、镜像源、工具链版本等）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showResetDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "重置所有自定义配置",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showExportPreview) {
        AlertDialog(
            onDismissRequest = { showExportPreview = false },
            title = { Text("导出预览") },
            text = {
                Column {
                    Text(
                        "以下为即将导出的配置 JSON，可复制或保存为文件：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportJson,
                        onValueChange = { exportJson = it },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("AI 配置导出", exportJson)
                        )
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已复制到剪贴板")
                        }
                        showExportPreview = false
                    }
                ) {
                    Text("复制")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            exportLauncher.launch("aimodifier_config_${System.currentTimeMillis()}.json")
                        }
                    ) {
                        Text("保存文件")
                    }
                    TextButton(onClick = { showExportPreview = false }) {
                        Text("关闭")
                    }
                }
            }
        )
    }

    if (showImportPreview && importPreview != null) {
        AlertDialog(
            onDismissRequest = { showImportPreview = false },
            title = { Text("导入预览") },
            text = {
                LazyColumn(
                    modifier = Modifier.height(320.dp)
                ) {
                    item {
                        Text(
                            "即将导入以下配置，确认后将覆盖现有设置：",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    importPreview!!.aiConfig?.let { ai ->
                        item {
                            AssistChip(
                                onClick = {},
                                label = { Text("AI 配置: ${ai.baseUrl.ifBlank { "(空)" }}") }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    importPreview!!.mirrorConfig?.let { mirror ->
                        item {
                            AssistChip(
                                onClick = {},
                                label = { Text("镜像源: ${mirror.mirrorBaseUrl.ifBlank { "(默认)" }}") }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    importPreview!!.toolchainConfig?.let { tc ->
                        item {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "工具链版本: ${tc.selectedVersions.entries.joinToString { "${it.key}=${it.value}" }}"
                                    )
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    if (importPreview!!.globalPrompt.isNotBlank()) {
                        item {
                            AssistChip(
                                onClick = {},
                                label = { Text("全局 Prompt: ${importPreview!!.globalPrompt.take(50)}…") }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    item {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importJson,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { doImport() },
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isProcessing) "导入中..." else "确认导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportPreview = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("确认重置") },
            text = {
                Text(
                    "此操作将清除所有自定义配置（AI 中转站、镜像源、工具链版本、全局 Prompt），且不可恢复。确定要继续吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        doResetAll()
                        showResetDialog = false
                    },
                    enabled = !isProcessing
                ) {
                    Text(
                        "重置",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun buildToolchainVersionMap(prefs: com.her.aimodifier.data.pref.EncryptedPrefs): Map<String, String> {
    val versionKeys = listOf(
        "toolchain_check_env", "toolchain_prepare_task",
        "toolchain_run_command", "toolchain_snapshot",
        "toolchain_clean", "aapt2", "apktool", "jadx",
        "dex2jar", "frida-tools", "mitmproxy"
    )
    return versionKeys.mapNotNull { key ->
        prefs.getSelectedToolVersion(key)?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap()
}