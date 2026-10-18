package com.chamelex.protocol

/**
 * M1 破解采集指令封装（阶段 3c）。成功状态码均为 HF_TAG_OK。
 */
class CrackCommands(private val protocol: ChameleonProtocol) {

    /** MF1_DETECT_PRNG=2002：响应 1 字节（0=Static/1=Weak/2=Hard）。 */
    suspend fun mf1DetectPrng(): MifareClassicPrngType? {
        val resp = protocol.sendCommand(Command.MF1_DETECT_PRNG)
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> MifareClassicPrngType.fromCode(resp.data[0].toInt() and 0xFF)
            Status.HF_TAG_NO -> throw CardLostException()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_DETECT_PRNG.code)
            else -> throw DeviceStatusException(Command.MF1_DETECT_PRNG.code, resp.status)
        }
    }

    /**
     * MF1_DARKSIDE_ACQUIRE=2004：请求 `!BBBB` (type_target, block_target, first_recover, sync_max)。
     * 成功响应 `!BIIQQII` = (status, uid, nt1, par, ks1, nr, ar)。超时 = sync_max×10 秒。
     */
    suspend fun mf1DarksideAcquire(
        typeTarget: MfcKeyType,
        blockTarget: Int,
        firstRecover: Boolean,
        syncMax: Int,
    ): DarksideResult {
        val resp = protocol.sendCommand(
            Command.MF1_DARKSIDE_ACQUIRE,
            byteArrayOf(typeTarget.code.toByte(), blockTarget.toByte(), if (firstRecover) 1 else 0, syncMax.toByte()),
            timeoutMs = syncMax * 10_000L,
        )
        when (resp.statusEnum) {
            Status.HF_TAG_OK -> return parseDarksideAcquire(resp.data)
            Status.HF_TAG_NO -> throw CardLostException()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_DARKSIDE_ACQUIRE.code)
            else -> throw DeviceStatusException(Command.MF1_DARKSIDE_ACQUIRE.code, resp.status)
        }
    }

    /** MF1_DETECT_NT_DIST=2005：请求 `!BB6s` (type_known, block_known, key_known)，响应 `!II` (uid, dist)。 */
    suspend fun mf1DetectNtDist(typeKnown: MfcKeyType, blockKnown: Int, keyKnown: ByteArray): Pair<Long, Long> {
        require(keyKnown.size == 6) { "密钥必须是 6 字节" }
        val resp = protocol.sendCommand(
            Command.MF1_DETECT_NT_DIST,
            byteArrayOf(typeKnown.code.toByte(), blockKnown.toByte()) + keyKnown,
        )
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> parseNtDist(resp.data)
            Status.HF_TAG_NO -> throw CardLostException()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_DETECT_NT_DIST.code)
            else -> throw DeviceStatusException(Command.MF1_DETECT_NT_DIST.code, resp.status)
        }
    }

    /** MF1_NESTED_ACQUIRE=2006：请求 `!BB6sBB`，响应 N×`!IIB` (nt, nt_enc, par)。 */
    suspend fun mf1NestedAcquire(
        typeKnown: MfcKeyType,
        blockKnown: Int,
        keyKnown: ByteArray,
        typeTarget: MfcKeyType,
        blockTarget: Int,
    ): List<NestedNonce> {
        val resp = nestedAcquire(Command.MF1_NESTED_ACQUIRE, typeKnown, blockKnown, keyKnown, typeTarget, blockTarget)
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> parseNestedAcquire(resp.data)
            Status.HF_TAG_NO -> throw CardLostException()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_NESTED_ACQUIRE.code)
            else -> throw DeviceStatusException(Command.MF1_NESTED_ACQUIRE.code, resp.status)
        }
    }

    /** MF1_STATIC_NESTED_ACQUIRE=2003：请求同 2006，响应 uid(u32) + N×`!II` (nt, nt_enc)。 */
    suspend fun mf1StaticNestedAcquire(
        typeKnown: MfcKeyType,
        blockKnown: Int,
        keyKnown: ByteArray,
        typeTarget: MfcKeyType,
        blockTarget: Int,
    ): StaticNestedResult {
        val resp = nestedAcquire(Command.MF1_STATIC_NESTED_ACQUIRE, typeKnown, blockKnown, keyKnown, typeTarget, blockTarget)
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> parseStaticNestedAcquire(resp.data)
            Status.HF_TAG_NO -> throw CardLostException()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_STATIC_NESTED_ACQUIRE.code)
            else -> throw DeviceStatusException(Command.MF1_STATIC_NESTED_ACQUIRE.code, resp.status)
        }
    }

    /** MF1_HARDNESTED_ACQUIRE=2013：请求 `!BBB6sBB` (slow, type_known, block_known, key, type_target, block_target)，响应裸 nonce 字节。 */
    suspend fun mf1HardNestedAcquire(
        slow: Boolean,
        typeKnown: MfcKeyType,
        blockKnown: Int,
        keyKnown: ByteArray,
        typeTarget: MfcKeyType,
        blockTarget: Int,
    ): ByteArray {
        require(keyKnown.size == 6) { "密钥必须是 6 字节" }
        val resp = protocol.sendCommand(
            Command.MF1_HARDNESTED_ACQUIRE,
            byteArrayOf(
                if (slow) 1 else 0,
                typeKnown.code.toByte(), blockKnown.toByte(),
            ) + keyKnown + byteArrayOf(typeTarget.code.toByte(), blockTarget.toByte()),
            timeoutMs = 30_000,
        )
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> resp.data
            Status.HF_TAG_NO -> throw CardLostException()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_HARDNESTED_ACQUIRE.code)
            else -> throw DeviceStatusException(Command.MF1_HARDNESTED_ACQUIRE.code, resp.status)
        }
    }

    private suspend fun nestedAcquire(
        cmd: Command,
        typeKnown: MfcKeyType,
        blockKnown: Int,
        keyKnown: ByteArray,
        typeTarget: MfcKeyType,
        blockTarget: Int,
    ): Response {
        require(keyKnown.size == 6) { "密钥必须是 6 字节" }
        return protocol.sendCommand(
            cmd,
            byteArrayOf(typeKnown.code.toByte(), blockKnown.toByte()) + keyKnown +
                    byteArrayOf(typeTarget.code.toByte(), blockTarget.toByte()),
        )
    }
}

