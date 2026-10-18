package com.chamelex.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrackCommandsTest {

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parseDarksideAcquire ok status parses all fields`() {
        // status=0 uid=11223344 nt1=AABBCCDD par=0102030405060708 ks1=1112131415161718 nr=99887766 ar=55443322
        val data = "00" + "11223344" + "aabbccdd" + "0102030405060708" + "1112131415161718" + "99887766" + "55443322"
        val result = parseDarksideAcquire(data.hexToBytes())
        assertEquals(MifareClassicDarksideStatus.OK, result.status)
        val acq = result.acquisition!!
        assertEquals(0x11223344L, acq.uid)
        assertEquals(0xAABBCCDDL, acq.nt1)
        assertEquals(0x0102030405060708L, acq.par)
        assertEquals(0x1112131415161718L, acq.ks1)
        assertEquals(0x99887766L, acq.nr)
        assertEquals(0x55443322L, acq.ar)
    }

    @Test
    fun `parseDarksideAcquire non-ok status has no acquisition`() {
        val result = parseDarksideAcquire("02".hexToBytes())
        assertEquals(MifareClassicDarksideStatus.LUCKY_AUTH_OK, result.status)
        assertNull(result.acquisition)
    }

    @Test
    fun `parseNtDist`() {
        val (uid, dist) = parseNtDist("11223344000000a0".hexToBytes())
        assertEquals(0x11223344L, uid)
        assertEquals(0xA0L, dist)
    }

    @Test
    fun `parseNestedAcquire multiple nonces`() {
        val data = "000000010000000207" + "000000030000000400"
        val nonces = parseNestedAcquire(data.hexToBytes())
        assertEquals(2, nonces.size)
        assertEquals(NestedNonce(1, 2, 7), nonces[0])
        assertEquals(NestedNonce(3, 4, 0), nonces[1])
    }

    @Test
    fun `parseStaticNestedAcquire`() {
        val data = "11223344" + "0000000100000002" + "0000000300000004"
        val result = parseStaticNestedAcquire(data.hexToBytes())
        assertEquals(0x11223344L, result.uid)
        assertEquals(listOf(1L to 2L, 3L to 4L), result.nts)
    }

    @Test
    fun `hardNestedNoncePairs`() {
        assertEquals(3, hardNestedNoncePairs(ByteArray(27)))
        assertTrue(hardNestedNoncePairs(ByteArray(0)) == 0)
    }
}
