package com.her.aimodifier.ui.mcp_panel

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.her.aimodifier.mcp.core.McpCallRecord
import com.her.aimodifier.mcp.core.McpParam
import com.her.aimodifier.mcp.core.McpTool
import com.her.aimodifier.viewModel.McpPanelViewModel
import com.her.aimodifier.viewModel.TestCallResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpPanelScreen(
    onBack: () -> Unit = {},
    viewModel: McpPanelViewModel = viewModel()
) {
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val callHistory by viewModel.callHistory.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isCalling by viewModel.isCalling.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) }
    var showTestDialog by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }
    var currentTestTool by remember { mutableStateOf<McpTool?>(null) }
    var currentTestFullName by remember { mutableStateOf("") }

    LaunchedEffect(testResult) {
        if (testResult != null) showResultDialog = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 插件") },
                actions = {
                    if (selectedTab == 1 && callHistory.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空历史")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("插件工具") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.refreshHistory()
                    },
                    text = { Text("调用日志 (${callHistory.size})") }
                )
            }

            when (selectedTab) {
                0 -> PluginToolsTab(
                    plugins = plugins,
                    onTestTool = { tool, fullName ->
                        currentTestTool = tool
                        currentTestFullName = fullName
                        showTestDialog = true
                    }
                )
                1 -> CallLogTab(
                    history = callHistory,
                    onClear = { viewModel.clearHistory() }
                )
            }
        }
    }

    if (showTestDialog && currentTestTool != null) {
        TestCallDialog(
            tool = currentTestTool!!,
            fullToolName = currentTestFullName,
            isCalling = isCalling,
            onDismiss = {
                showTestDialog = false
                viewModel.clearTestResult()
            },
            onConfirm = { args ->
                viewModel.testCall(currentTestFullName, args)
            }
        )
    }

    if (showResultDialog && testResult != null) {
        TestResultDialog(
            result = testResult!!,
            toolName = currentTestFullName,
            onDismiss = {
                showResultDialog = false
                viewModel.clearTestResult()
            }
        )
    }
}

