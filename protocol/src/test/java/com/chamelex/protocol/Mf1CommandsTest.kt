package com.chamelex.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mf1CommandsTest {

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---- 掩码位操作 ----

    @Test
    fun `mask bit mapping is MSB first`() {
        val mask = ByteArray(10)
        // 位 0 在字节 0 的最高位；位 7 在字节 0 的最低位；位 8 在字节 1 的最高位
        maskSet(mask, 0)
        assertEquals(0x80.toByte(), mask[0])
        maskSet(mask, 7)
        assertEquals(0x81.toByte(), mask[0])
        maskSet(mask, 8)
        assertEquals(0x80.toByte(), mask[1])
        assertTrue(maskGet(mask, 0))
        assertTrue(maskGet(mask, 7))
        assertTrue(maskGet(mask, 8))
        assertFalse(maskGet(mask, 1))
    }

    @Test
    fun `mask semantics sector and key type`() {
        // 位 k → 扇区 k/2，k%2==0→KeyA，1→KeyB
        val mask = ByteArray(10)
        maskSet(mask, 3) // 扇区 1 KeyB
        maskSet(mask, 4) // 扇区 2 KeyA
        assertEquals(2, maskCountClear(mask).let { 80 - it })
        assertTrue(maskGet(mask, 3))
        assertTrue(maskGet(mask, 4))
    }

    @Test
    fun `maskOr and countClear and allSet`() {
        val mask = ByteArray(10)
        assertEquals(80, maskCountClear(mask))
        assertFalse(maskAllSet(mask))

        val found = ByteArray(10)
        maskSet(found, 0)
        maskSet(found, 79)
        maskOr(mask, found)
        assertEquals(78, maskCountClear(mask))
        assertTrue(maskGet(mask, 79))

        maskOr(mask, ByteArray(10) { 0xFF.toByte() })
        assertTrue(maskAllSet(mask))
    }

    // ---- CHECK_KEYS 响应解析 ----

    @Test
    fun `parseCheckKeysResponse found bits and key offsets`() {
        val found = ByteArray(10)
        maskSet(found, 0)  // 扇区 0 KeyA 命中
        maskSet(found, 5)  // 扇区 2 KeyB 命中
        maskSet(found, 79) // 扇区 39 KeyB 命中
        val keyArea = ByteArray(480) { 0 }
        val key0 = "a0a1a2a3a4a5".hexToBytes()
        val key5 = "b0b1b2b3b4b5".hexToBytes()
        val key79 = "ffffffffffff".hexToBytes()
        key0.copyInto(keyArea, 0)
        key5.copyInto(keyArea, 5 * 6)
        key79.copyInto(keyArea, 79 * 6)

        val result = parseCheckKeysResponse(found + keyArea)
        assertTrue(result.isFound(0))
        assertTrue(result.isFound(5))
        assertTrue(result.isFound(79))
        assertFalse(result.isFound(1))
        assertArrayEquals(key0, result.keyAt(0))
        assertArrayEquals(key5, result.keyAt(5))
        assertArrayEquals(key79, result.keyAt(79))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCheckKeysResponse rejects short response`() {
        parseCheckKeysResponse(ByteArray(489))
    }

    // ---- M1 卡结构 ----

    @Test
    fun `mfc card layouts`() {
        assertEquals(64, MfcType.CLASSIC_1K.blockCount)
        assertEquals(256, MfcType.CLASSIC_4K.blockCount)
        assertEquals(20, MfcType.MINI.blockCount)
        assertEquals(MfcType.CLASSIC_1K, MfcType.fromSak(0x08))
        assertEquals(MfcType.CLASSIC_4K, MfcType.fromSak(0x18))
        assertEquals(MfcType.MINI, MfcType.fromSak(0x09))

        // 4K 块布局：扇区 31 → 块 124..127；扇区 32 → 块 128..143
        assertEquals(4, mfcBlocksInSector(31))
        assertEquals(16, mfcBlocksInSector(32))
        assertEquals(124, mfcFirstBlockOfSector(31))
        assertEquals(128, mfcFirstBlockOfSector(32))
        assertEquals(128 + 7 * 16, mfcFirstBlockOfSector(39))
        assertEquals(0, mfcFirstBlockOfSector(0))
    }


    @Test
    fun `createCheckMask presets bits beyond card sectors`() {
        // 1K 卡 16 扇区 = 32 个密钥位；位 32..79 应预置 1
        val mask = createCheckMask(16)
        assertEquals(32, maskCountClear(mask))
        assertFalse(maskAllSet(mask))
        // 32 个密钥位全部命中后应判定为全齐（秒开条件）
        for (k in 0 until 32) maskSet(mask, k)
        assertTrue(maskAllSet(mask))

        // 4K 卡 40 扇区 = 80 位，无预置位
        val mask4k = createCheckMask(40)
        assertEquals(80, maskCountClear(mask4k))
    }

    // ---- 字典解析 ----
    @Test
    fun `parseKeyDictionary trims validates and dedupes`() {
        val text = """
            # 注释行
            
            FFFFFFFFFFFF
              a0a1a2a3a4a5  
            ffffffffffff
            GHIJKL123456
            1234
            #另一个注释
            000000000001
        """.trimIndent()
        val keys = parseKeyDictionary(text)
        assertEquals(3, keys.size)
        assertArrayEquals("ffffffffffff".hexToBytes(), keys[0])
        assertArrayEquals("a0a1a2a3a4a5".hexToBytes(), keys[1])
        assertArrayEquals("000000000001".hexToBytes(), keys[2])
    }
}
