package com.her.aimodifier.ui.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.her.aimodifier.viewModel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPane(
    modifier: Modifier = Modifier,
    sessionId: Long = -1L,
    workspaceId: String? = null,
    onOpenLocalModels: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    viewModel: ChatViewModel = viewModel()
) {
    LaunchedEffect(sessionId, workspaceId) {
        if (sessionId > 0) viewModel.bindSession(sessionId, workspaceId)
    }

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val canPause by viewModel.canPause.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val currentModel by viewModel.currentModel.collectAsStateWithLifecycle()
    val isToolCallRunning by viewModel.isToolCallRunning.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var input by remember { mutableStateOf("") }
    var forceThinking by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val lastSelectedModelKey = remember { prefs.getString(KEY_LAST_MODEL, null) }
    LaunchedEffect(availableModels, lastSelectedModelKey) {
        if (lastSelectedModelKey != null && availableModels.isNotEmpty()) {
            val lastModel = availableModels.firstOrNull {
                modelKey(it) == lastSelectedModelKey
            }
            if (lastModel != null && lastModel != currentModel) {
                viewModel.selectModel(lastModel)
            }
        }
    }

    val pinnedModels = remember {
        mutableStateOf(
            prefs.getStringSet(KEY_PINNED_MODELS, emptySet()) ?: emptySet()
        )
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AI 对话",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )

            ModelSelector(
                availableModels = availableModels,
                currentModel = currentModel,
                pinnedModels = pinnedModels.value,
                expanded = modelDropdownExpanded,
                onExpandedChange = { modelDropdownExpanded = it },
                onSelectModel = { model ->
                    viewModel.selectModel(model)
                    modelDropdownExpanded = false
                    prefs.edit().putString(KEY_LAST_MODEL, modelKey(model)).apply()
                },
                onTogglePin = { model ->
                    val key = modelKey(model)
                    val current = pinnedModels.value.toMutableSet()
                    if (current.contains(key)) current.remove(key) else current.add(key)
                    pinnedModels.value = current
                    prefs.edit().putStringSet(KEY_PINNED_MODELS, current).apply()
                },
                onOpenLocalModels = onOpenLocalModels,
                onOpenAiSettings = onOpenAiSettings
            )

            if (isStreaming) {
                Spacer(Modifier.width(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp).padding(end = 4.dp),
                    strokeWidth = 2.dp
                )
                if (canPause) {
                    IconButton(onClick = { viewModel.pause() }) {
                        Icon(Icons.Default.Pause, contentDescription = "暂停")
                    }
                }
                IconButton(onClick = { viewModel.cancel() }) {
                    Icon(Icons.Default.Stop, contentDescription = "取消")
                }
            }

            if (isToolCallRunning) {
                Spacer(Modifier.width(4.dp))
                Text(
                    "运行中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                IconButton(onClick = { viewModel.cancelToolCall() }) {
                    Icon(Icons.Default.Stop, contentDescription = "取消工具调用")
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.hashCode() }) { msg ->
                MessageBubble(msg)
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { forceThinking = !forceThinking },
                enabled = !isStreaming
            ) {
                Text(if (forceThinking) "思考模式: 开" else "思考模式: 关")
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…") },
                enabled = !isStreaming,
                maxLines = 5
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    viewModel.send(input, forceThinking)
                    input = ""
                },
                enabled = !isStreaming && input.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun ModelSelector(
    availableModels: List<ChatViewModel.ModelInfo>,
    currentModel: ChatViewModel.ModelInfo?,
    pinnedModels: Set<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectModel: (ChatViewModel.ModelInfo) -> Unit,
    onTogglePin: (ChatViewModel.ModelInfo) -> Unit,
    onOpenLocalModels: () -> Unit,
    onOpenAiSettings: () -> Unit
) {
    Box {
        Row(
            modifier = Modifier
                .clickable(enabled = availableModels.isNotEmpty()) {
                    onExpandedChange(true)
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentModel?.let { model ->
                    if (model.isLocal) "本地: ${model.name}" else "云端: ${model.name}"
                } ?: "无模型",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (availableModels.isNotEmpty()) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "切换模型",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.height(20.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            val pinnedItems = availableModels.filter { pinnedModels.contains(modelKey(it)) }
            val unpinnedItems = availableModels.filter { !pinnedModels.contains(modelKey(it)) }

            if (pinnedItems.isNotEmpty()) {
                Text(
                    "已置顶",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                pinnedItems.forEach { model ->
                    ModelDropdownItem(
                        model = model,
                        isSelected = model == currentModel,
                        isPinned = true,
                        onSelect = { onSelectModel(model) },
                        onTogglePin = { onTogglePin(model) }
                    )
                }
            }

            if (pinnedItems.isNotEmpty() && unpinnedItems.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "──────────",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    onClick = {},
                    enabled = false
                )
            }

            unpinnedItems.forEach { model ->
                ModelDropdownItem(
                    model = model,
                    isSelected = model == currentModel,
                    isPinned = false,
                    onSelect = { onSelectModel(model) },
                    onTogglePin = { onTogglePin(model) }
                )
            }

            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.height(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "模型管理",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                onClick = {
                    onExpandedChange(false)
                    onOpenAiSettings()
                }
            )

            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.height(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "本地模型管理",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                onClick = {
                    onExpandedChange(false)
                    onOpenLocalModels()
                }
            )
        }
    }
}

@Composable
private fun ModelDropdownItem(
    model: ChatViewModel.ModelInfo,
    isSelected: Boolean,
    isPinned: Boolean,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit
) {
    val modelLabel = if (model.isLocal) "本地: ${model.name}" else "云端: ${model.name}"
    val isValid = model.isLocal || model.modelId != null || true

    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelLabel,
                        color = when {
                            !isValid -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            isSelected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "已置顶",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.height(16.dp)
                    )
                }
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.height(24.dp)
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = if (isPinned) "取消置顶" else "置顶",
                        tint = if (isPinned) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        modifier = Modifier.height(16.dp)
                    )
                }
            }
        },
        onClick = onSelect,
        enabled = isValid
    )
}

