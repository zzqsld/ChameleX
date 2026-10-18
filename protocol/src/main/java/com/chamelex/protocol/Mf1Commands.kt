package com.chamelex.protocol

/**
 * M1 字典/密钥读取相关指令封装（阶段 3b）。
 */
class Mf1Commands(private val protocol: ChameleonProtocol) {

    companion object {
        const val CHECK_KEYS_BATCH_SIZE = 5
        const val CHECK_KEYS_MAX_PER_BATCH = 83
        const val KEY_SIZE = 6
        const val MASK_BYTES = 10
        const val MASK_BITS = 80
    }

    /**
     * MF1_CHECK_KEYS_OF_SECTORS=2012：批量字典检测。
     *
     * @param mask 10 字节掩码（位=1 跳过不检），命中位由客户端 OR 进掩码
     * @param keys 本批密钥（1..83 个，官方 CLI 每批 20 个）
     * @return 490 字节响应解析结果（found 位图 + 密钥区）
     * @throws DeviceStatusException 状态非 HF_TAG_OK（卡离开等中断）
     */
    suspend fun mf1CheckKeysOfSectors(mask: ByteArray, keys: List<ByteArray>): CheckKeysResult {
        require(mask.size == MASK_BYTES) { "掩码必须是 $MASK_BYTES 字节" }
        require(keys.size in 1..CHECK_KEYS_MAX_PER_BATCH) { "每批密钥 1..$CHECK_KEYS_MAX_PER_BATCH 个" }
        require(keys.all { it.size == KEY_SIZE }) { "密钥必须是 $KEY_SIZE 字节" }

        val request = mask + keys.reduce { acc, k -> acc + k }
        // 超时公式（官方 CLI）：1 + (待检位数 + 1) × 批内密钥数 × 0.1 秒
        val bitsToCheck = maskCountClear(mask)
        val timeoutMs = ((1.0 + (bitsToCheck + 1) * keys.size * 0.1) * 1000).toLong()

        val resp = protocol.sendCommand(Command.MF1_CHECK_KEYS_OF_SECTORS, request, timeoutMs)
        when (resp.statusEnum) {
            Status.HF_TAG_OK -> return parseCheckKeysResponse(resp.data)
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_CHECK_KEYS_OF_SECTORS.code)
            else -> throw DeviceStatusException(Command.MF1_CHECK_KEYS_OF_SECTORS.code, resp.status)
        }
    }

    /**
     * MF1_WRITE_ONE_BLOCK=2009：`!BB6s16s` (key_type, block, key, data)。
     * @return true=写入成功；false=认证失败
     * @throws CardLostException 卡离开感应区
     */
    suspend fun mf1WriteOneBlock(keyType: MfcKeyType, block: Int, key: ByteArray, data: ByteArray): Boolean {
        require(key.size == KEY_SIZE && data.size == 16) { "参数长度错误" }
        val resp = protocol.sendCommand(
            Command.MF1_WRITE_ONE_BLOCK,
            byteArrayOf(keyType.code.toByte(), block.toByte()) + key + data,
        )
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> true
            Status.MF_ERR_AUTH -> false
            Status.HF_TAG_NO -> throw CardLostException()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_WRITE_ONE_BLOCK.code)
            else -> throw DeviceStatusException(Command.MF1_WRITE_ONE_BLOCK.code, resp.status)
        }
    }

    /**
     * MF1_WRITE_EMU_BLOCK_DATA=4000：`!B{16n}s` (block_start + 连续多块数据)。
     * 成功状态 SUCCESS。
     */
    suspend fun mf1WriteEmuBlockData(blockStart: Int, blockData: ByteArray) {
        require(blockData.size % 16 == 0 && blockData.isNotEmpty()) { "块数据必须按 16 字节对齐" }
        protocol.sendCommandExpect(
            Command.MF1_WRITE_EMU_BLOCK_DATA,
            byteArrayOf(blockStart.toByte()) + blockData,
        )
    }

    /** MF1_READ_EMU_BLOCK_DATA=4008：`!BB` (block_start, block_count)，回读校验用。 */
    suspend fun mf1ReadEmuBlockData(blockStart: Int, blockCount: Int): ByteArray =
        protocol.sendCommandExpect(
            Command.MF1_READ_EMU_BLOCK_DATA,
            byteArrayOf(blockStart.toByte(), blockCount.toByte()),
        ).data

    /** HF14A_SET_ANTI_COLL_DATA=4001：`!B{uid}2s1sB{ats}` (uidlen+uid+atqa+sak+atslen+ats)。 */
    suspend fun hf14aSetAntiCollData(uid: ByteArray, atqa: ByteArray, sak: Int, ats: ByteArray = ByteArray(0)) {
        require(atqa.size == 2) { "ATQA 必须 2 字节" }
        protocol.sendCommandExpect(
            Command.HF14A_SET_ANTI_COLL_DATA,
            byteArrayOf(uid.size.toByte()) + uid + atqa + byteArrayOf(sak.toByte(), ats.size.toByte()) + ats,
        )
    }

    /**
     * HF14A_RAW=2010：原始 14A 指令（Gen1A 后门写卡用）。
     * 请求：1 字节 options 位域 + `!HH` (timeout_ms, bitlen) + data。
     * 返回卡片应答原始字节；超时/无应答返回空。
     */
    suspend fun hf14aRaw(
        options: Hf14aRawOptions,
        data: ByteArray,
        respTimeoutMs: Int = 100,
        bitlen: Int = data.size * 8,
    ): ByteArray {
        val resp = protocol.sendCommand(
            Command.HF14A_RAW,
            byteArrayOf(options.pack().toByte()) +
                    byteArrayOf((respTimeoutMs shr 8).toByte(), respTimeoutMs.toByte()) +
                    byteArrayOf((bitlen shr 8).toByte(), bitlen.toByte()) +
                    data,
            timeoutMs = respTimeoutMs + 1000L,
        )
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> resp.data
            Status.HF_TAG_NO -> throw CardLostException()
            else -> ByteArray(0)
        }
    }

    suspend fun mf1AuthOneKeyBlock(keyType: MfcKeyType, block: Int, key: ByteArray): Boolean {
        require(key.size == KEY_SIZE) { "密钥必须是 $KEY_SIZE 字节" }
        val resp = protocol.sendCommand(
            Command.MF1_AUTH_ONE_KEY_BLOCK,
            byteArrayOf(keyType.code.toByte(), block.toByte()) + key,
        )
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> true
            Status.MF_ERR_AUTH -> false
            Status.HF_TAG_NO -> throw CardLostException()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_AUTH_ONE_KEY_BLOCK.code)
            else -> throw DeviceStatusException(Command.MF1_AUTH_ONE_KEY_BLOCK.code, resp.status)
        }
    }

    /**
     * MF1_READ_ONE_BLOCK=2008：请求同 2007，响应 16 字节块数据。
     * @throws AuthFailedException 密钥认证失败
     * @throws CardLostException 卡离开感应区
     */
    suspend fun mf1ReadOneBlock(keyType: MfcKeyType, block: Int, key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "密钥必须是 $KEY_SIZE 字节" }
        val resp = protocol.sendCommand(
            Command.MF1_READ_ONE_BLOCK,
            byteArrayOf(keyType.code.toByte(), block.toByte()) + key,
        )
        return when (resp.statusEnum) {
            Status.HF_TAG_OK -> resp.data
            Status.MF_ERR_AUTH -> throw AuthFailedException(block)
            Status.HF_TAG_NO -> throw CardLostException()
            Status.INVALID_CMD -> throw InvalidCommandException(Command.MF1_READ_ONE_BLOCK.code)
            else -> throw DeviceStatusException(Command.MF1_READ_ONE_BLOCK.code, resp.status)
        }
    }
}

