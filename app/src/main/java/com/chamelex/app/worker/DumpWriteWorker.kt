package com.chamelex.app.worker

import com.chamelex.protocol.CardLostException
import com.chamelex.protocol.Hf14aRawOptions
import com.chamelex.protocol.Mf1Commands
import com.chamelex.protocol.Mf1DumpData
import com.chamelex.protocol.MfcKeyType
import com.chamelex.protocol.SectorKeys
import com.chamelex.protocol.SlotCommands
import com.chamelex.protocol.SlotNumber
import com.chamelex.protocol.TagSpecificType
import com.chamelex.protocol.mfcBlocksInSector
import com.chamelex.protocol.mfcFirstBlockOfSector
import com.chamelex.protocol.parseAntiCollFromBlock0
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 写入结果。 */
data class WriteResult(
    val written: Int,
    val total: Int,
    /** 回读校验不一致的块号 */
    val mismatchedBlocks: List<Int>,
)

/**
 * dump 写入工作器：eload 到模拟卡槽 / 写入实体魔术卡（Gen1A/Gen2）。
 * 被数据管理页与读卡页共用。
 */
class DumpWriteWorker(
    private val mf1: Mf1Commands,
    private val slots: SlotCommands?,
) {
    companion object {
        /** eload 写入分块（4000 无块数限制，只限总帧长） */
        const val ELOAD_CHUNK_BLOCKS = 64

        /** 回读校验分块：固件 4008 限制 block_count ≤ 32（app_cmd.c:1611） */
        const val VERIFY_CHUNK_BLOCKS = 32

        // Gen1A 后门指令字节（卡片级指令，非设备协议指令）
        const val GEN1A_UNLOCK_CMD1 = 0x40
        const val GEN1A_UNLOCK_CMD2 = 0x43
        const val GEN1A_WRITE_BLOCK = 0xA0
        const val MFC_READ_BLOCK = 0x30

        /** 卡片 ACK 应答 */
        const val TAG_ACK = 0x0A
    }

    /** 低频 ID 写入模拟卡槽（EM410X / HIDProx）。 */
    suspend fun writeLfToSlot(slot: SlotNumber, type: TagSpecificType, id: ByteArray) {
        val slotCmd = slots ?: throw IllegalStateException("设备未连接")
        slotCmd.setActiveSlot(slot)
        slotCmd.setSlotTagType(slot, type)
        when (type) {
            TagSpecificType.EM410X, TagSpecificType.EM410X_16,
            TagSpecificType.EM410X_32, TagSpecificType.EM410X_64,
            TagSpecificType.EM410X_ELECTRA,
                -> slotCmd.em410xSetEmuId(id)

            TagSpecificType.HID_PROX -> slotCmd.hidProxSetEmuId(id)
            else -> throw IllegalArgumentException("不支持的低频卡型: $type")
        }
        slotCmd.slotDataConfigSave()
    }

    /**
     * M1 dump 写入模拟卡槽：切槽 → 设类型 → 分块 eload → 按 0 块设置 anticoll → 回读校验 → 保存配置。
     */
    suspend fun writeM1ToSlot(
        dump: Mf1DumpData,
        slot: SlotNumber,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): WriteResult {
        val slotCmd = slots ?: throw IllegalStateException("设备未连接")
        val mfcType = dump.mfcType ?: throw IllegalArgumentException("无法识别 dump 卡型")
        slotCmd.setActiveSlot(slot)
        slotCmd.setSlotTagType(slot, mfcType.tagSpecificType)

        // 分块 eload
        var offset = 0
        while (offset < dump.blockCount) {
            currentCoroutineContext().ensureActive()
            val n = minOf(ELOAD_CHUNK_BLOCKS, dump.blockCount - offset)
            mf1.mf1WriteEmuBlockData(offset, dump.blocks.copyOfRange(offset * 16, (offset + n) * 16))
            offset += n
            onProgress(offset, dump.blockCount)
        }

        // 0 块 → anticoll（UID/BCC/SAK/ATQA）
        val ac = parseAntiCollFromBlock0(dump.blockAt(0))
        mf1.hf14aSetAntiCollData(ac.uid, ac.atqa, ac.sak, ac.ats)

        // 回读校验（固件单批上限 32 块）
        val mismatches = mutableListOf<Int>()
        offset = 0
        while (offset < dump.blockCount) {
            currentCoroutineContext().ensureActive()
            val n = minOf(VERIFY_CHUNK_BLOCKS, dump.blockCount - offset)
            val read = mf1.mf1ReadEmuBlockData(offset, n)
            for (i in 0 until n) {
                if (!dump.blockAt(offset + i).contentEquals(read.copyOfRange(i * 16, (i + 1) * 16))) {
                    mismatches.add(offset + i)
                }
            }
            offset += n
        }

        slotCmd.slotDataConfigSave()
        return WriteResult(dump.blockCount, dump.blockCount, mismatches)
    }

    /**
     * Gen2 魔术卡写入：用 dump 密钥逐块写（数据块先写、trailer 最后写）。
     * 只写 dump 中已读出的块。返回校验不一致的块。
     */
    suspend fun writeGen2Card(
        dump: Mf1DumpData,
        keys: List<SectorKeys>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): WriteResult {
        val mfcType = dump.mfcType ?: throw IllegalArgumentException("无法识别 dump 卡型")
        val mismatches = mutableListOf<Int>()
        var done = 0
        val writable = (0 until dump.blockCount).count { dump.isBlockReadable(it) }

        for (sector in 0 until mfcType.sectorCount) {
            val blockCount = mfcBlocksInSector(sector)
            val firstBlock = mfcFirstBlockOfSector(sector)
            val trailer = firstBlock + blockCount - 1
            // 数据块在前，trailer 最后（写完 trailer 后卡密钥变为 dump 的密钥）
            val order = (0 until blockCount - 1).map { firstBlock + it } + trailer
            for (block in order) {
                currentCoroutineContext().ensureActive()
                if (!dump.isBlockReadable(block)) continue
                val data = dump.blockAt(block)
                val ok = writeWithKeys(block, data, keys[sector])
                if (ok) {
                    val read = readWithKeys(block, keys[sector])
                    if (read == null || !read.contentEquals(data)) mismatches.add(block)
                } else {
                    mismatches.add(block)
                }
                done++
                onProgress(done, writable)
            }
        }
        return WriteResult(done, writable, mismatches)
    }

    /**
     * Gen1A 魔术卡写入：0x40/0x43 后门解锁 → 逐块 0xA0 写。
     * 只写 dump 中已读出的块（含 0 块 UID）。
     */
    suspend fun writeGen1aCard(
        dump: Mf1DumpData,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): WriteResult {
        gen1aUnlock()
        val mismatches = mutableListOf<Int>()
        var done = 0
        val writable = (0 until dump.blockCount).count { dump.isBlockReadable(it) }

        for (block in 0 until dump.blockCount) {
            currentCoroutineContext().ensureActive()
            if (!dump.isBlockReadable(block)) continue
            val data = dump.blockAt(block)
            val ok = gen1aWriteBlock(block, data)
            if (ok) {
                val read = gen1aReadBlock(block)
                if (read == null || !read.contentEquals(data)) mismatches.add(block)
            } else {
                mismatches.add(block)
            }
            done++
            onProgress(done, writable)
        }
        return WriteResult(done, writable, mismatches)
    }

    // ---- Gen1A 后门序列 ----

    private suspend fun gen1aUnlock() {
        // 0x40（7 位）+ 0x43 解锁后门，卡应答 0x0A
        val opts1 = Hf14aRawOptions(
            activateRfField = true, waitResponse = true, autoSelect = true, keepRfField = true,
        )
        val r1 = mf1.hf14aRaw(opts1, byteArrayOf(GEN1A_UNLOCK_CMD1.toByte()), respTimeoutMs = 100, bitlen = 7)
        if (!isAck(r1)) throw CardLostException()
        val r2 = mf1.hf14aRaw(
            Hf14aRawOptions(waitResponse = true, keepRfField = true),
            byteArrayOf(GEN1A_UNLOCK_CMD2.toByte()),
        )
        if (!isAck(r2)) throw IllegalStateException("卡片不是 Gen1A 魔术卡（后门未开）")
    }

    private suspend fun gen1aWriteBlock(block: Int, data: ByteArray): Boolean {
        val opts = Hf14aRawOptions(waitResponse = true, appendCrc = true, keepRfField = true)
        val ack = mf1.hf14aRaw(opts, byteArrayOf(GEN1A_WRITE_BLOCK.toByte(), block.toByte()))
        if (!isAck(ack)) return false
        return isAck(mf1.hf14aRaw(opts, data))
    }

    private suspend fun gen1aReadBlock(block: Int): ByteArray? {
        val resp = mf1.hf14aRaw(
            Hf14aRawOptions(waitResponse = true, appendCrc = true, keepRfField = true),
            byteArrayOf(MFC_READ_BLOCK.toByte(), block.toByte()),
        )
        return if (resp.size >= 16) resp.copyOfRange(0, 16) else null
    }

    private fun isAck(resp: ByteArray): Boolean = resp.isNotEmpty() && resp[0] == TAG_ACK.toByte()

    // ---- Gen2 密钥读写 ----

    private suspend fun writeWithKeys(block: Int, data: ByteArray, keys: SectorKeys): Boolean {
        val keyA = keys.keyA
        val keyB = keys.keyB
        if (keyA != null && mf1.mf1WriteOneBlock(MfcKeyType.A, block, keyA, data)) return true
        if (keyB != null && mf1.mf1WriteOneBlock(MfcKeyType.B, block, keyB, data)) return true
        return false
    }

    private suspend fun readWithKeys(block: Int, keys: SectorKeys): ByteArray? {
        val keyA = keys.keyA
        val keyB = keys.keyB
        if (keyA != null) {
            try {
                return mf1.mf1ReadOneBlock(MfcKeyType.A, block, keyA)
            } catch (_: com.chamelex.protocol.AuthFailedException) {
            }
        }
        if (keyB != null) {
            try {
                return mf1.mf1ReadOneBlock(MfcKeyType.B, block, keyB)
            } catch (_: com.chamelex.protocol.AuthFailedException) {
            }
        }
        return null
    }
}
