package com.chamelex.app.ui.screens.data

import com.chamelex.data.db.DumpEntity
import com.chamelex.protocol.Mf1DumpData
import com.chamelex.protocol.MfcType
import com.chamelex.protocol.TagSpecificType

/** Room DumpEntity 与内部 dump 结构互换。 */

fun Mf1DumpData.toEntity(name: String, source: String): DumpEntity {
    val mfcType = mfcType
    return DumpEntity(
        name = name,
        tagType = sak ?: 0,
        uid = (uid ?: blockAt(0).let { parseUid(it) }).toHexUpper(),
        source = source,
        createdAt = System.currentTimeMillis(),
        sectorCount = mfcType?.sectorCount ?: 0,
        blockCount = blockCount,
        data = blocks,
        readableMask = readableMask ?: ByteArray((blockCount + 7) / 8) { 0xFF.toByte() },
        keys = ByteArray((mfcType?.sectorCount ?: 0) * 12) { 0xFF.toByte() },
    )
}

fun DumpEntity.toDumpData(): Mf1DumpData = Mf1DumpData(
    blocks = data,
    readableMask = readableMask,
    sak = tagType.takeIf { it != 0 },
)

private fun parseUid(block0: ByteArray): ByteArray =
    if (block0[0] == 0x88.toByte()) {
        byteArrayOf(block0[1], block0[2], block0[3], block0[5], block0[6], block0[7], block0[8])
    } else {
        block0.copyOfRange(0, 4)
    }

private fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }

/** 低频卡 dump 记录（EM410X 等）：sectorCount=0 表示非 M1。 */
fun makeLfDumpEntity(name: String, type: TagSpecificType, id: ByteArray, source: String): DumpEntity =
    DumpEntity(
        name = name,
        tagType = type.code,
        uid = id.toHexUpper(),
        source = source,
        createdAt = System.currentTimeMillis(),
        sectorCount = 0,
        blockCount = 0,
        data = id,
        readableMask = ByteArray(0),
        keys = ByteArray(0),
    )

fun DumpEntity.isLfDump(): Boolean = sectorCount == 0

fun DumpEntity.lfType(): TagSpecificType = TagSpecificType.fromCode(tagType)
