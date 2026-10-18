package com.chamelex.protocol

/**
 * ChameleonUltra 数据帧编解码，与官方 CLI chameleon_com.py 行为一致。
 *
 * 帧格式（大端）：
 *   SOF(0x11) + LRC1 + CMD(u16) + STATUS(u16) + LEN(u16) + LRC2 + DATA + LRC3
 *   LRC1 = lrc(SOF)，恒为 0xEF
 *   LRC2 = lrc(前 8 字节)
 *   LRC3 = lrc(前面所有字节)
 * LRC 算法：累加取低 8 位后用 0x100 减再取低 8 位。
 */
object FrameCodec {

    const val SOF: Int = 0x11
    const val MAX_DATA_LENGTH: Int = 4096

    /** 帧头长度：SOF + LRC1 + CMD + STATUS + LEN + LRC2 */
    const val HEADER_LENGTH: Int = 9

    fun lrc(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var ret = 0
        for (i in offset until offset + length) {
            ret = (ret + (data[i].toInt() and 0xFF)) and 0xFF
        }
        return (0x100 - ret) and 0xFF
    }

    fun encode(cmd: Int, status: Int = 0, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= MAX_DATA_LENGTH) { "data length ${data.size} > $MAX_DATA_LENGTH" }
        val frame = ByteArray(HEADER_LENGTH + data.size + 1)
        frame[0] = SOF.toByte()
        frame[1] = 0 // lrc1 placeholder
        frame[2] = (cmd shr 8).toByte()
        frame[3] = cmd.toByte()
        frame[4] = (status shr 8).toByte()
        frame[5] = status.toByte()
        frame[6] = (data.size shr 8).toByte()
        frame[7] = data.size.toByte()
        frame[8] = 0 // lrc2 placeholder
        data.copyInto(frame, HEADER_LENGTH)
        frame[frame.size - 1] = 0 // lrc3 placeholder
        frame[1] = lrc(frame, 0, 1).toByte()
        frame[8] = lrc(frame, 0, 8).toByte()
        frame[frame.size - 1] = lrc(frame, 0, frame.size - 1).toByte()
        return frame
    }

    data class Frame(val cmd: Int, val status: Int, val data: ByteArray) {
        val statusEnum: Status? get() = Status.fromCode(status)
        override fun equals(other: Any?): Boolean =
            other is Frame && cmd == other.cmd && status == other.status && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * (31 * cmd + status) + data.contentHashCode()
    }

    /**
     * 逐字节重组的解码状态机，对应 chameleon_com.py 的 thread_data_receive。
     * USB 裸字节流与 BLE 分包通知都经此重组。
     */
    class Decoder {
        private var buffer = ByteArray(HEADER_LENGTH + MAX_DATA_LENGTH + 1)
        private var position = 0
        private var dataCmd = 0
        private var dataStatus = 0
        private var dataLength = 0

        fun reset() {
            position = 0
        }

        /** 喂入一个字节，凑齐一帧时返回该帧，否则返回 null。 */
        fun feed(byte: Byte): Frame? {
            if (position >= buffer.size) {
                // 理论上不会发生（dataLength 已限制在 MAX），防御性复位
                position = 0
            }
            buffer[position] = byte

            if (position < 2) { // SOF + LRC1
                if (position == 0) {
                    if (buffer[0].toInt() and 0xFF != SOF) {
                        position = 0
                        return null
                    }
                } else { // position == 1
                    if (buffer[1].toInt() and 0xFF != lrc(buffer, 0, 1)) {
                        position = 0
                        return null
                    }
                }
            } else if (position == 8) { // LRC2，帧头完成
                if (buffer[8].toInt() and 0xFF != lrc(buffer, 0, 8)) {
                    position = 0
                    return null
                }
                dataCmd = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
                dataStatus = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                dataLength = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
                if (dataLength > MAX_DATA_LENGTH) {
                    position = 0
                    return null
                }
            } else if (position > 8) { // DATA + LRC3
                if (position == HEADER_LENGTH + dataLength) {
                    // 最后一个字节 LRC3
                    val frame: Frame? = if ((byte.toInt() and 0xFF) == lrc(buffer, 0, position)) {
                        Frame(
                            cmd = dataCmd,
                            status = dataStatus,
                            data = buffer.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + dataLength)
                        )
                    } else {
                        null
                    }
                    position = 0
                    return frame
                }
            }
            position++
            return null
        }

        fun feed(bytes: ByteArray): List<Frame> {
            val frames = ArrayList<Frame>(1)
            for (b in bytes) {
                feed(b)?.let { frames.add(it) }
            }
            return frames
        }
    }
}
