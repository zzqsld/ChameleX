package com.chamelex.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCommandsTest {

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parseHf14aScan single mifare 1k tag`() {
        // uidlen=4 uid=DEADBEEF atqa=0400 sak=08 atslen=0
        val tags = parseHf14aScan("04deadbeef04000800".hexToBytes())
        assertEquals(1, tags.size)
        assertEquals("deadbeef", tags[0].uidHex)
        assertEquals("0400", tags[0].atqaHex)
        assertEquals(0x08, tags[0].sak)
        assertTrue(tags[0].ats.isEmpty())
        assertEquals(HfCardKind.MFC_1K, classifyHfTag(tags[0]))
    }

    @Test
    fun `parseHf14aScan multiple concatenated tags`() {
        // 卡1: uid4 DEADBEEF atqa=0400 sak=08 无ATS
        // 卡2: uid7 01020304050607 atqa=4400 sak=00 atslen=3 ats=112233
        val data = "04deadbeef04000800" + "0701020304050607" + "4400" + "00" + "03" + "112233"
        val tags = parseHf14aScan(data.hexToBytes())
        assertEquals(2, tags.size)
        assertEquals("deadbeef", tags[0].uidHex)
        assertEquals("01020304050607", tags[1].uidHex)
        assertEquals("112233", tags[1].atsHex)
        assertEquals(HfCardKind.MFC_1K, classifyHfTag(tags[0]))
        // sak=0 + atqa=4400 → Ultralight 系，但 ATS 非空优先判为 ISO14443-4
        assertEquals(HfCardKind.ISO14443_4_CPU, classifyHfTag(tags[1]))
    }

    @Test
    fun `classifyHfTag rules`() {
        fun tag(sak: Int, atqa: String = "0400", ats: String = "") =
            HfTagInfo("deadbeef".hexToBytes(), atqa.hexToBytes(), sak, ats.hexToBytes())

        assertEquals(HfCardKind.MFC_1K, classifyHfTag(tag(0x08)))
        assertEquals(HfCardKind.MFC_4K, classifyHfTag(tag(0x18)))
        assertEquals(HfCardKind.MFC_MINI, classifyHfTag(tag(0x09)))
        assertEquals(HfCardKind.ISO14443_4_CPU, classifyHfTag(tag(0x20)))
        assertEquals(HfCardKind.ISO14443_4_CPU, classifyHfTag(tag(0x00, ats = "067577810280")))
        assertEquals(HfCardKind.ULTRALIGHT_NTAG, classifyHfTag(tag(0x00, atqa = "4400")))
        assertEquals(HfCardKind.ULTRALIGHT_NTAG, classifyHfTag(tag(0x00, atqa = "0044")))
        assertEquals(HfCardKind.UNKNOWN_14A, classifyHfTag(tag(0x10)))
    }

    @Test
    fun `parseEm410xScan 5-byte uid`() {
        // type=EM410X(0x0064) uid=0102030405
        val tag = parseEm410xScan("00640102030405".hexToBytes())
        assertEquals(TagSpecificType.EM410X, tag.type)
        assertEquals("0102030405", tag.idHex)
    }

    @Test
    fun `parseEm410xScan electra 13-byte uid`() {
        // type=EM410X_ELECTRA(0x0068) uid=13 字节
        val tag = parseEm410xScan(("0068" + "0102030405060708090a0b0c0d").hexToBytes())
        assertEquals(TagSpecificType.EM410X_ELECTRA, tag.type)
        assertEquals(13, tag.id.size)
    }

    @Test
    fun `parseHidProxScan 13-byte response`() {
        // format=H10301(1) fc=123 cn_hi=0 cn_lo=456789 il=0 oem=0
        val data = byteArrayOf(1) +
                byteArrayOf(0, 0, 0, 123) +
                byteArrayOf(0) +
                byteArrayOf(0, 6, -8, 85) +   // 456789 = 0x0006F855
                byteArrayOf(0) +
                byteArrayOf(0, 0)
        val tag = parseHidProxScan(data)
        assertEquals(HIDFormat.H10301, tag.formatEnum)
        assertEquals(123L, tag.facilityCode)
        assertEquals(456789L, tag.cardNumber)
        assertEquals(0, tag.issueLevel)
        assertEquals(0, tag.oem)
        assertEquals(TagSpecificType.HID_PROX, tag.type)
    }

    @Test
    fun `parseHidProxScan large card number`() {
        // cn_hi=1 cn_lo=0 → cn = 2^32
        val data = byteArrayOf(26, 0, 0, 0, 1, 1, 0, 0, 0, 0, 2, 0, 5)
        val tag = parseHidProxScan(data)
        assertEquals(1L shl 32, tag.cardNumber)
        assertEquals(2, tag.issueLevel)
        assertEquals(5, tag.oem)
        assertEquals(HIDFormat.H10302, tag.formatEnum)
    }
}
