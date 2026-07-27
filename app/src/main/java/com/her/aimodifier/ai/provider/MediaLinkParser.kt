package com.her.aimodifier.ai.provider

/**
 * 媒体链接数据类。
 *
 * 直接存储 base64Data 和 mimeType，不依赖任何媒体池管理器。
 * 解析时仅填充 type 与 id，base64Data/mimeType 留空，
 * 由调用方在需要时自行注入实际媒体数据。
 */
data class MediaLink(
    val type: String,
    val id: String,
    val base64Data: String,
    val mimeType: String
)

/**
 * 图片链接数据类，结构与 [MediaLink] 一致。
 */
data class ImageLink(
    val type: String,
    val id: String,
    val base64Data: String,
    val mimeType: String
)

/**
 * 仅包含类型与标识的媒体链接标签，用于不需要实际数据的场景。
 */
data class MediaLinkTag(
    val type: String,
    val id: String
)

/**
 * 媒体链接解析器。
 *
 * 使用正则匹配 `<link type="image|audio|video" id="...">...</link>` 标签，
 * 同时支持普通（PLAIN）和转义（ESCAPED，含 \"）两种格式。
 * 自动去重（seenIds），跳过 id == "error" 的占位符。
 */
object MediaLinkParser {
    private val IMAGE_LINK_PATTERN_PLAIN = Regex(
        """<link\s+type\s*=\s*\\?["']?image\\?["']?\s+id\s*=\s*\\?["']?([^"'\s>]+)\\?["']?\s*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val IMAGE_LINK_PATTERN_ESCAPED = Regex(
        """<link\s+type\s*=\s*\\?["']?image\\?["']?\s+id\s*=\s*\\?["']?([^"'\s>]+)\\?["']?\s*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val LINK_PATTERN_PLAIN = Regex(
        """<link\s+type=\"?(audio|video)\"?\s+id=\"?([^\"\s>]+)\"?\s*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val LINK_PATTERN_ESCAPED = Regex(
        """<link\s+type=\\\"?(audio|video)\\\"?\s+id=\\\"?([^\"\s>]+)\\\"?\s*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * 提取消息中的所有图片链接。
     *
     * 注意：当前实现不依赖图片池管理器，返回的 [ImageLink] 中
     * base64Data 与 mimeType 均为空字符串，调用方需自行补充实际数据。
     */
    fun extractImageLinks(message: String): List<ImageLink> {
        val imageLinks = mutableListOf<ImageLink>()
        val seenIds = mutableSetOf<String>()

        fun collectFromPattern(pattern: Regex) {
            pattern.findAll(message).forEach { match ->
                val id = match.groupValues[1]
                if (id == "error") {
                    return@forEach
                }
                if (!seenIds.add(id)) {
                    return@forEach
                }
                imageLinks.add(
                    ImageLink(
                        type = "image",
                        id = id,
                        base64Data = "",
                        mimeType = ""
                    )
                )
            }
        }

        collectFromPattern(IMAGE_LINK_PATTERN_PLAIN)
        collectFromPattern(IMAGE_LINK_PATTERN_ESCAPED)

        return imageLinks
    }

    fun extractImageLinkIds(message: String): List<String> {
        val ids = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()

        fun collectFromPattern(pattern: Regex) {
            pattern.findAll(message).forEach { match ->
                val id = match.groupValues[1]
                if (id == "error") {
                    return@forEach
                }
                if (seenIds.add(id)) {
                    ids.add(id)
                }
            }
        }

        collectFromPattern(IMAGE_LINK_PATTERN_PLAIN)
        collectFromPattern(IMAGE_LINK_PATTERN_ESCAPED)

        return ids
    }

    fun removeImageLinks(message: String): String {
        return message
            .replace(IMAGE_LINK_PATTERN_PLAIN, "")
            .replace(IMAGE_LINK_PATTERN_ESCAPED, "")
    }

    fun replaceImageLinks(message: String, replacer: (id: String) -> String): String {
        var result = message
        val patterns = listOf(IMAGE_LINK_PATTERN_PLAIN, IMAGE_LINK_PATTERN_ESCAPED)
        patterns.forEach { pattern ->
            result = pattern.replace(result) { match ->
                val id = match.groupValues.getOrNull(1) ?: return@replace ""
                if (id == "error") "" else replacer(id)
            }
        }
        return result
    }

    fun hasImageLinks(message: String): Boolean {
        return IMAGE_LINK_PATTERN_PLAIN.containsMatchIn(message) ||
            IMAGE_LINK_PATTERN_ESCAPED.containsMatchIn(message)
    }

    /**
     * 提取消息中的所有媒体链接（音频/视频）。
     *
     * 注意：当前实现不依赖媒体池管理器与 base64 限制器，返回的 [MediaLink] 中
     * base64Data 与 mimeType 均为空字符串，调用方需自行补充实际数据。
     */
    fun extractMediaLinks(message: String): List<MediaLink> {
        val links = mutableListOf<MediaLink>()
        val seenIds = mutableSetOf<String>()

        fun collectFromPattern(pattern: Regex) {
            pattern.findAll(message).forEach { match ->
                val type = match.groupValues[1]
                val id = match.groupValues[2]

                if (id == "error") {
                    return@forEach
                }

                if (!seenIds.add("$type:$id")) {
                    return@forEach
                }

                links.add(
                    MediaLink(
                        type = type,
                        id = id,
                        base64Data = "",
                        mimeType = ""
                    )
                )
            }
        }

        collectFromPattern(LINK_PATTERN_PLAIN)
        collectFromPattern(LINK_PATTERN_ESCAPED)

        return links
    }

    fun extractMediaLinkTags(message: String): List<MediaLinkTag> {
        val tags = mutableListOf<MediaLinkTag>()
        val seenIds = mutableSetOf<String>()

        fun collectFromPattern(pattern: Regex) {
            pattern.findAll(message).forEach { match ->
                val type = match.groupValues[1]
                val id = match.groupValues[2]
                if (id == "error") {
                    return@forEach
                }
                if (!seenIds.add("$type:$id")) {
                    return@forEach
                }
                tags.add(MediaLinkTag(type = type, id = id))
            }
        }

        collectFromPattern(LINK_PATTERN_PLAIN)
        collectFromPattern(LINK_PATTERN_ESCAPED)

        return tags
    }

    fun replaceMediaLinks(message: String, replacer: (type: String, id: String) -> String): String {
        var result = message
        val patterns = listOf(LINK_PATTERN_PLAIN, LINK_PATTERN_ESCAPED)
        patterns.forEach { pattern ->
            result = pattern.replace(result) { match ->
                val type = match.groupValues.getOrNull(1) ?: return@replace ""
                val id = match.groupValues.getOrNull(2) ?: return@replace ""
                if (id == "error") "" else replacer(type, id)
            }
        }
        return result
    }

    fun removeMediaLinks(message: String): String {
        return message
            .replace(LINK_PATTERN_PLAIN, "")
            .replace(LINK_PATTERN_ESCAPED, "")
    }

    fun hasMediaLinks(message: String): Boolean {
        return LINK_PATTERN_PLAIN.containsMatchIn(message) || LINK_PATTERN_ESCAPED.containsMatchIn(message)
    }
}
