package com.her.aimodifier.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.her.aimodifier.base.constants.AppConstants
import com.her.aimodifier.data.database.dao.AiConfigDao
import com.her.aimodifier.data.database.dao.ChatSessionDao
import com.her.aimodifier.data.database.dao.LocalModelDao
import com.her.aimodifier.data.database.dao.WorkspaceDao
import com.her.aimodifier.data.database.entity.AiConfigEntity
import com.her.aimodifier.data.database.entity.ChatSessionEntity
import com.her.aimodifier.data.database.entity.LocalModelEntity
import com.her.aimodifier.data.database.entity.WorkspaceEntity
import com.her.aimodifier.data.pref.EncryptedPrefs
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * 应用加密数据库（Room + SQLCipher）。
 *
 * - 数据库密钥首次启动生成，存于 [EncryptedPrefs]
 * - 通过 [SupportFactory] 把密钥传给 Room 的 OpenHelper
 * - 通过 [get] 单例访问，避免重复打开
 */
@Database(
    entities = [
        WorkspaceEntity::class,
        ChatSessionEntity::class,
        AiConfigEntity::class,
        LocalModelEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun workspaceDao(): WorkspaceDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun aiConfigDao(): AiConfigDao
    abstract fun localModelDao(): LocalModelDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }
        }

        private fun build(context: Context): AppDatabase {
            val prefs = EncryptedPrefs(context)
            val passphrase: ByteArray = prefs.dbPassphrase.ensureGenerated()

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                AppConstants.DB_NAME
            )
                .openHelperFactory(SupportFactory(passphrase, null, false))
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // 创建索引（如未通过 @Index 自动生成）
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_chat_session_workspaceId ON chat_session(workspaceId)"
                        )
                    }
                })
                .build()
        }

        @Suppress("unused")
        private fun newSqlCipherKey(): ByteArray =
            UUID_RANDOM_32()
    }
}

private fun UUID_RANDOM_32(): ByteArray {
    val random = java.security.SecureRandom()
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return bytes
}
