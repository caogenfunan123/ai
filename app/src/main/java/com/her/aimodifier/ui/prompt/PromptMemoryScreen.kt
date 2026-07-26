package com.her.aimodifier.ui.prompt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.her.aimodifier.di.ServiceLocator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptMemoryScreen(
    onBack: () -> Unit
) {
    val promptMemory = ServiceLocator.promptMemory
    val currentPrompt by promptMemory.observePrompt().collectAsStateWithLifecycle(initialValue = "")

    var editText by remember { mutableStateOf("") }
    var showEditDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        editText = promptMemory.getPrompt()
    }

    fun openEdit() {
        editText = currentPrompt
        showEditDialog = true
    }

    fun doSave() {
        if (saving) return
        saving = true
        coroutineScope.launch {
            try {
                promptMemory.setPrompt(editText)
                snackbarHostState.showSnackbar("全局提示词已保存")
                showEditDialog = false
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("保存失败：${e.message}")
            } finally {
                saving = false
            }
        }
    }

    fun doReset() {
        coroutineScope.launch {
            try {
                promptMemory.resetToDefault()
                editText = ""
                snackbarHostState.showSnackbar("已重置为默认提示词")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("重置失败：${e.message}")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("AI 系统提示词") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "当前全局提示词",
                style = MaterialTheme.typography.titleMedium
            )

            if (currentPrompt.isEmpty()) {
                Text(
                    "尚未设置全局提示词。设置后，将在每次 AI 对话的 system 角色中注入，用于持久化系统人设、工作习惯和用户偏好等长期记忆。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = currentPrompt,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("全局 Prompt (只读)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { openEdit() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
                OutlinedButton(
                    onClick = { showPreview = true },
                    modifier = Modifier.weight(1f),
                    enabled = currentPrompt.isNotEmpty()
                ) {
                    Text("预览注入")
                }
                OutlinedButton(
                    onClick = { doReset() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("重置默认")
                }
            }

            if (currentPrompt.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "注入方式说明",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "全局提示词会在每次对话请求的 messages 头部以 system 角色注入：",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("编辑全局提示词") },
            text = {
                Column {
                    Text(
                        "此提示词将作为 system 角色消息注入每轮对话的开头，用于设置 AI 的长期行为规则、人设和偏好。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        label = { Text("全局 Prompt") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { doSave() },
                    enabled = !saving
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (saving) "保存中..." else "保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showPreview && currentPrompt.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("预览：Prompt 注入效果") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "以下是当前提示词被注入到对话 API 请求中的方式：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = buildString {
                            appendLine("┌─ messages[0] ──────────────────────")
                            appendLine("│ role: \"system\"")
                            appendLine("│ name: \"system_prompt_global\"")
                            appendLine("│ content: \"\"\"")
                            appendLine(currentPrompt)
                            appendLine("│ \"\"\"")
                            appendLine("└──────────────────────────────────────")
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("注入预览") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPreview = false }) {
                    Text("关闭")
                }
            }
        )
    }
}