package com.her.aimodifier.ui.ai_setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.viewModel.AiSettingViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ExportConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val defaultModel: String = "",
    val timeoutMs: Long = AppConstants.AI_CLOUD_TIMEOUT_MS,
    val contextLength: Int = AppConstants.DEFAULT_CONTEXT_LENGTH,
    val temperature: Float = AppConstants.DEFAULT_TEMPERATURE
)

private val URL_REGEX = Regex("""^https?://\S+""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingScreen(
    workspaceId: String?,
    onBack: () -> Unit = {},
    viewModel: AiSettingViewModel = viewModel()
) {
    LaunchedEffect(workspaceId) { viewModel.load(workspaceId) }

    val config by viewModel.config.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val fetching by viewModel.fetching.collectAsStateWithLifecycle()
    val manualMode by viewModel.manualMode.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saveSuccess by viewModel.saveSuccess.collectAsStateWithLifecycle()

    var baseUrl by remember(config) { mutableStateOf(config?.baseUrl.orEmpty()) }
    var apiKey by remember(config) { mutableStateOf(config?.apiKey.orEmpty()) }
    var defaultModel by remember(config) { mutableStateOf(config?.defaultModel.orEmpty()) }
    var manualModels by remember(config) { mutableStateOf(config?.manualModels.orEmpty()) }
    var timeoutMs by remember(config) {
        mutableStateOf((config?.timeoutMs ?: AppConstants.AI_CLOUD_TIMEOUT_MS).toString())
    }
    var contextLength by remember(config) {
        mutableStateOf((config?.contextLength ?: AppConstants.DEFAULT_CONTEXT_LENGTH).toString())
    }
    var temperature by remember(config) {
        mutableStateOf(config?.temperature ?: AppConstants.DEFAULT_TEMPERATURE)
    }

    var scope by remember(workspaceId) {
        mutableStateOf(
            if (workspaceId == null) AppConstants.AiConfigScope.GLOBAL
            else AppConstants.AiConfigScope.WORKSPACE
        )
    }

    var baseUrlError by remember { mutableStateOf(false) }
    var apiKeyError by remember { mutableStateOf(false) }

    var showModelDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(defaultModel) }
    var exportJsonText by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            snackbarHostState.showSnackbar("配置保存成功")
            viewModel.clearSuccess()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    val jsonText = input.readBytes().toString(Charsets.UTF_8)
                    val cfg = Json.decodeFromString<ExportConfig>(jsonText)
                    baseUrl = cfg.baseUrl
                    apiKey = cfg.apiKey
                    defaultModel = cfg.defaultModel
                    timeoutMs = cfg.timeoutMs.toString()
                    contextLength = cfg.contextLength.toString()
                    temperature = cfg.temperature
                    baseUrlError = false
                    apiKeyError = false
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("配置导入成功")
                    }
                }
            } catch (e: Exception) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("导入失败：${e.message}")
                }
            }
        }
    }

    fun validate(): Boolean {
        var valid = true
        baseUrlError = false
        apiKeyError = false

        if (baseUrl.isNotBlank() && !URL_REGEX.matches(baseUrl.trim())) {
            baseUrlError = true
            valid = false
        }
        if (baseUrl.isNotBlank() && apiKey.isBlank()) {
            apiKeyError = true
            valid = false
        }
        return valid
    }

    fun resetDefaults() {
        baseUrl = ""
        apiKey = ""
        defaultModel = ""
        manualModels = ""
        timeoutMs = AppConstants.AI_CLOUD_TIMEOUT_MS.toString()
        contextLength = AppConstants.DEFAULT_CONTEXT_LENGTH.toString()
        temperature = AppConstants.DEFAULT_TEMPERATURE
        baseUrlError = false
        apiKeyError = false
    }

    fun doSave() {
        if (!validate()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("请修正表单中的错误")
            }
            return
        }
        val timeout = timeoutMs.toLongOrNull() ?: AppConstants.AI_CLOUD_TIMEOUT_MS
        val contextLen = contextLength.toIntOrNull() ?: AppConstants.DEFAULT_CONTEXT_LENGTH
        viewModel.save(
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            defaultModel = defaultModel.trim(),
            scope = scope,
            workspaceId = workspaceId,
            timeoutMs = timeout,
            contextLength = contextLen,
            temperature = temperature
        )
    }

    fun buildExportJson(): String {
        return Json.encodeToString(
            ExportConfig(
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                defaultModel = defaultModel.trim(),
                timeoutMs = timeoutMs.toLongOrNull() ?: AppConstants.AI_CLOUD_TIMEOUT_MS,
                contextLength = contextLength.toIntOrNull() ?: AppConstants.DEFAULT_CONTEXT_LENGTH,
                temperature = temperature
            )
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("AI 中转站配置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }

            // 作用域选择
            item {
                Text("作用域", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scope == AppConstants.AiConfigScope.GLOBAL,
                        onClick = { scope = AppConstants.AiConfigScope.GLOBAL },
                        label = { Text("全局") }
                    )
                    if (workspaceId != null) {
                        FilterChip(
                            selected = scope == AppConstants.AiConfigScope.WORKSPACE,
                            onClick = { scope = AppConstants.AiConfigScope.WORKSPACE },
                            label = { Text("当前工作区") }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // BaseUrl
            item {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        if (it.isNotBlank()) baseUrlError = !URL_REGEX.matches(it.trim())
                        else baseUrlError = false
                    },
                    label = { Text("中转站 URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = baseUrlError,
                    supportingText = if (baseUrlError) {
                        { Text("URL 格式不正确，需以 http:// 或 https:// 开头") }
                    } else null
                )
            }

            // API Key
            item {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        if (baseUrl.isNotBlank()) apiKeyError = it.isBlank()
                        else apiKeyError = false
                    },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = apiKeyError,
                    supportingText = if (apiKeyError) {
                        { Text("API Key 不能为空") }
                    } else null
                )
            }

            // 默认模型
            item {
                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = { defaultModel = it },
                    label = { Text("默认模型名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // 获取模型 + 选择
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.fetchModels() },
                        enabled = !fetching && baseUrl.isNotEmpty() && apiKey.isNotEmpty()
                    ) {
                        Text("获取模型列表")
                    }
                    if (fetching) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .height(20.dp)
                                .padding(start = 4.dp)
                        )
                    }
                }
            }

            if (models.isNotEmpty() && !manualMode) {
                item {
                    AssistChip(
                        onClick = { showModelDialog = true },
                        label = { Text("已加载 ${models.size} 个模型，点击选择默认模型") }
                    )
                }
            }

            // 手动模式开关
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = manualMode,
                        onCheckedChange = { viewModel.setManualMode(it) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "手动输入模型（远端拉取失败时启用）",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (manualMode) {
                item {
                    OutlinedTextField(
                        value = manualModels,
                        onValueChange = { manualModels = it },
                        label = { Text("手动模型列表（逗号分隔）") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
                item {
                    Button(
                        onClick = {
                            viewModel.saveManualModels(
                                manualModels.split(",").map { it.trim() }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存手动模型")
                    }
                }
            }

            // timeoutMs
            item {
                Text(
                    "请求超时 (ms)",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = timeoutMs,
                    onValueChange = { timeoutMs = it.filter { c -> c.isDigit() } },
                    label = { Text("超时毫秒数，默认 30000") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // contextLength
            item {
                Text(
                    "上下文长度",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = contextLength,
                    onValueChange = { contextLength = it.filter { c -> c.isDigit() } },
                    label = { Text("Token 长度，默认 8192") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // temperature
            item {
                Text(
                    "Temperature: ${String.format("%.1f", temperature)}",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = (it * 10).toInt() / 10f },
                    valueRange = 0.0f..2.0f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.0 (确定性)", style = MaterialTheme.typography.bodySmall)
                    Text("2.0 (创意)", style = MaterialTheme.typography.bodySmall)
                }
            }

            // 错误显示
            error?.let {
                item {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item { Spacer(Modifier.height(4.dp)) }

            // 其他操作
            item {
                Text("其他操作", style = MaterialTheme.typography.labelMedium)
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { resetDefaults() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("恢复默认")
                    }
                    OutlinedButton(
                        onClick = {
                            exportJsonText = buildExportJson()
                            showExportDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导出配置")
                    }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf("application/json", "*/*")
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导入配置")
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            // 保存按钮
            item {
                Button(
                    onClick = { doSave() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (saving) "保存中..." else "保存配置")
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // 模型选择对话框
    if (showModelDialog && models.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("选择默认模型") },
            text = {
                LazyColumn(
                    modifier = Modifier.height(320.dp)
                ) {
                    items(models, key = { it.id }) { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedModel = m.id }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedModel == m.id,
                                onClick = { selectedModel = m.id }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    m.id,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                m.owned_by?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        defaultModel = selectedModel
                        showModelDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 导出配置对话框
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出配置") },
            text = {
                Column {
                    Text(
                        "以下为当前配置的 JSON 数据，可复制保存或分享：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportJsonText,
                        onValueChange = { exportJsonText = it },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("AI 配置", exportJsonText)
                        )
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已复制到剪贴板")
                        }
                        showExportDialog = false
                    }
                ) {
                    Text("复制全部")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}