package com.her.aimodifier.ui.main.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * AI 对话主窗口（占位实现）。
 *
 * P3 阶段实现：流式渲染 / 暂停按钮 / 输入区 / 工作区绑定。
 */
@Composable
fun ChatPane(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("AI 对话窗口（待实现：流式 / 暂停 / 工作区绑定）")
    }
}
