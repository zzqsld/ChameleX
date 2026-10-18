package com.chamelex.protocol

/** 14A 防冲突数据。 */
data class AntiCollData(
    val uid: ByteArray,
    val atqa: ByteArray,
    val sak: Int,
    val ats: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is AntiCollData && uid.contentEquals(other.uid) && atqa.contentEquals(other.atqa) &&
                sak == other.sak && ats.contentEquals(other.ats)
    override fun hashCode(): Int = uid.contentHashCode()
}

/**
 * 从 M1 dump 的 0 块（厂商块）解析 UID/BCC/SAK/ATQA。
 *
 * 标准布局：
 * - 4 字节 UID：[uid0..3, bcc, sak, atqa0, atqa1, ...]，bcc = uid0^uid1^uid2^uid3
 * - 7 字节 UID：[0x88(ct), uid0..2, bcc1, uid3..6, bcc2, sak, atqa0, atqa1, ...]，
 *   bcc1 = 0x88^uid0^uid1^uid2，bcc2 = uid3^uid4^uid5^uid6
 */
fun parseAntiCollFromBlock0(block0: ByteArray): AntiCollData {
    require(block0.size >= 16) { "0 块必须 16 字节" }
    return if (block0[0] == 0x88.toByte()) {
        val uid = byteArrayOf(block0[1], block0[2], block0[3], block0[5], block0[6], block0[7], block0[8])
        AntiCollData(
            uid = uid,
            atqa = byteArrayOf(block0[11], block0[12]),
            sak = block0[10].toInt() and 0xFF,
        )
    } else {
        AntiCollData(
            uid = block0.copyOfRange(0, 4),
            atqa = byteArrayOf(block0[6], block0[7]),
            sak = block0[5].toInt() and 0xFF,
        )
    }
}

/** 4 字节 UID 的 BCC。 */
fun mf1Bcc(uid: ByteArray): Int {
    var bcc = 0
    for (b in uid) bcc = bcc xor (b.toInt() and 0xFF)
    return bcc
}
