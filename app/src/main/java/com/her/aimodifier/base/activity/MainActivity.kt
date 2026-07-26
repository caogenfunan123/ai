package com.her.aimodifier.base.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.her.aimodifier.base.navigation.AppNavGraph
import com.her.aimodifier.ui.theme.AiModifierTheme

/**
 * 单 Activity 入口。
 *
 * - 所有页面通过 Compose Navigation 切换
 * - Drawer / 业务页面在 [AppNavGraph] 中组合
 * - 全屏沉浸式 + Compose 主题
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AiModifierTheme {
                AppNavGraph()
            }
        }
    }
}
