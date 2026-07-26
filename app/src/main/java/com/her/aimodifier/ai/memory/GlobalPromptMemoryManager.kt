package com.her.aimodifier.ai.memory

import com.her.aimodifier.data.pref.EncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI 强制记忆（全局 Prompt）管理器。
 *
 * 全局 Prompt 在每次对话请求的 messages 头部以 system 角色注入，
 * 用于持久化"系统人设""工作习惯""用户偏好"等长期记忆。
 *
 * 工作区级 Prompt 覆盖：当 [workspacePrompt] 非空时优先使用，
 * 否则回退到 [globalPrompt]。
 */
class GlobalPromptMemoryManager(private val prefs: EncryptedPrefs) {

    private val _promptFlow = MutableStateFlow(prefs.globalSystemPrompt.ifEmpty { BUILTIN_SYSTEM_PROMPT })
    val promptFlow: StateFlow<String> = _promptFlow.asStateFlow()

    var globalPrompt: String
        get() = prefs.globalSystemPrompt.ifEmpty { BUILTIN_SYSTEM_PROMPT }
        set(value) {
            prefs.globalSystemPrompt = value
            _promptFlow.value = value
        }

    /** 工作区级 Prompt 覆盖（运行时内存 Map，由 WorkspaceManager 维护） */
    private val workspacePrompts = mutableMapOf<String, String>()

    fun setWorkspacePrompt(workspaceId: String, prompt: String?) {
        if (prompt.isNullOrEmpty()) workspacePrompts.remove(workspaceId)
        else workspacePrompts[workspaceId] = prompt
    }

    fun getWorkspacePrompt(workspaceId: String): String? = workspacePrompts[workspaceId]

    /**
     * 解析最终生效 Prompt。
     * @return Pair(prompt, source) source = "workspace" / "global" / "none"
     */
    fun resolve(workspaceId: String?): Pair<String, String> {
        workspacePrompts[workspaceId ?: ""]?.let { return it to "workspace" }
        val g = globalPrompt
        return if (g.isNotEmpty()) g to "global" else "" to "none"
    }

    /** 拼装到 messages 头部 */
    fun buildSystemMessage(workspaceId: String?): com.her.aimodifier.ai.client.ChatMessage? {
        val (prompt, source) = resolve(workspaceId)
        if (prompt.isEmpty()) return null
        return com.her.aimodifier.ai.client.ChatMessage(
            role = "system",
            content = prompt,
            name = "system_prompt_$source"
        )
    }

    suspend fun getPrompt(): String = globalPrompt

    suspend fun setPrompt(prompt: String) {
        globalPrompt = prompt
    }

    suspend fun resetToDefault() {
        globalPrompt = BUILTIN_SYSTEM_PROMPT
    }

    fun observePrompt(): kotlinx.coroutines.flow.Flow<String> = promptFlow

    companion object {
        val BUILTIN_SYSTEM_PROMPT = """你是AI魔改器内置智能代理，运行于Android ARM64平台。
当前程序底层拥有一套MCP工具调度系统，可执行安卓逆向、APK编译、Frida调试、流量抓包、Magisk/KernelSU模块构建、静态DEX逆向任务。

【核心规则，必须严格遵守】
1. 用户下达功能性指令时，禁止单纯文字描述方案；优先调用系统工具自动执行操作。
2. 触发工具调用格式规范：
固定前缀 //TOOL_CALL: 紧随标准单行JSON，不添加多余换行、注释、markdown标记。
JSON结构：
{
  "taskId": "任务标识",
  "workspaceId": "当前工作区ID",
  "args": {}
}
允许使用的taskId固定清单（只能选下面字符串，禁止自创）：
apk_build          APK源码编译
lspatch_repack     LSPatch重打包APK
frida_hook         Frida脚本调试
traffic_capture    Mitmproxy抓包
magisk_mod         Magisk模块编译
ksu_module_build   KernelSU模块构建
static_reverse     静态DEX/APK逆向分析

3. 执行流程逻辑：
输出TOOL_CALL标记后，APP会自动执行整套链路：
toolchain_check_env → toolchain_prepare_task(自动下载缺失工具) → toolchain_run_command
执行日志会实时流回对话窗口，你读取日志后继续分析报错、输出修复方案。

4. 判断规则：
✅ 需要操作文件、编译、抓包、Hook、打包、逆向 → 使用TOOL_CALL调用工具
✅ 单纯理论讲解、代码解释、概念问答 → 正常文本回复，不触发工具调用

5. 约束：
- JSON必须单行，不能换行拆分
- 不要增加额外解释文字在JSON同一行后方
- 无法识别用户需求时，主动向用户确认任务目标
- 如果执行工具返回错误日志，分析错误，生成修复命令，再次发起TOOL_CALL重试

【上下文信息】
后续对话自动携带当前workspaceId，发起工具调用务必填充正确workspaceId，保证工作区目录路径正常映射。"""
    }
}
