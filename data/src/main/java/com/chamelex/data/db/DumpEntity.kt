package com.chamelex.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * dump 卡片数据。块数据/可读位图/密钥布局按 M1 设计，阶段 4 格式互转直接复用。
 */
@Entity(tableName = "dumps")
data class DumpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 卡型标识（MfcType 的 sak 值） */
    val tagType: Int,
    /** UID hex */
    val uid: String,
    /** 来源：dict_attack / import 等 */
    val source: String,
    val createdAt: Long,
    val sectorCount: Int,
    val blockCount: Int,
    /** 全部块数据串联（16 字节/块，未读出的块为全 0） */
    val data: ByteArray,
    /** 可读位图：每块 1 位，1=读取成功（MSB 优先） */
    val readableMask: ByteArray,
    /** 每扇区 12 字节：KeyA(6) + KeyB(6)，未命中填 0xFF */
    val keys: ByteArray,
) {
    fun isBlockReadable(block: Int): Boolean =
        readableMask[block / 8].toInt() and (1 shl (7 - block % 8)) != 0

    fun blockAt(block: Int): ByteArray = data.copyOfRange(block * 16, (block + 1) * 16)

    fun sectorKeyA(sector: Int): ByteArray? =
        keys.copyOfRange(sector * 12, sector * 12 + 6).takeUnless { it.all { b -> b == 0xFF.toByte() } }

    fun sectorKeyB(sector: Int): ByteArray? =
        keys.copyOfRange(sector * 12 + 6, sector * 12 + 12).takeUnless { it.all { b -> b == 0xFF.toByte() } }

    override fun equals(other: Any?): Boolean =
        other is DumpEntity && id == other.id && name == other.name && tagType == other.tagType &&
                uid == other.uid && source == other.source && createdAt == other.createdAt &&
                sectorCount == other.sectorCount && blockCount == other.blockCount &&
                data.contentEquals(other.data) && readableMask.contentEquals(other.readableMask) &&
                keys.contentEquals(other.keys)

    override fun hashCode(): Int = id.hashCode()
}
