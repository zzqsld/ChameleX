package com.chamelex.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.Cursor
import com.chamelex.protocol.MfcType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** 迁移 SQL 结构测试（动态代理捕获 execSQL 语句）。 */
class MigrationsTest {

    /** 捕获 execSQL 的假 SupportSQLiteDatabase。 */
    private fun capturingDb(sql: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                sql.add(args[0] as String)
            }
            if (method.name == "query") emptyCursor() else null
        } as SupportSQLiteDatabase

    private fun emptyCursor(): Cursor =
        Proxy.newProxyInstance(
            Cursor::class.java.classLoader,
            arrayOf(Cursor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "moveToNext" -> false
                "getColumnIndexOrThrow" -> when (args?.firstOrNull()) {
                    "id" -> 0
                    "tagType" -> 1
                    "data" -> 2
                    else -> -1
                }
                "close" -> null
                else -> null
            }
        } as Cursor

    @Test
    fun `migration 1 to 2 adds dump v2 columns`() {
        val sql = mutableListOf<String>()
        MIGRATION_1_2.migrate(capturingDb(sql))
        assertEquals(4, sql.size)
        assertTrue(sql.all { it.startsWith("ALTER TABLE dumps ADD COLUMN") })
        assertTrue(sql.any { it.contains("sectorCount") })
        assertTrue(sql.any { it.contains("blockCount") })
        assertTrue(sql.any { it.contains("readableMask") })
        assertTrue(sql.any { it.contains("keys") })
    }

    @Test
    fun `legacy mifare dump metadata is restored during migration`() {
        val metadata = legacyDumpMetadata(MfcType.CLASSIC_1K.sak, 1024)

        assertEquals(16, metadata.sectorCount)
        assertEquals(64, metadata.blockCount)
        assertEquals(8, metadata.readableMask.size)
        assertTrue(metadata.readableMask.all { it == 0xFF.toByte() })
        assertEquals(16 * 12, metadata.keys.size)
        assertTrue(metadata.keys.all { it == 0xFF.toByte() })
    }

    @Test
    fun `legacy unknown dump metadata remains empty`() {
        val metadata = legacyDumpMetadata(tagType = 0, dataSize = 13)

        assertEquals(0, metadata.sectorCount)
        assertEquals(0, metadata.blockCount)
        assertArrayEquals(ByteArray(0), metadata.readableMask)
        assertArrayEquals(ByteArray(0), metadata.keys)
    }

    @Test
    fun `migration 2 to 3 creates key tables`() {
        val sql = mutableListOf<String>()
        MIGRATION_2_3.migrate(capturingDb(sql))
        assertTrue(sql.any { it.contains("CREATE TABLE") && it.contains("key_groups") })
        assertTrue(sql.any { it.contains("CREATE TABLE") && it.contains("keys") })
        assertTrue(sql.any { it.contains("CREATE TABLE") && it.contains("card_keys") })
        assertTrue(sql.any { it.contains("UNIQUE INDEX") && it.contains("keyHex") })
        assertTrue(sql.any { it.contains("index_keys_groupId") })
    }
}
