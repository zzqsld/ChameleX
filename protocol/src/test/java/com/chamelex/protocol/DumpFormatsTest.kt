package com.chamelex.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpFormatsTest {

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** 造一个 1K dump：块 i = 全 i，0 块为 4 字节 UID 布局。 */
    private fun makeDump1k(): Mf1DumpData {
        val blocks = ByteArray(1024) { (it / 16).toByte() }
        // 0 块：uid=DEADBEEF bcc sak=08 atqa=0400
        blocks[0] = 0xDE.toByte(); blocks[1] = 0xAD.toByte(); blocks[2] = 0xBE.toByte(); blocks[3] = 0xEF.toByte()
        blocks[4] = (0xDE xor 0xAD xor 0xBE xor 0xEF).toByte()
        blocks[5] = 0x08; blocks[6] = 0x04; blocks[7] = 0x00
        return Mf1DumpData(blocks = blocks, sak = 0x08)
    }

    @Test
    fun `bin round trip`() {
        val dump = makeDump1k()
        val bytes = serializeDump(DumpFormat.BIN, dump)
        assertEquals(1024, bytes.size)
        val parsed = parseDump("card.bin", bytes)
        assertArrayEquals(dump.blocks, parsed.blocks)
        assertEquals(MfcType.CLASSIC_1K, parsed.mfcType)
    }

    @Test
    fun `eml round trip`() {
        val dump = makeDump1k()
        val text = serializeEml(dump)
        assertEquals(64, text.lines().count { it.isNotBlank() })
        val parsed = parseDump("card.eml", text.encodeToByteArray())
        assertArrayEquals(dump.blocks, parsed.blocks)
        assertEquals(DumpFormat.EML, sniffDumpFormat(text.encodeToByteArray()))
    }

    @Test
    fun `mct round trip including sector headers`() {
        val dump = makeDump1k()
        val text = serializeMct(dump)
        assertTrue(text.contains("+Sector: 0"))
        assertTrue(text.contains("+Sector: 15"))
        val parsed = parseDump("card.mct", text.encodeToByteArray())
        assertArrayEquals(dump.blocks, parsed.blocks)
    }

    @Test
    fun `mct 4k sector mapping`() {
        // 4K：块 128 属于扇区 32（(32-24)*16=128）
        val dump = Mf1DumpData(blocks = ByteArray(4096) { (it / 16).toByte() })
        val text = serializeMct(dump)
        assertTrue(text.contains("+Sector: 32"))
        val parsed = parseMct(text)
        assertArrayEquals(dump.blocks, parsed.blocks)
        assertEquals(4096, parsed.blocks.size)
    }

    @Test
    fun `json round trip with card metadata`() {
        val dump = makeDump1k()
        val text = serializeDumpJson(dump)
        assertTrue(text.contains("\"FileType\": \"mfc v3\""))
        val parsed = parseDump("card.json", text.encodeToByteArray())
        assertArrayEquals(dump.blocks, parsed.blocks)
        assertArrayEquals("deadbeef".hexToBytes(), parsed.uid)
        assertArrayEquals("0400".hexToBytes(), parsed.atqa)
        assertEquals(0x08, parsed.sak)
    }

    @Test
    fun `json import accepts mfcard filetype and dash placeholders`() {
        val block0 = "DEADBEEF11080400" + "00".repeat(8)
        val text = """
            {"Created": "proxmark3", "FileType": "mfcard",
             "blocks": {"0": "$block0", "1": "--------------------------------"},
             "Card": {"UID": "DEADBEEF", "ATQA": "0400", "SAK": "08"}}
        """.trimIndent()
        val parsed = parseDumpJson(text)
        assertEquals(1024, parsed.blocks.size)
        assertTrue(parsed.isBlockReadable(0))
        assertFalse(parsed.isBlockReadable(1))
    }

    @Test
    fun `unreadable blocks export as dashes and reimport as unreadable`() {
        val blocks = ByteArray(1024)
        val mask = ByteArray(8) { 0xFF.toByte() }
        mask[0] = 0x7F // 块 0 不可读（MSB 位 0）
        val dump = Mf1DumpData(blocks = blocks, readableMask = mask)
        val mct = serializeMct(dump)
        assertTrue(mct.lines()[1] == "-".repeat(32))
        val parsed = parseMct(mct)
        assertFalse(parsed.isBlockReadable(0))
        assertTrue(parsed.isBlockReadable(1))
    }

    @Test
    fun `sniff detects json mct eml bin`() {
        assertEquals(DumpFormat.JSON, sniffDumpFormat("{}".encodeToByteArray()))
        assertEquals(DumpFormat.MCT, sniffDumpFormat("+Sector: 0\n".encodeToByteArray()))
        val eml = (0 until 64).joinToString("\n") { "00".repeat(16) }
        assertEquals(DumpFormat.EML, sniffDumpFormat(eml.encodeToByteArray()))
        assertEquals(DumpFormat.BIN, sniffDumpFormat(ByteArray(1024) { it.toByte() }))
    }

    @Test
    fun `eml parses address prefix lines`() {
        val eml = "00: " + "AA".repeat(16) + "\n" + "01: " + "BB".repeat(16)
        val parsed = parseEml(eml)
        assertEquals(0xAA.toByte(), parsed.blocks[0])
        assertEquals(0xBB.toByte(), parsed.blocks[16])
    }


    // ---- LF hex 文件解析 ----

    @Test
    fun `parseLfIdFile em410x 10 hex`() {
        val (type, id) = parseLfIdFile("0102030405")
        assertEquals(TagSpecificType.EM410X, type)
        assertArrayEquals("0102030405".hexToBytes(), id)
    }

    @Test
    fun `parseLfIdFile hidprox 26 hex with whitespace`() {
        val (type, id) = parseLfIdFile("01 0203 0405\n060708090a0b0c0d\r\n")
        assertEquals(TagSpecificType.HID_PROX, type)
        assertEquals(13, id.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseLfIdFile rejects wrong length`() {
        parseLfIdFile("0102")
    }

    @Test
    fun `looksLikeLfIdFile`() {
        assertTrue(looksLikeLfIdFile("0102030405"))
        assertTrue(looksLikeLfIdFile("  0102 0304 05\n"))
        assertFalse(looksLikeLfIdFile("0102"))
        assertFalse(looksLikeLfIdFile("zz0102030405"))
    }

    // ---- anticoll 0 块解析 ----

    @Test
    fun `anticoll 4-byte uid layout`() {
        // uid=DEADBEEF bcc=11 sak=08 atqa=0400
        val block0 = "DEADBEEF" + "11" + "08" + "0400" + "00".repeat(8)
        val ac = parseAntiCollFromBlock0(block0.hexToBytes())
        assertArrayEquals("deadbeef".hexToBytes(), ac.uid)
        assertEquals(0x08, ac.sak)
        assertArrayEquals("0400".hexToBytes(), ac.atqa)
    }

    @Test
    fun `anticoll 7-byte uid layout`() {
        // ct=88 uid0-2=010203 bcc1=88^01^02^03 uid3-6=04050607 bcc2 sak=18 atqa=0044
        val uid = "01020304050607".hexToBytes()
        val bcc1 = (0x88 xor 0x01 xor 0x02 xor 0x03)
        val bcc2 = mf1Bcc("04050607".hexToBytes())
        val block0 = "88" + "010203" + "%02X".format(bcc1) + "04050607" + "%02X".format(bcc2) + "18" + "0044" + "000000"
        val ac = parseAntiCollFromBlock0(block0.hexToBytes())
        assertArrayEquals(uid, ac.uid)
        assertEquals(0x18, ac.sak)
        assertArrayEquals("0044".hexToBytes(), ac.atqa)
    }

    @Test
    fun `mf1Bcc xor`() {
        assertEquals(0xDE xor 0xAD xor 0xBE xor 0xEF, mf1Bcc("deadbeef".hexToBytes()))
    }

    // ---- hf14a raw options ----

    @Test
    fun `hf14a raw options bitfield`() {
        assertEquals(0x12, Hf14aRawOptions(waitResponse = true, keepRfField = true).pack())
        assertEquals(0x3E, Hf14aRawOptions(
            waitResponse = true, appendCrc = true, autoSelect = true,
            keepRfField = true, checkResponseCrc = true,
        ).pack())
        assertEquals(0x0B, Hf14aRawOptions(
            activateRfField = true, waitResponse = true, autoSelect = true,
        ).pack())
    }
}
