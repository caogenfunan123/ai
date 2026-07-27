package com.her.aimodifier.ai.enhance

/**
 * AI 引用数据类，表示从内容中提取出的一个 Markdown 链接引用。
 *
 * @property title 引用标题
 * @property url 引用 URL
 */
data class AiReference(
    val title: String,
    val url: String
)

/**
 * 从内容中提取引用的工具类。
 *
 * 当前实现支持 Markdown 链接格式 `[title](url)`，
 * 仅提取 http/https 链接作为引用。
 */
object ReferenceManager {
    /**
     * 从内容中提取引用。
     *
     * @param content 要提取引用的内容
     * @return 引用列表
     */
    fun extractReferences(content: String): List<AiReference> {
        // 使用 Markdown 链接格式: [title](url)
        val regex = "\\[([^\\]]+)\\]\\((https?://[^\\)]+)\\)".toRegex()
        val matches = regex.findAll(content)

        return matches.map { match ->
            val (title, url) = match.destructured
            AiReference(title, url)
        }.toList()
    }
}
