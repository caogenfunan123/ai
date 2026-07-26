package com.her.aimodifier.ui.toolbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.her.aimodifier.container.manager.ProotContainerManager
import com.her.aimodifier.viewModel.ToolboxViewModel

/**
 * 工具箱页（最终定稿）。
 *
 * 功能：容器控制、快照、缓存清理、工具更新、镜像源配置、容器状态监控
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolboxScreen(
    onBack: () -> Unit = {},
    viewModel: ToolboxViewModel = viewModel()
) {
    val env by viewModel.env.collectAsStateWithLifecycle()
    val deployed by viewModel.deployed.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val installedTools by viewModel.installedTools.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val mirrorUrl by viewModel.mirrorUrl.collectAsStateWithLifecycle()
    val mirrorTestResult by viewModel.mirrorTestResult.collectAsStateWithLifecycle()
    val mirrorTesting by viewModel.mirrorTesting.collectAsStateWithLifecycle()

    val containerStatus by viewModel.containerStatus.collectAsStateWithLifecycle()
    val healthState by viewModel.healthState.collectAsStateWithLifecycle()
    val deployProgress by viewModel.deployProgress.collectAsStateWithLifecycle()

    var showSnapshotDialog by remember { mutableStateOf(false) }
    var mirrorUrlInput by remember { mutableStateOf(mirrorUrl) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("工具箱") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 环境状态卡片
            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("环境检测", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        env?.let {
                            Text("架构: ${it.arch}")
                            Text("Root: ${it.hasRoot}")
                            Text("KernelSU: ${it.hasKernelSu}")
                            Text("使用 PRoot: ${it.useProot}")
                            Text("容器部署: $deployed")
                            Text("已安装工具: ${installedTools.size} 个")
                            if (installedTools.isNotEmpty()) {
                                Text(installedTools.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.refreshEnv() },
                            enabled = !busy
                        ) { Text("刷新") }
                    }
                }
            }

            // 容器控制
            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("容器控制", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        if (!deployed) {
                            Button(
                                onClick = { viewModel.deployContainer() },
                                enabled = !busy
                            ) { Text("部署 PRoot 容器") }
                        } else {
                            Text("容器已就绪", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // 容器状态
            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("容器状态", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        // 部署进度
                        if (healthState == ProotContainerManager.HealthState.DEPLOYING) {
                            Text("正在部署容器...", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { deployProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "部署进度: $deployProgress%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            Text(
                                "部署完成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        // 健康状态
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("健康状态", style = MaterialTheme.typography.bodyMedium)
                            HealthStateBadge(healthState)
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        // 自动重启开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("自动重启", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "容器异常退出时自动重新启动",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = containerStatus.autoRestart,
                                onCheckedChange = { viewModel.setAutoRestart(it) }
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // 空闲自动休眠开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("空闲自动休眠", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "30 秒无操作后自动停止容器",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = containerStatus.autoSleep,
                                onCheckedChange = { viewModel.setAutoSleep(it) }
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.restartContainer() },
                                enabled = !busy
                            ) { Text("重启容器") }
                            Button(
                                onClick = { viewModel.stopContainer() },
                                enabled = containerStatus.isRunning
                            ) { Text("停止容器") }
                        }
                    }
                }
            }

            // 下载镜像源设置
            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("下载镜像源设置", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "设置自定义镜像源加速下载（留空使用默认源）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = mirrorUrlInput,
                            onValueChange = { mirrorUrlInput = it },
                            label = { Text("镜像源 URL") },
                            placeholder = { Text("https://mirror.example.com/toolchain/arm64") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.testMirrorConnection(mirrorUrlInput)
                                },
                                enabled = !mirrorTesting && mirrorUrlInput.isNotBlank()
                            ) {
                                if (mirrorTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("测试连接")
                                }
                            }
                            Button(
                                onClick = {
                                    viewModel.saveMirrorUrl(mirrorUrlInput)
                                },
                                enabled = !busy
                            ) { Text("保存") }
                            Button(
                                onClick = {
                                    mirrorUrlInput = ""
                                    viewModel.resetMirror()
                                },
                                enabled = !busy
                            ) { Text("重置") }
                        }

                        // 测试结果显示
                        mirrorTestResult?.let { result ->
                            Spacer(Modifier.height(8.dp))
                            val color = if (result.success) Color(0xFF4CAF50) else Color(0xFFE53935)
                            val label = if (result.success) "连接成功" else "连接失败"
                            Text(
                                "$label · ${result.url}",
                                color = color,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // 缓存与工具链
            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("缓存与工具链", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.cleanCache() },
                                enabled = !busy
                            ) { Text("清理编译缓存") }
                            Button(
                                onClick = { viewModel.updateToolchain() },
                                enabled = !busy
                            ) { Text("更新工具链") }
                        }
                    }
                }
            }

            // 快照
            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("容器快照", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { showSnapshotDialog = true },
                            enabled = !busy
                        ) { Text("创建快照") }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        if (snapshots.isEmpty()) {
                            Text("暂无快照", color = MaterialTheme.colorScheme.outline)
                        } else {
                            snapshots.forEach { snap ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(snap.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${snap.id}  •  ${snap.sizeBytes}B",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Button(onClick = { viewModel.loadSnapshot(snap.id) }) { Text("加载") }
                                        Button(onClick = { viewModel.deleteSnapshot(snap.id) }) { Text("删除") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        message?.let {
            Text(it, modifier = Modifier.padding(16.dp))
        }
    }

    if (showSnapshotDialog) {
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSnapshotDialog = false },
            title = { Text("创建快照") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("快照名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            viewModel.createSnapshot(name)
                            showSnapshotDialog = false
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                Button(onClick = { showSnapshotDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 根据健康状态显示不同颜色的标签 */
@Composable
private fun HealthStateBadge(state: ProotContainerManager.HealthState) {
    val (label, color) = when (state) {
        ProotContainerManager.HealthState.STOPPED -> "已停止" to Color.Gray
        ProotContainerManager.HealthState.DEPLOYING -> "部署中" to Color(0xFFFF9800)
        ProotContainerManager.HealthState.HEALTHY -> "运行中" to Color(0xFF4CAF50)
        ProotContainerManager.HealthState.RESTARTING -> "重启中" to Color(0xFF2196F3)
        ProotContainerManager.HealthState.ERROR -> "异常" to Color(0xFFE53935)
    }
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.bodyMedium
    )
}