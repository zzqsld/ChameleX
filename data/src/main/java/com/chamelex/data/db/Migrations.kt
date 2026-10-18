package com.chamelex.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.chamelex.protocol.MfcType

/**
 * 正式迁移（替代 fallbackToDestructiveMigration）。
 *
 * v1：dumps(id, name, tagType, uid, source, createdAt, data)
 * v2：dumps + sectorCount/blockCount/readableMask/keys
 * v3：+ key_groups / keys / card_keys
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE dumps ADD COLUMN sectorCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE dumps ADD COLUMN blockCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE dumps ADD COLUMN readableMask BLOB NOT NULL DEFAULT X''")
        db.execSQL("ALTER TABLE dumps ADD COLUMN keys BLOB NOT NULL DEFAULT X''")

        db.query("SELECT id, tagType, data FROM dumps").use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val typeIndex = cursor.getColumnIndexOrThrow("tagType")
            val dataIndex = cursor.getColumnIndexOrThrow("data")
            while (cursor.moveToNext()) {
                val metadata = legacyDumpMetadata(
                    tagType = cursor.getInt(typeIndex),
                    dataSize = cursor.getBlob(dataIndex)?.size ?: 0,
                )
                db.execSQL(
                    """
                    UPDATE dumps
                    SET sectorCount = ?, blockCount = ?, readableMask = ?, keys = ?
                    WHERE id = ?
                    """.trimIndent(),
                    arrayOf(
                        metadata.sectorCount,
                        metadata.blockCount,
                        metadata.readableMask,
                        metadata.keys,
                        cursor.getLong(idIndex),
                    ),
                )
            }
        }
    }
}

internal data class LegacyDumpMetadata(
    val sectorCount: Int,
    val blockCount: Int,
    val readableMask: ByteArray,
    val keys: ByteArray,
)

internal fun legacyDumpMetadata(tagType: Int, dataSize: Int): LegacyDumpMetadata {
    val type = MfcType.fromSak(tagType)
        ?.takeIf { it.blockCount * 16 == dataSize }
        ?: MfcType.fromByteSize(dataSize)
    if (type == null) return LegacyDumpMetadata(0, 0, ByteArray(0), ByteArray(0))

    return LegacyDumpMetadata(
        sectorCount = type.sectorCount,
        blockCount = type.blockCount,
        readableMask = ByteArray((type.blockCount + 7) / 8) { 0xFF.toByte() },
        keys = ByteArray(type.sectorCount * 12) { 0xFF.toByte() },
    )
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS key_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                isBuiltin INTEGER NOT NULL DEFAULT 0,
                sortOrder INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS keys (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                groupId INTEGER NOT NULL,
                keyHex TEXT NOT NULL,
                sourceUid TEXT,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_keys_keyHex ON keys(keyHex)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_keys_groupId ON keys(groupId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS card_keys (
                uid TEXT PRIMARY KEY NOT NULL,
                sectorCount INTEGER NOT NULL,
                keysData BLOB NOT NULL,
                nickname TEXT,
                lastUsedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
