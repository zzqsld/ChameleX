package com.chamelex.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionCommandsTest {

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parseDetectionLog single record all fields`() {
        // block=03, bitfield=0x03(B+nested), uid=DEADBEEF, nt=01234567, nr=2BCFE9CD, ar=3B481254
        val data = "03" + "03" + "deadbeef" + "01234567" + "2bcfe9cd" + "3b481254"
        val records = parseDetectionLog(data.hexToBytes())
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals(3, r.block)
        assertTrue(r.isKeyB)
        assertTrue(r.isNested)
        assertEquals("B", r.keyTypeLabel)
        assertEquals(0xDEADBEEFL, r.uid)
        assertEquals(0x01234567L, r.nt)
        assertEquals(0x2BCFE9CDL, r.nr)
        assertEquals(0x3B481254L, r.ar)
    }

    @Test
    fun `parseDetectionLog multiple records`() {
        val rec1 = "00" + "00" + "11223344" + "a1b2c3d4" + "66d7ad51" + "67ab3173"
        val rec2 = "07" + "01" + "11223344" + "13579bdf" + "db571a3b" + "b4a57ea5"
        val records = parseDetectionLog((rec1 + rec2).hexToBytes())
        assertEquals(2, records.size)
        assertFalse(records[0].isKeyB)
        assertEquals("A", records[0].keyTypeLabel)
        assertEquals(7, records[1].block)
        assertTrue(records[1].isKeyB)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseDetectionLog rejects misaligned data`() {
        parseDetectionLog("0300deadbeef".hexToBytes())
    }

    @Test
    fun `mfkey32 test vectors are self consistent`() {
        // 与 on-device native 测试相同的向量（官方 crypto1.py 生成并自证），
        // 这里验证 protocol 解析出的字段与向量一致（native 反推在真机验证）
        val rec1 = "03" + "00" + "deadbeef" + "01234567" + "2bcfe9cd" + "3b481254"
        val rec2 = "03" + "00" + "deadbeef" + "89abcdef" + "2fdbadf9" + "08bf913c"
        val records = parseDetectionLog((rec1 + rec2).hexToBytes())
        assertEquals(2, records.size)
        assertEquals(records[0].uid, records[1].uid)
        assertTrue(records[0].nt != records[1].nt)
    }
}