/** HF14A_RAW 的 options 位域（与 chameleon_cmd.py 的 CStruct 一致）。 */
data class Hf14aRawOptions(
    val activateRfField: Boolean = false,
    val waitResponse: Boolean = true,
    val appendCrc: Boolean = false,
    val autoSelect: Boolean = false,
    val keepRfField: Boolean = false,
    val checkResponseCrc: Boolean = false,
) {
    fun pack(): Int =
        (if (activateRfField) 0x01 else 0) or
                (if (waitResponse) 0x02 else 0) or
                (if (appendCrc) 0x04 else 0) or
                (if (autoSelect) 0x08 else 0) or
                (if (keepRfField) 0x10 else 0) or
                (if (checkResponseCrc) 0x20 else 0)
}

/** 卡片离开感应区（读取中断，可提示用户重试）。 */
class CardLostException : Exception("卡片离开感应区")

/** 块密钥认证失败。 */
class AuthFailedException(val block: Int) : Exception("块 $block 认证失败")

/** MF1_CHECK_KEYS_OF_SECTORS 的 490 字节响应解析结果。 */
data class CheckKeysResult(
    /** 10 字节 found 位图（位=1 命中） */
    val foundMask: ByteArray,
    /** 80×6 字节密钥区（命中位 k 的密钥在偏移 6k 处） */
    val keyArea: ByteArray,
) {
    fun isFound(k: Int): Boolean = maskGet(foundMask, k)

    fun keyAt(k: Int): ByteArray = keyArea.copyOfRange(k * Mf1Commands.KEY_SIZE, (k + 1) * Mf1Commands.KEY_SIZE)

    override fun equals(other: Any?): Boolean =
        other is CheckKeysResult && foundMask.contentEquals(other.foundMask) && keyArea.contentEquals(other.keyArea)
    override fun hashCode(): Int = 31 * foundMask.contentHashCode() + keyArea.contentHashCode()
}

// ---- 掩码位操作与解析（纯函数，可单测） ----

