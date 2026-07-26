package com.her.aimodifier.base.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.her.aimodifier.ui.ai_setting.AiSettingScreen
import com.her.aimodifier.ui.local_model.LocalModelScreen
import com.her.aimodifier.ui.main.MainScaffold
import com.her.aimodifier.ui.mcp_panel.McpPanelScreen
import com.her.aimodifier.ui.prompt.PromptMemoryScreen
import com.her.aimodifier.ui.settings.ConfigExportScreen
import com.her.aimodifier.ui.toolbox.ToolboxScreen
import com.her.aimodifier.ui.workspace.WorkspaceScreen

@Composable
fun AppNavGraph() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Destinations.MAIN) {

        composable(Destinations.MAIN) {
            MainScaffold(
                navController = nav
            )
        }

        composable(
            route = Destinations.WORKSPACE,
        ) {
            WorkspaceScreen(
                onOpenWorkspace = { workspace ->
                    nav.navigate(Destinations.chat(workspace.workspaceId)) {
                        popUpTo(Destinations.MAIN) { saveState = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = Destinations.AI_SETTING,
            arguments = listOf(
                navArgument(Destinations.ARG_WORKSPACE_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val wsId = backStackEntry.arguments?.getString(Destinations.ARG_WORKSPACE_ID).orEmpty()
            AiSettingScreen(
                workspaceId = wsId.ifEmpty { null },
                onBack = { nav.popBackStack() }
            )
        }

        composable(Destinations.LOCAL_MODEL) {
            LocalModelScreen(onBack = { nav.popBackStack() })
        }

        composable(Destinations.TOOLBOX) {
            ToolboxScreen(onBack = { nav.popBackStack() })
        }

        composable(Destinations.MCP_PANEL) {
            McpPanelScreen(onBack = { nav.popBackStack() })
        }

        composable(Destinations.PROMPT_MEMORY) {
            PromptMemoryScreen(onBack = { nav.popBackStack() })
        }

        composable(Destinations.CONFIG_EXPORT) {
            ConfigExportScreen(onBack = { nav.popBackStack() })
        }
    }
}