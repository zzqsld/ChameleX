package com.chamelex.protocol

/**
 * M1 侦测（Detection）指令封装（阶段 6）。
 * 侦测日志：读卡器对当前模拟槽发起认证的记录，用于 MFKey32 反推读卡器密钥。
 */
class DetectionCommands(private val protocol: ChameleonProtocol) {

    /** MF1_GET_DETECTION_COUNT=4005：响应 `!I` 记录条数。 */
    suspend fun getDetectionCount(): Int {
        val data = protocol.sendCommandExpect(Command.MF1_GET_DETECTION_COUNT).data
        require(data.size >= 4)
        return ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)
    }

    /** MF1_GET_DETECTION_LOG=4006：请求 `!I` 起始索引，响应 N×18 字节记录。 */
    suspend fun getDetectionLog(index: Int): List<DetectionRecord> {
        val data = byteArrayOf(
            (index shr 24).toByte(), (index shr 16).toByte(),
            (index shr 8).toByte(), index.toByte(),
        )
        return parseDetectionLog(protocol.sendCommandExpect(Command.MF1_GET_DETECTION_LOG, data).data)
    }

    /** 拉取全部侦测日志（按设备返回条数递增索引，与 CLI 一致）。 */
    suspend fun downloadAllLogs(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): List<DetectionRecord> {
        val total = getDetectionCount()
        if (total == 0) return emptyList()
        val out = mutableListOf<DetectionRecord>()
        var index = 0
        while (index < total) {
            val batch = getDetectionLog(index)
            if (batch.isEmpty()) break
            index += batch.size
            out += batch
            onProgress(out.size, total)
        }
        return out
    }
}

/**
 * 一条侦测记录（18 字节 `!BB4s4s4s4s`）：
 * block(1) + bitfield(1, bit0: 0=A/1=B, bit1: is_nested) + uid(4) + nt(4) + nr(4) + ar(4)。
 * uid 为模拟卡 UID；nt 是 tag challenge；nr/ar 是读卡器的加密 challenge/response。
 */
data class DetectionRecord(
    val block: Int,
    val isKeyB: Boolean,
    val isNested: Boolean,
    val uid: Long,
    val nt: Long,
    val nr: Long,
    val ar: Long,
) {
    val keyTypeLabel: String get() = if (isKeyB) "B" else "A"
}

const val DETECTION_RECORD_SIZE = 18

/** MF1_GET_DETECTION_LOG 响应解析（与 chameleon_cmd.py mf1_get_detection_log 一致）。 */
fun parseDetectionLog(data: ByteArray): List<DetectionRecord> {
    require(data.size % DETECTION_RECORD_SIZE == 0) {
        "侦测日志长度 ${data.size} 不是 $DETECTION_RECORD_SIZE 的倍数"
    }
    return (data.indices step DETECTION_RECORD_SIZE).map { i ->
        DetectionRecord(
            block = data[i].toInt() and 0xFF,
            isKeyB = data[i + 1].toInt() and 0x01 != 0,
            isNested = data[i + 1].toInt() and 0x02 != 0,
            uid = readU32BE(data, i + 2),
            nt = readU32BE(data, i + 6),
            nr = readU32BE(data, i + 10),
            ar = readU32BE(data, i + 14),
        )
    }
}

private fun readU32BE(data: ByteArray, offset: Int): Long =
    ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
