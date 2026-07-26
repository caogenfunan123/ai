package com.her.aimodifier.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON 工具统一入口。
 *
 * 全局配置：
 * - ignoreUnknownKeys = true（向后兼容）
 * - encodeDefaults = false（避免输出默认值噪音）
 * - explicitNulls = false（不输出 null 字段）
 */
object JsonUtil {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = false
    }

    val prettyJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = true
    }

    fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T =
        json.decodeFromString(serializer, text)

    /** 构建简单 JsonObject */
    fun obj(vararg pairs: Pair<String, Any?>): JsonObject {
        val map = pairs.filter { it.second != null }
            .associate { (k, v) -> k to JsonPrimitive(v.toString()) }
        return JsonObject(map)
    }
}
