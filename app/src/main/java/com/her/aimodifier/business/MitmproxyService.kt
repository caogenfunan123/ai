package com.her.aimodifier.business

import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.mcp.core.McpCallResult
import com.her.aimodifier.mcp.core.McpClient

/**
 * Mitmproxy 抓包服务（最终定稿）。
 *
 * 系统联动规则：禁止直接操作 PRoot，全部通过 MCP 调度。
 *
 * 流程：
 * 1. check_env
 * 2. prepare_task("traffic_capture") 自动补齐 mitmproxy / socat / tcpdump / patchelf 等
 * 3. run_command 启动 mitmproxy / mitmdump
 * 4. run_command 启动 tcpdump 后台抓包
 */
class MitmproxyService(mcpClient: McpClient) :
    McpBasedTask(mcpClient, AppConstants.TaskIds.TRAFFIC_CAPTURE) {

    /**
     * 启动 mitmdump（无交互模式）。
     *
     * @param port 监听端口，默认 8080
     * @param scriptPath 拦截脚本（可选）
     * @param outputPath 抓包结果保存路径
     */
    suspend fun startMitmdump(
        port: Int = 8080,
        scriptPath: String? = null,
        outputPath: String,
        executionId: String = ""
    ): McpCallResult {
        val cmd = buildString {
            append("mitmdump --listen-port ").append(port)
            append(" -w ").append(outputPath)
            if (scriptPath != null) append(" -s ").append(scriptPath)
            append(" &")
        }
        return execute(executionId, cmd, null)
    }

    /** 启动 mitmweb（交互模式，提供 Web UI） */
    suspend fun startMitmweb(
        port: Int = 8081,
        webPort: Int = 8082,
        executionId: String = ""
    ): McpCallResult {
        val cmd = "mitmweb --listen-port $port --web-port $webPort --web-open-browser=false &"
        return execute(executionId, cmd, null)
    }

    /** 启动 tcpdump 抓包 */
    suspend fun startTcpdump(
        interfaceName: String = "any",
        outputPath: String,
        executionId: String = ""
    ): McpCallResult {
        val cmd = "tcpdump -i $interfaceName -w $outputPath &"
        return execute(executionId, cmd, null)
    }

    /** 启动 strace 跟踪进程 */
    suspend fun startStrace(
        pid: Int,
        outputPath: String,
        executionId: String = ""
    ): McpCallResult {
        val cmd = "strace -p $pid -o $outputPath -ff &"
        return execute(executionId, cmd, null)
    }
}
