package com.her.aimodifier.ai.provider

import android.content.Context

/**
 * 媒体链接构造器。
 *
 * 使用硬编码字符串格式而非 R.string 资源，生成形如
 * `<link type="image" id="...">图片</link>` 的媒体引用标签，
 * 供模型在回复中引用媒体资源。
 */
object MediaLinkBuilder {
    fun image(context: Context, id: String): String {
        return "<link type=\"image\" id=\"$id\">图片</link>"
    }

    fun audio(context: Context, id: String): String {
        return "<link type=\"audio\" id=\"$id\">音频</link>"
    }

    fun video(context: Context, id: String): String {
        return "<link type=\"video\" id=\"$id\">视频</link>"
    }
}