@Composable
private fun MessageBubble(msg: ChatViewModel.UiMessage) {
    val isUser = msg.role == "user"
    val isToolLog = msg.type == "tool_log"
    val isSystem = msg.type == "system"

    val bg: Color
    val fg: Color
    val alignment: Arrangement.Horizontal

    when {
        isToolLog -> {
            bg = Color(0xFF2B2B2B)
            fg = Color(0xFFB0BEC5)
            alignment = Arrangement.Start
        }
        isSystem -> {
            bg = MaterialTheme.colorScheme.surface
            fg = MaterialTheme.colorScheme.onSurfaceVariant
            alignment = Arrangement.Center
        }
        isUser -> {
            bg = MaterialTheme.colorScheme.primary
            fg = MaterialTheme.colorScheme.onPrimary
            alignment = Arrangement.End
        }
        else -> {
            bg = MaterialTheme.colorScheme.surfaceVariant
            fg = MaterialTheme.colorScheme.onSurfaceVariant
            alignment = Arrangement.Start
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Surface(
            color = bg,
            shape = if (isToolLog) RoundedCornerShape(4.dp) else MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (isToolLog) 8.dp else 12.dp,
                    vertical = if (isToolLog) 4.dp else 8.dp
                )
            ) {
                if (msg.thinking) {
                    Text(
                        "【思考中…】",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = fg
                    )
                }
                Text(
                    text = msg.content.ifEmpty { "…" },
                    color = fg,
                    fontFamily = if (isToolLog) FontFamily.Monospace else FontFamily.Default,
                    style = if (isToolLog) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    }
                )
                if (msg.streaming) {
                    Text("▌", color = fg)
                }
            }
        }
    }
}

private fun modelKey(model: ChatViewModel.ModelInfo): String {
    return if (model.isLocal) "local:${model.modelId ?: model.name}" else "cloud:${model.name}"
}

private const val PREFS_NAME = "chat_pane_prefs"
private const val KEY_LAST_MODEL = "last_selected_model"
private const val KEY_PINNED_MODELS = "pinned_models"