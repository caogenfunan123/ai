package com.her.aimodifier.ui.workspace

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.her.aimodifier.data.database.entity.WorkspaceEntity
import com.her.aimodifier.viewModel.WorkspaceViewModel

/**
 * 工作区管理页（最终定稿）。
 *
 * 功能：新建/打开/删除项目，三种创建方式（空白/导入/Git拉取）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onOpenWorkspace: (WorkspaceEntity) -> Unit,
    viewModel: WorkspaceViewModel = viewModel()
) {
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("工作区管理") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(workspaces, key = { it.workspaceId }) { ws ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ws.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "来源：${ws.source}  •  ${ws.gitUrl ?: ws.localPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Button(onClick = { onOpenWorkspace(ws) }) { Text("打开") }
                        IconButton(onClick = { viewModel.delete(ws.workspaceId) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }

        if (creating) {
            Text("处理中…", modifier = Modifier.padding(16.dp))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
    }

    if (showCreate) {
        CreateWorkspaceDialog(
            onDismiss = { showCreate = false },
            onCreateBlank = { name ->
                viewModel.createBlank(name)
                showCreate = false
            },
            onImportLocal = { name, dir ->
                viewModel.importLocal(name, dir)
                showCreate = false
            },
            onCloneGit = { name, url, branch ->
                viewModel.cloneFromGit(name, url, branch)
                showCreate = false
            }
        )
    }
}

@Composable
private fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreateBlank: (String) -> Unit,
    onImportLocal: (String, String) -> Unit,
    onCloneGit: (String, String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("blank") }
    var localDir by remember { mutableStateOf("") }
    var gitUrl by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建工作区") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { mode = "blank" },
                        enabled = mode != "blank"
                    ) { Text("空白项目") }
                    Button(
                        onClick = { mode = "local" },
                        enabled = mode != "local"
                    ) { Text("导入目录") }
                    Button(
                        onClick = { mode = "git" },
                        enabled = mode != "git"
                    ) { Text("Git 拉取") }
                }
                Spacer(Modifier.height(8.dp))
                when (mode) {
                    "local" -> OutlinedTextField(
                        value = localDir,
                        onValueChange = { localDir = it },
                        label = { Text("本地源码目录绝对路径") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    "git" -> {
                        OutlinedTextField(
                            value = gitUrl,
                            onValueChange = { gitUrl = it },
                            label = { Text("Git 仓库 URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = branch,
                            onValueChange = { branch = it },
                            label = { Text("分支（可选，默认主分支）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (mode) {
                        "blank" -> onCreateBlank(name)
                        "local" -> onImportLocal(name, localDir)
                        "git" -> onCloneGit(name, gitUrl, branch.ifEmpty { null })
                    }
                },
                enabled = name.isNotBlank() && when (mode) {
                    "blank" -> true
                    "local" -> localDir.isNotBlank()
                    "git" -> gitUrl.isNotBlank()
                    else -> false
                }
            ) { Text("创建") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("取消") } }
    )
}
