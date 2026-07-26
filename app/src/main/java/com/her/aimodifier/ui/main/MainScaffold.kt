package com.her.aimodifier.ui.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.her.aimodifier.base.navigation.Destinations
import kotlinx.coroutines.launch

/**
 * 主页：Drawer 抽屉 + 内容区。
 *
 * 内容区默认展示 AI 对话窗口（[ChatPane]）；抽屉中提供各业务页入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(navController: NavController) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val menu: List<DrawerItem> = listOf(
        DrawerItem("会话", Icons.AutoMirrored.Filled.MenuBook) {
            navController.navigate(Destinations.chat(null)) { launchSingleTop = true }
        },
        DrawerItem("工作区", Icons.Filled.Folder) {
            navController.navigate(Destinations.WORKSPACE)
        },
        DrawerItem("AI 模型设置", Icons.Filled.SmartToy) {
            navController.navigate(Destinations.aiSetting(null))
        },
        DrawerItem("本地 GGUF 模型", Icons.Filled.Cloud) {
            navController.navigate(Destinations.LOCAL_MODEL)
        },
        DrawerItem("工具箱", Icons.Filled.Build) {
            navController.navigate(Destinations.TOOLBOX)
        },
        DrawerItem("MCP 插件", Icons.Filled.Extension) {
            navController.navigate(Destinations.MCP_PANEL)
        },
        DrawerItem("设置", Icons.Filled.Settings) {
            navController.navigate(Destinations.CONFIG_EXPORT)
        },
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("AI 魔改器", Modifier.padding(16.dp))
                menu.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.title) },
                        selected = false,
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            item.onClick()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI 魔改器") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "菜单")
                        }
                    }
                )
            }
        ) { padding ->
            ChatPane(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onOpenLocalModels = { navController.navigate(Destinations.LOCAL_MODEL) },
                onOpenAiSettings = { navController.navigate(Destinations.aiSetting(null)) }
            )
        }
    }
}

private data class DrawerItem(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)
