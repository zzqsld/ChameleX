package com.chamelex.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 期望值均由官方 CLI chameleon_com.py 的 lrc_calc / make_data_frame_bytes 实际运行生成。
 */
class FrameCodecTest {

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `lrc matches python implementation`() {
        assertEquals(0xEF, FrameCodec.lrc(byteArrayOf(0x11)))
        assertEquals(0x15, FrameCodec.lrc("11ef03e800000000".hexToBytes()))
    }

    @Test
    fun `encode no-data command matches python`() {
        // GET_APP_VERSION=1000, 无数据
        assertEquals("11ef03e8000000001500", FrameCodec.encode(1000).toHex())
    }

    @Test
    fun `encode one-byte command matches python`() {
        // CHANGE_DEVICE_MODE=1001, data=01
        assertEquals("11ef03e9000000011301ff", FrameCodec.encode(1001, data = byteArrayOf(0x01)).toHex())
    }

    @Test
    fun `encode multi-byte command matches python`() {
        // SET_SLOT_TAG_NICK=1007, data=00 02 48 46
        assertEquals(
            "11ef03ef000000040a0002484670",
            FrameCodec.encode(1007, data = "00024846".hexToBytes()).toHex()
        )
    }

    @Test
    fun `decode response frame byte by byte`() {
        // GET_APP_VERSION 响应：status=SUCCESS(0x68), data=02 05
        val raw = "11ef03e800680002ab0205f9".hexToBytes()
        val decoder = FrameCodec.Decoder()
        var frame: FrameCodec.Frame? = null
        for (b in raw) {
            decoder.feed(b)?.let { frame = it }
        }
        val f = frame!!
        assertEquals(1000, f.cmd)
        assertEquals(0x68, f.status)
        assertArrayEquals(byteArrayOf(0x02, 0x05), f.data)
        assertEquals(Status.SUCCESS, f.statusEnum)
    }

    @Test
    fun `decode utf8 string response in one chunk`() {
        // GET_GIT_VERSION=1017 响应："v2.0.0-test"
        val raw = "11ef03f90068000b9176322e302e302d74657374af".hexToBytes()
        val frames = FrameCodec.Decoder().feed(raw)
        assertEquals(1, frames.size)
        assertEquals(1017, frames[0].cmd)
        assertEquals("v2.0.0-test", frames[0].data.decodeToString())
    }

    @Test
    fun `decode resyncs after garbage prefix`() {
        // 混入噪声字节与 LRC 校验失败的假 SOF 头，解码器复位后应能对齐到真帧
        val garbage = "00ff11001199".hexToBytes()
        val raw = garbage + "11ef03f3006800089a0102030405060708dc".hexToBytes()
        val frames = FrameCodec.Decoder().feed(raw)
        assertEquals(1, frames.size)
        assertEquals(1011, frames[0].cmd)
        assertArrayEquals("0102030405060708".hexToBytes(), frames[0].data)
    }

    @Test
    fun `decode multiple frames split across chunks`() {
        val f1 = FrameCodec.encode(1000)
        val f2 = "11ef03e800680002ab0205f9".hexToBytes()
        val stream = f1 + f2
        val decoder = FrameCodec.Decoder()
        val frames = mutableListOf<FrameCodec.Frame>()
        // 以 3 字节为一片模拟 BLE 分包
        stream.toList().chunked(3).forEach { chunk ->
            frames.addAll(decoder.feed(chunk.toByteArray()))
        }
        assertEquals(2, frames.size)
        assertEquals(1000, frames[0].cmd)
        assertEquals(0, frames[0].status)
        assertTrue(frames[0].data.isEmpty())
        assertEquals(0x68, frames[1].status)
    }

    @Test
    fun `decode rejects corrupted lrc3`() {
        val raw = "11ef03e800680002ab0205f9".hexToBytes()
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte()
        val frames = FrameCodec.Decoder().feed(raw)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `decode rejects oversized data length`() {
        // LEN=4097 超过上限，LRC2 修正为合法值(0x04)，仍应被拒绝且后续帧能正常对齐
        val bad = "11ef03e8000010010400".hexToBytes()
        val good = "11ef03e8000000001500".hexToBytes()
        val frames = FrameCodec.Decoder().feed(bad + good)
        assertEquals(1, frames.size)
        assertEquals(1000, frames[0].cmd)
    }

    @Test
    fun `encode-decode roundtrip`() {
        val data = ByteArray(300) { it.toByte() }
        val raw = FrameCodec.encode(2015, status = 0, data = data)
        val frames = FrameCodec.Decoder().feed(raw)
        assertEquals(1, frames.size)
        assertEquals(2015, frames[0].cmd)
        assertArrayEquals(data, frames[0].data)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
