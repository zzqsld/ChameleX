package com.chamelex.protocol

/** HF 卡扫描结果。 */
data class HfTagInfo(
    val uid: ByteArray,
    val atqa: ByteArray,
    val sak: Int,
    val ats: ByteArray,
) {
    val uidHex: String get() = uid.toHex()
    val atqaHex: String get() = atqa.toHex()
    val atsHex: String get() = ats.toHex()
    override fun equals(other: Any?): Boolean =
        other is HfTagInfo && uid.contentEquals(other.uid) && atqa.contentEquals(other.atqa) &&
                sak == other.sak && ats.contentEquals(other.ats)
    override fun hashCode(): Int = uid.contentHashCode()
}

/** LF 卡扫描结果。 */
sealed interface LfTagInfo {
    val type: TagSpecificType

    /** EM410X 系：id 5 字节（EM410X_ELECTRA 为 13 字节）。 */
    data class Em410x(override val type: TagSpecificType, val id: ByteArray) : LfTagInfo {
        val idHex: String get() = id.toHex()
        override fun equals(other: Any?): Boolean =
            other is Em410x && type == other.type && id.contentEquals(other.id)
        override fun hashCode(): Int = 31 * type.hashCode() + id.contentHashCode()
    }

    /** HID Prox：卡号 = (cnHigh shl 32) + cnLow。 */
    data class HidProx(
        val format: Int,
        val facilityCode: Long,
        val cardNumber: Long,
        val issueLevel: Int,
        val oem: Int,
    ) : LfTagInfo {
        override val type: TagSpecificType get() = TagSpecificType.HID_PROX
        val formatEnum: HIDFormat? get() = HIDFormat.fromCode(format)
    }
}

/** HF 卡型判断结果。 */
enum class HfCardKind {
    /** MIFARE Classic 1K（支持自动解密） */
    MFC_1K,
    /** MIFARE Classic 4K（支持自动解密） */
    MFC_4K,
    /** MIFARE Classic Mini（支持自动解密） */
    MFC_MINI,
    /** Ultralight/NTAG 系（不支持自动解密，支持模拟） */
    ULTRALIGHT_NTAG,
    /** ISO14443-4 / CPU 卡（不支持自动解密） */
    ISO14443_4_CPU,
    /** 其他 14A 卡 */
    UNKNOWN_14A,
}

/**
 * 卡型判断（阶段 3a 规则）：
 * SAK=0x08→1K，0x18→4K，0x09→Mini；SAK=0x20 或有 ATS→ISO14443-4/CPU 卡；
 * SAK=0x00 且 ATQA=0x0044→Ultralight/NTAG 系；其他→未知 14A。
 */
fun classifyHfTag(tag: HfTagInfo): HfCardKind {
    val atqaValue = if (tag.atqa.size >= 2) {
        ((tag.atqa[0].toInt() and 0xFF) shl 8) or (tag.atqa[1].toInt() and 0xFF)
    } else -1
    return when {
        tag.sak == 0x08 -> HfCardKind.MFC_1K
        tag.sak == 0x18 -> HfCardKind.MFC_4K
        tag.sak == 0x09 -> HfCardKind.MFC_MINI
        tag.sak == 0x20 || tag.ats.isNotEmpty() -> HfCardKind.ISO14443_4_CPU
        // ATQA 在线上为小端字节序，两种排布都接受
        tag.sak == 0x00 && (atqaValue == 0x0044 || atqaValue == 0x4400) -> HfCardKind.ULTRALIGHT_NTAG
        else -> HfCardKind.UNKNOWN_14A
    }
}

/**
 * 读卡器指令封装（阶段 3a）。
 * 注意：扫描指令的成功状态码是 HF_TAG_OK(0x00)/LF_TAG_OK(0x40)，不是 SUCCESS。
 */
class ReaderCommands(private val protocol: ChameleonProtocol) {

    /** GET_DEVICE_MODE=1002：true=读卡器模式。 */
    suspend fun isDeviceReaderMode(): Boolean =
        protocol.sendCommandExpect(Command.GET_DEVICE_MODE).data[0].toInt() != 0

    /**
     * CHANGE_DEVICE_MODE=1001：`!B`，true=读卡器模式。
     * Lite 设备会返回 NOT_IMPLEMENTED（抛 [DeviceStatusException]）。
     */
    suspend fun changeDeviceMode(readerMode: Boolean) {
        protocol.sendCommandExpect(Command.CHANGE_DEVICE_MODE, byteArrayOf(if (readerMode) 1 else 0))
    }