/** Darkside 单次采集结果（u32 均以无符号 Long 表示）。 */
data class DarksideAcquisition(
    val uid: Long,
    val nt1: Long,
    val par: Long,
    val ks1: Long,
    val nr: Long,
    val ar: Long,
)

/** Darkside 采集响应：status 非 OK 时 acquisition 为 null。 */
data class DarksideResult(
    val status: MifareClassicDarksideStatus?,
    val acquisition: DarksideAcquisition?,
)

/** Nested 采集的一个 (nt, nt_enc, par) 三元组。 */
data class NestedNonce(val nt: Long, val ntEnc: Long, val par: Int)

/** StaticNested 采集响应。 */
data class StaticNestedResult(val uid: Long, val nts: List<Pair<Long, Long>>)

// ---- 纯解析函数（可单测） ----

private fun readU32BE(data: ByteArray, offset: Int): Long =
    ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

private fun readU64BE(data: ByteArray, offset: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
    return v
}

/** MF1_DARKSIDE_ACQUIRE 响应 `!BIIQQII`。 */
fun parseDarksideAcquire(data: ByteArray): DarksideResult {
    require(data.isNotEmpty()) { "DARKSIDE 响应为空" }
    val status = MifareClassicDarksideStatus.fromCode(data[0].toInt() and 0xFF)
    if (status != MifareClassicDarksideStatus.OK) return DarksideResult(status, null)
    require(data.size >= 33) { "DARKSIDE 响应长度 ${data.size} < 33" }
    return DarksideResult(
        status,
        DarksideAcquisition(
            uid = readU32BE(data, 1),
            nt1 = readU32BE(data, 5),
            par = readU64BE(data, 9),
            ks1 = readU64BE(data, 17),
            nr = readU32BE(data, 25),
            ar = readU32BE(data, 29),
        ),
    )
}

/** MF1_DETECT_NT_DIST 响应 `!II` (uid, dist)。 */
fun parseNtDist(data: ByteArray): Pair<Long, Long> {
    require(data.size >= 8) { "NT_DIST 响应长度 ${data.size} < 8" }
    return readU32BE(data, 0) to readU32BE(data, 4)
}

/** MF1_NESTED_ACQUIRE 响应 N×`!IIB`。 */
fun parseNestedAcquire(data: ByteArray): List<NestedNonce> {
    require(data.size % 9 == 0) { "NESTED 响应长度 ${data.size} 不是 9 的倍数" }
    return (data.indices step 9).map { i ->
        NestedNonce(
            nt = readU32BE(data, i),
            ntEnc = readU32BE(data, i + 4),
            par = data[i + 8].toInt() and 0xFF,
        )
    }
}

/** MF1_STATIC_NESTED_ACQUIRE 响应 uid(u32) + N×`!II`。 */
fun parseStaticNestedAcquire(data: ByteArray): StaticNestedResult {
    require(data.size >= 4 && (data.size - 4) % 8 == 0) { "STATIC_NESTED 响应长度异常 ${data.size}" }
    return StaticNestedResult(
        uid = readU32BE(data, 0),
        nts = (4 until data.size step 8).map { i -> readU32BE(data, i) to readU32BE(data, i + 4) },
    )
}

/** HardNested  nonce 对数（9 字节/对）。 */
fun hardNestedNoncePairs(data: ByteArray): Int = data.size / 9