/**
 * 80 位掩码语义：位 k 对应 扇区 k/2 的 KeyA(k%2==0)/KeyB(k%2==1)。
 * MSB 优先：位 k 位于第 k/8 字节的第 (7-k%8) 位。
 * 在 CHECK_KEYS 请求中位=1 表示跳过；在响应 found 位图中位=1 表示命中。
 */
fun maskGet(mask: ByteArray, k: Int): Boolean {
    require(mask.size == Mf1Commands.MASK_BYTES && k in 0 until Mf1Commands.MASK_BITS)
    return mask[k / 8].toInt() and (1 shl (7 - k % 8)) != 0
}

fun maskSet(mask: ByteArray, k: Int) {
    require(mask.size == Mf1Commands.MASK_BYTES && k in 0 until Mf1Commands.MASK_BITS)
    mask[k / 8] = (mask[k / 8].toInt() or (1 shl (7 - k % 8))).toByte()
}

/** dst |= src */
fun maskOr(dst: ByteArray, src: ByteArray) {
    require(dst.size == Mf1Commands.MASK_BYTES && src.size == Mf1Commands.MASK_BYTES)
    for (i in dst.indices) dst[i] = (dst[i].toInt() or src[i].toInt()).toByte()
}

/** 统计位=0 的位数（CHECK_KEYS 中即待检测的位数）。 */
fun maskCountClear(mask: ByteArray): Int {
    var count = 0
    for (k in 0 until Mf1Commands.MASK_BITS) if (!maskGet(mask, k)) count++
    return count
}

/** 所有位均为 1（固件不会执行，客户端应跳过）。 */
fun maskAllSet(mask: ByteArray): Boolean = maskCountClear(mask) == 0

/**
 * 按卡扇区数构造检测掩码：超出卡片扇区范围的位预置 1（跳过）。
 * 与 CLI autopwn 的 bits_to_10byte_mask(max_sectors*2) 一致——
 * 卡只有 sectorCount*2 个密钥位，全部命中时 maskAllSet 才为真（"秒开"判定依赖这一点）。
 */
fun createCheckMask(sectorCount: Int): ByteArray {
    val mask = ByteArray(Mf1Commands.MASK_BYTES)
    for (k in sectorCount * 2 until Mf1Commands.MASK_BITS) maskSet(mask, k)
    return mask
}

/** MF1_CHECK_KEYS_OF_SECTORS 响应：490 字节 = 10 字节 found 位图 + 80×6 密钥区。 */
fun parseCheckKeysResponse(data: ByteArray): CheckKeysResult {
    require(data.size >= Mf1Commands.MASK_BYTES + Mf1Commands.MASK_BITS * Mf1Commands.KEY_SIZE) {
        "CHECK_KEYS 响应长度 ${data.size} 不足 490 字节"
    }
    return CheckKeysResult(
        foundMask = data.copyOfRange(0, Mf1Commands.MASK_BYTES),
        keyArea = data.copyOfRange(Mf1Commands.MASK_BYTES, Mf1Commands.MASK_BYTES + Mf1Commands.MASK_BITS * Mf1Commands.KEY_SIZE),
    )
}

// ---- M1 卡结构 ----

/** 每扇区密钥对。 */
data class SectorKeys(val keyA: ByteArray? = null, val keyB: ByteArray? = null) {
    val isComplete: Boolean get() = keyA != null && keyB != null
}

/** M1 卡型（按 SAK 判断）。 */
enum class MfcType(val sak: Int, val sectorCount: Int, val blockCount: Int) {
    MINI(0x09, 5, 20),
    CLASSIC_1K(0x08, 16, 64),
    CLASSIC_2K(0x10, 32, 128),
    CLASSIC_4K(0x18, 40, 256);

    /** 对应的 TagSpecificType（模拟槽类型）。 */
    val tagSpecificType: TagSpecificType
        get() = when (this) {
            MINI -> TagSpecificType.MIFARE_MINI
            CLASSIC_1K -> TagSpecificType.MIFARE_1024
            CLASSIC_2K -> TagSpecificType.MIFARE_2048
            CLASSIC_4K -> TagSpecificType.MIFARE_4096
        }

    companion object {
        fun fromSak(sak: Int): MfcType? = entries.firstOrNull { it.sak == sak }

        /** 按 dump 字节数推断卡型。 */
        fun fromByteSize(size: Int): MfcType? = entries.firstOrNull { it.blockCount * 16 == size }
    }
}

/** 扇区的块数：4K 卡 32 扇区起每扇区 16 块，其余每扇区 4 块。 */
fun mfcBlocksInSector(sector: Int): Int = if (sector < 32) 4 else 16

/** 扇区首块地址：前 32 扇区 sector*4，之后 128 + (sector-32)*16。 */
fun mfcFirstBlockOfSector(sector: Int): Int =
    if (sector < 32) sector * 4 else 128 + (sector - 32) * 16

/** 块号 → 扇区号（mfcFirstBlockOfSector 的反函数）。 */
fun mfcSectorOfBlock(block: Int): Int =
    if (block < 128) block / 4 else (block - 128) / 16 + 32