    /**
     * HF14A_SCAN=2000：返回场上的卡列表，无卡返回空列表。
     */
    suspend fun hf14aScan(timeoutMs: Long = 5000): List<HfTagInfo> {
        val resp = protocol.sendCommand(Command.HF14A_SCAN, timeoutMs = timeoutMs)
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> parseHf14aScan(resp.data)
            Status.HF_TAG_NO -> emptyList()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.HF14A_SCAN.code)
            else -> throw DeviceStatusException(Command.HF14A_SCAN.code, resp.status)
        }
    }

    /** EM410X_SCAN=3000：无卡返回 null。 */
    suspend fun em410xScan(timeoutMs: Long = 5000): LfTagInfo.Em410x? {
        val resp = protocol.sendCommand(Command.EM410X_SCAN, timeoutMs = timeoutMs)
        return when (resp.statusEnum) {
            Status.LF_TAG_OK -> parseEm410xScan(resp.data)
            Status.LF_TAG_NO_FOUND -> null
            Status.INVALID_CMD -> throw InvalidCommandException(Command.EM410X_SCAN.code)
            else -> throw DeviceStatusException(Command.EM410X_SCAN.code, resp.status)
        }
    }

    /**
     * HIDPROX_SCAN=3002：请求 `!B` format，0=自动识别（与官方 CLI 默认一致）。无卡返回 null。
     */
    suspend fun hidProxScan(format: Int = 0, timeoutMs: Long = 5000): LfTagInfo.HidProx? {
        val resp = protocol.sendCommand(Command.HIDPROX_SCAN, byteArrayOf(format.toByte()), timeoutMs)
        return when (resp.statusEnum) {
            Status.LF_TAG_OK -> parseHidProxScan(resp.data)
            Status.LF_TAG_NO_FOUND -> null
            Status.INVALID_CMD -> throw InvalidCommandException(Command.HIDPROX_SCAN.code)
            else -> throw DeviceStatusException(Command.HIDPROX_SCAN.code, resp.status)
        }
    }
}

// ---- 纯解析函数（可单测） ----

/** HF14A_SCAN 响应：若干卡串联 `uidlen|uid|atqa[2]|sak|atslen|ats`。 */
fun parseHf14aScan(data: ByteArray): List<HfTagInfo> {
    val tags = mutableListOf<HfTagInfo>()
    var offset = 0
    while (offset < data.size) {
        val uidLen = data[offset].toInt() and 0xFF
        require(offset + 1 + uidLen + 4 <= data.size) { "HF14A_SCAN 响应数据越界" }
        val uid = data.copyOfRange(offset + 1, offset + 1 + uidLen)
        val p = offset + 1 + uidLen
        val atqa = data.copyOfRange(p, p + 2)
        val sak = data[p + 2].toInt() and 0xFF
        val atsLen = data[p + 3].toInt() and 0xFF
        require(p + 4 + atsLen <= data.size) { "HF14A_SCAN 响应 ATS 越界" }
        val ats = data.copyOfRange(p + 4, p + 4 + atsLen)
        tags.add(HfTagInfo(uid, atqa, sak, ats))
        offset = p + 4 + atsLen
    }
    return tags
}

/** EM410X_SCAN 响应：`!H` tag_type + uid（ELECTRA 13 字节，其他 5 字节）。 */
fun parseEm410xScan(data: ByteArray): LfTagInfo.Em410x {
    require(data.size >= 2) { "EM410X_SCAN 响应过短" }
    val type = TagSpecificType.fromCode(((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF))
    val uidLen = if (type == TagSpecificType.EM410X_ELECTRA) 13 else 5
    require(data.size >= 2 + uidLen) { "EM410X_SCAN 响应 UID 越界" }
    return LfTagInfo.Em410x(type, data.copyOfRange(2, 2 + uidLen))
}

/** HIDPROX_SCAN 响应：13 字节 `>BIBIBH` = (format, fc, cn_hi, cn_lo, il, oem)。 */
fun parseHidProxScan(data: ByteArray): LfTagInfo.HidProx {
    require(data.size >= 13) { "HIDPROX_SCAN 响应长度 ${data.size} < 13" }
    val format = data[0].toInt() and 0xFF
    val fc = readU32(data, 1)
    val cnHigh = data[5].toInt() and 0xFF
    val cnLow = readU32(data, 6)
    val il = data[10].toInt() and 0xFF
    val oem = ((data[11].toInt() and 0xFF) shl 8) or (data[12].toInt() and 0xFF)
    return LfTagInfo.HidProx(
        format = format,
        facilityCode = fc,
        cardNumber = (cnHigh.toLong() shl 32) or cnLow,
        issueLevel = il,
        oem = oem,
    )
}

private fun readU32(data: ByteArray, offset: Int): Long =
    ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
