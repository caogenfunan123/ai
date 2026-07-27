package com.her.aimodifier.ai.enhance

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * 管理AI助手对话轮次的管理器。
 *
 * 该类负责跟踪和管理不同的对话轮次，
 * 特别是在工具执行和响应处理时使用。
 *
 * 使用 AtomicInteger 保证线程安全，使用 StringBuilder 累积内容。
 */
class ConversationRoundManager {
    companion object {
        private const val TAG = "ConversationRoundMgr"
        private const val ROUND_SEPARATOR_FORMAT = "--- Round %d ---\n"
    }

    // 存储每一轮内容的 Map
    private val roundContents = mutableMapOf<Int, StringBuilder>()

    // 跟踪当前轮次号
    private val currentResponseRound = AtomicInteger(0)

    // 用于移除显示内容中轮次分隔符的正则
    private val roundSeparatorPattern = Regex("--- Round \\d+ ---\n")

    /** 初始化新的对话，重置所有轮次跟踪状态。 */
    fun initializeNewConversation() {
        currentResponseRound.set(0)
        synchronized(roundContents) {
            roundContents.clear()
        }
        Log.d(TAG, "New conversation initialized")
    }

    /**
     * 更新当前轮次的内容。
     *
     * @param content 要添加或更新的内容
     * @return 更新后的累计内容
     */
    fun updateContent(content: String): String {
        val currentRound = currentResponseRound.get()
        synchronized(roundContents) {
            val builder = roundContents.getOrPut(currentRound) { StringBuilder() }
            builder.setLength(0)
            builder.append(content)
        }
        return getDisplayContent()
    }

    /**
     * 追加一个流式分块，不重建当前轮次的完整文本。
     *
     * @param content 要追加的内容
     * @return 当前轮次累积内容后的字符串
     */
    fun appendChunk(content: String): String {
        val currentRound = currentResponseRound.get()
        synchronized(roundContents) {
            val builder = roundContents.getOrPut(currentRound) { StringBuilder() }
            builder.append(content)
            return builder.toString()
        }
    }

    /**
     * 开始新的轮次。
     *
     * @return 自增后的当前轮次号
     */
    fun startNewRound(): Int {
        val newRound = currentResponseRound.incrementAndGet()
        synchronized(roundContents) {
            roundContents[newRound] = StringBuilder()
        }
        Log.d(TAG, "Starting new round: $newRound")
        return newRound
    }

    /**
     * 将内容追加到累计内容的末尾，超出任何轮次结构（key 为 -1）。
     *
     * @param content 要追加的内容
     * @return 更新后的显示内容
     */
    fun appendContent(content: String): String {
        appendChunk("\n" + content.trim())
        return getDisplayContent()
    }

    /**
     * 获取适合显示的内容（已移除轮次分隔符）。
     *
     * @return 不含轮次分隔符的干净内容
     */
    fun getDisplayContent(): String {
        val buffer = StringBuilder()

        synchronized(roundContents) {
            // 按顺序添加轮次
            val sortedKeys = roundContents.keys.filter { it >= 0 }.sorted()

            sortedKeys.forEachIndexed { index, round ->
                val content = roundContents[round] ?: StringBuilder()
                if (index > 0) buffer.append("\n")
                buffer.append(content)
            }

            // 追加轮次之外的内容（key 为 -1）
            if (roundContents.containsKey(-1)) {
                buffer.append("\n").append(roundContents[-1])
            }
        }

        return buffer.toString()
    }

    /** 获取当前轮次的内容。 */
    fun getCurrentRoundContent(): String {
        synchronized(roundContents) {
            return roundContents[currentResponseRound.get()]?.toString().orEmpty()
        }
    }

    /**
     * 获取包含所有轮次分隔符的原始累计内容。
     *
     * @return 含轮次分隔符的原始内容
     */
    fun getRawContent(): String {
        val buffer = StringBuilder()

        synchronized(roundContents) {
            // 按顺序添加轮次（带分隔符）
            val sortedKeys = roundContents.keys.filter { it >= 0 }.sorted()

            sortedKeys.forEachIndexed { index, round ->
                val content = roundContents[round] ?: StringBuilder()
                if (index > 0) buffer.append("\n")
                buffer.append(String.format(ROUND_SEPARATOR_FORMAT, round))
                buffer.append(content)
            }

            // 追加轮次之外的内容（key 为 -1）
            if (roundContents.containsKey(-1)) {
                buffer.append("\n").append(roundContents[-1])
            }
        }

        return buffer.toString()
    }

    /**
     * 获取当前轮次号。
     *
     * @return 当前轮次号
     */
    fun getCurrentRound(): Int {
        return currentResponseRound.get()
    }

    /** 清除所有内容。 */
    fun clearContent() {
        synchronized(roundContents) {
            roundContents.clear()
        }
        Log.d(TAG, "Content cleared")
    }
}
