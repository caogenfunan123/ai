package com.her.aimodifier.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地 GGUF 模型信息表。
 *
 * 仅记录元数据；模型本体存放在 [com.her.aimodifier.base.constants.PathConstants.localModelDir]。
 */
@Entity(
    tableName = "local_model",
    indices = [Index(value = ["filePath"], unique = true)]
)
data class LocalModelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    val filePath: String,

    val sizeBytes: Long,

    val quant: String = "Q4_K_M",

    val contextLength: Int = 4096,

    val sha256: String? = null,

    val sourceUrl: String? = null,

    val status: String = STATUS_OK,

    val loaded: Boolean = false,

    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_DAMAGED = "DAMAGED"
    }
}
