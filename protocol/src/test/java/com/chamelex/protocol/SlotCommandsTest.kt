package com.chamelex.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCommandsTest {

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parseSlotInfo parses 8 slots of hf lf u16 pairs`() {
        // 槽1: hf=MIFARE_1024(0x03E9) lf=EM410X(0x0064)；槽2: hf=NTAG_213(0x044C) lf=UNDEFINED；其余全 0
        val data =
            "03e90064" + "044c0000" + "00000000".repeat(6)
        val info = parseSlotInfo(data.hexToBytes())
        assertEquals(8, info.size)
        assertEquals(TagSpecificType.MIFARE_1024, info[0].first)
        assertEquals(TagSpecificType.EM410X, info[0].second)
        assertEquals(TagSpecificType.NTAG_213, info[1].first)
        assertEquals(TagSpecificType.UNDEFINED, info[1].second)
        assertEquals(TagSpecificType.UNDEFINED, info[7].first)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseSlotInfo rejects misaligned length`() {
        parseSlotInfo("03e900".hexToBytes())
    }

    @Test
    fun `parseEnabledSlots parses 8 hf lf bool pairs`() {
        // 槽1: hf=1 lf=0；槽2: hf=0 lf=1；槽3: 都启用；其余禁用
        val data = "0100" + "0001" + "0101" + "0000".repeat(5)
        val enabled = parseEnabledSlots(data.hexToBytes())
        assertEquals(8, enabled.size)
        assertEquals(true to false, enabled[0])
        assertEquals(false to true, enabled[1])
        assertEquals(true to true, enabled[2])
        assertEquals(false to false, enabled[7])
    }

    @Test
    fun `parseAllSlotNicks normal case`() {
        // 槽1: hf="HF1" lf="LF1"；槽2: hf="门卡"(UTF-8 6 字节) lf 空；槽3-8 空
        val slot1 = byteArrayOf(3) + "HF1".encodeToByteArray() + byteArrayOf(3) + "LF1".encodeToByteArray()
        val slot2 = byteArrayOf(6) + "门卡".encodeToByteArray() + byteArrayOf(0)
        val rest = ByteArray(6 * 2) // 槽3-8 各 2 个 0 长度字节
        val nicks = parseAllSlotNicks(slot1 + slot2 + rest)
        assertEquals(8, nicks.size)
        assertEquals(SlotNick("HF1", "LF1"), nicks[0])
        assertEquals(SlotNick("门卡", ""), nicks[1])
        assertEquals(SlotNick("", ""), nicks[7])
    }

    @Test
    fun `parseAllSlotNicks defensive on length overflow`() {
        // hf_len=10 但只剩 3 字节：与官方 CLI 一致，名字置空且指针照常前进（越过末尾后循环结束）
        val data = byteArrayOf(10, 0x41, 0x42, 0x43)
        val nicks = parseAllSlotNicks(data)
        assertEquals(1, nicks.size)
        assertEquals(SlotNick("", ""), nicks[0])
    }

    @Test
    fun `parseAllSlotNicks empty data`() {
        assertTrue(parseAllSlotNicks(ByteArray(0)).isEmpty())
    }

    @Test
    fun `parseEmulatorConfig parses 5-byte response`() {
        val config = parseEmulatorConfig("0100010103".hexToBytes())
        assertTrue(config.detection)
        assertFalse(config.gen1a)
        assertTrue(config.gen2)
        assertTrue(config.antiCollFromBlock0)
        assertEquals(MifareClassicWriteMode.SHADOW, config.writeMode)

        val all = parseEmulatorConfig("0101010102".hexToBytes())
        assertTrue(all.gen1a)
        assertEquals(MifareClassicWriteMode.DECEIVE, all.writeMode)

        val invalid = parseEmulatorConfig("00000000ff".hexToBytes())
        assertNull(invalid.writeMode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseEmulatorConfig rejects short response`() {
        parseEmulatorConfig("01000101".hexToBytes())
    }

    @Test
    fun `slot number fw conversion`() {
        assertEquals(0, SlotNumber.SLOT_1.toFw())
        assertEquals(7, SlotNumber.SLOT_8.toFw())
        assertEquals(SlotNumber.SLOT_8, SlotNumber.fromFw(7))
    }
}
