package com.chamelex.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolLoggerTest {

    @Test
    fun `ring buffer trims to capacity`() {
        ProtocolLogger.clear()
        ProtocolLogger.enabled = true
        repeat(ProtocolLogger.CAPACITY + 10) { i ->
            ProtocolLogger.log(tx = true, cmd = 1000, status = null, dataLen = i)
        }
        val snapshot = ProtocolLogger.snapshot()
        assertEquals(ProtocolLogger.CAPACITY, snapshot.size)
        // 最旧的一条应是第 10 条（0-9 被挤出）
        assertEquals(10, snapshot.first().dataLen)
        assertEquals(ProtocolLogger.CAPACITY + 9, snapshot.last().dataLen)
    }

    @Test
    fun `disabled logger drops entries`() {
        ProtocolLogger.clear()
        ProtocolLogger.enabled = false
        ProtocolLogger.log(true, 1000, null, 0)
        assertTrue(ProtocolLogger.snapshot().isEmpty())
        ProtocolLogger.enabled = true
    }

    @Test
    fun `format line contains direction cmd name status and len`() {
        val line = ProtocolLogger.formatLine(
            ProtocolLogger.Entry(0L, false, 1000, 0x68, 2)
        )
        assertTrue(line.contains("RX"))
        assertTrue(line.contains("CMD=1000(GET_APP_VERSION)"))
        assertTrue(line.contains("STATUS=0x68(SUCCESS)"))
        assertTrue(line.contains("LEN=2"))
        val txLine = ProtocolLogger.formatLine(ProtocolLogger.Entry(0L, true, 1001, null, 1))
        assertTrue(txLine.contains("TX"))
        assertFalse(txLine.contains("STATUS"))
    }
}