@Composable
private fun PluginToolsTab(
    plugins: List<McpPanelViewModel.PluginView>,
    onTestTool: (McpTool, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(plugins, key = { it.pluginId }) { plugin ->
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(plugin.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        plugin.pluginId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "暴露工具 (${plugin.tools.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    plugin.tools.forEach { tool ->
                        ToolItem(
                            pluginId = plugin.pluginId,
                            tool = tool,
                            onTest = { onTestTool(tool, "${plugin.pluginId}.${tool.name}") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolItem(
    pluginId: String,
    tool: McpTool,
    onTest: () -> Unit
) {
    val fullName = "$pluginId.${tool.name}"
    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (tool.description.isNotBlank()) {
                        Text(
                            tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = if (showDetails) Int.MAX_VALUE else 2
                        )
                    }
                }
                IconButton(onClick = onTest) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "测试调用",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (tool.inputSchema.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDetails = !showDetails }
                ) {
                    Text(
                        "参数 (${tool.inputSchema.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        if (showDetails) " ▲" else " ▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                if (showDetails) {
                    Spacer(Modifier.height(4.dp))
                    tool.inputSchema.forEach { (paramName, param) ->
                        ParamItem(paramName, param)
                    }
                } else {
                    Text(
                        tool.inputSchema.keys.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun ParamItem(name: String, param: McpParam) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "• ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    param.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (param.required) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "*",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (param.description.isNotBlank()) {
                Text(
                    param.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (param.default != null) {
                Text(
                    "默认: ${param.default}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (!param.enum.isNullOrEmpty()) {
                Text(
                    "可选值: ${param.enum.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun CallLogTab(
    history: List<McpCallRecord>,
    onClear: () -> Unit
) {
    if (history.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "暂无调用记录",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "点击工具旁的 ▶ 按钮开始测试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "最近 ${history.size} 条调用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                TextButton(onClick = onClear) {
                    Text("清空全部")
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        items(history, key = { it.timestamp }) { record ->
            CallLogItem(record)
        }
    }
}

@Composable
private fun CallLogItem(record: McpCallRecord) {
    val statusColor = if (record.success) Color(0xFF4CAF50) else Color(0xFFE53935)
    val statusIcon = if (record.success) Icons.Default.CheckCircle else Icons.Default.Error

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    record.toolName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${record.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "参数: ${formatArgs(record.arguments)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3
            )

            Spacer(Modifier.height(2.dp))

            Text(
                "结果: ${record.result.take(200)}${if (record.result.length > 200) "…" else ""}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (record.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                maxLines = 3
            )

            Spacer(Modifier.height(2.dp))

            Text(
                formatTime(record.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestCallDialog(
    tool: McpTool,
    fullToolName: String,
    isCalling: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any?>) -> Unit
) {
    val argValues = remember(tool) {
        tool.inputSchema.keys.associateWith { mutableStateOf("") }
    }

    AlertDialog(
        onDismissRequest = { if (!isCalling) onDismiss() },
        title = {
            Column {
                Text("测试工具调用")
                Text(
                    fullToolName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.height(280.dp)
            ) {
                item {
                    Text(
                        tool.description.ifBlank { "（无描述）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (tool.inputSchema.isEmpty()) {
                    item {
                        AssistChip(
                            onClick = {},
                            label = { Text("此工具无需参数") }
                        )
                    }
                } else {
                    tool.inputSchema.forEach { (paramName, param) ->
                        item {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        paramName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        param.type,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    if (param.required) {
                                        Text(
                                            " *",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                if (param.description.isNotBlank()) {
                                    Text(
                                        param.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Spacer(Modifier.height(4.dp))

                                if (param.enum.isNullOrEmpty()) {
                                    OutlinedTextField(
                                        value = argValues[paramName]?.value ?: "",
                                        onValueChange = { argValues[paramName]?.value = it },
                                        label = {
                                            Text(
                                                if (param.default != null) "默认: ${param.default}" else "输入 $paramName"
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isCalling,
                                        singleLine = true
                                    )
                                } else {
                                    var expanded by remember { mutableStateOf(false) }
                                    var selectedValue by remember {
                                        mutableStateOf(param.default ?: param.enum.firstOrNull() ?: "")
                                    }
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = !expanded }
                                    ) {
                                        OutlinedTextField(
                                            value = selectedValue,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("选择 $paramName") },
                                            trailingIcon = {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                            },
                                            modifier = Modifier.fillMaxWidth().menuAnchor()
                                        )
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            param.enum.forEach { value ->
                                                DropdownMenuItem(
                                                    text = { Text(value) },
                                                    onClick = {
                                                        selectedValue = value
                                                        argValues[paramName]?.value = value
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val args = tool.inputSchema.keys.associateWith { key ->
                        val raw = argValues[key]?.value.orEmpty()
                        val param = tool.inputSchema[key]
                        castArgValue(raw, param)
                    }
                    onConfirm(args)
                },
                enabled = !isCalling
            ) {
                if (isCalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isCalling) "调用中..." else "执行")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCalling
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun TestResultDialog(
    result: TestCallResult,
    toolName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text(if (result.success) "调用成功" else "调用失败")
            }
        },
        text = {
            Column {
                Text(
                    toolName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = when {
                            result.output != null -> result.output
                            result.error != null -> result.error
                            else -> "(无输出)"
                        },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun formatArgs(args: Map<String, Any?>): String {
    if (args.isEmpty()) return "{}"
    return args.entries.joinToString(", ", "{", "}") { (k, v) ->
        "$k=${v?.toString() ?: "null"}"
    }
}

private fun formatTime(timestamp: Long): String {
    val javaTime = java.time.Instant.ofEpochMilli(timestamp)
    val localTime = javaTime.atZone(java.time.ZoneId.systemDefault())
    return localTime.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))
}

private fun castArgValue(raw: String, param: McpParam?): Any? {
    if (raw.isBlank()) return null
    return when (param?.type) {
        "number", "integer" -> raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
        "boolean" -> raw.toBooleanStrictOrNull() ?: raw
        "array" -> raw.split(",").map { it.trim() }
        "object" -> raw
        else -> raw
    }
}