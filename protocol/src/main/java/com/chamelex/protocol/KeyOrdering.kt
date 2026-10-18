package com.chamelex.protocol

/**
 * 字典攻击喂钥顺序：该卡记忆密钥 → 用户自定义组（按排序）→ 已破解组 → 内置默认组。
 * 全局去重且保持顺序。
 */
fun orderKeys(
    remembered: List<ByteArray>,
    userGroups: List<List<ByteArray>>,
    crackedGroup: List<ByteArray>,
    builtinGroup: List<ByteArray>,
): List<ByteArray> {
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<ByteArray>()
    fun add(keys: List<ByteArray>) {
        for (k in keys) {
            if (seen.add(k.toHexUpper())) out.add(k)
        }
    }
    add(remembered)
    userGroups.forEach { add(it) }
    add(crackedGroup)
    add(builtinGroup)
    return out
}

// ---- UID → 扇区密钥映射编解码（与 DumpEntity.keys 布局一致：每扇区 12B，未知 0xFF） ----

typealias KeyPair = Pair<ByteArray?, ByteArray?>

/** 打包扇区密钥映射为字节数组（每扇区 KeyA 6 + KeyB 6，未知 0xFF）。 */
fun packCardKeys(sectorKeys: List<KeyPair>): ByteArray {
    val out = ByteArray(sectorKeys.size * 12) { 0xFF.toByte() }
    sectorKeys.forEachIndexed { sector, (keyA, keyB) ->
        keyA?.let {
            require(it.size == 6)
            it.copyInto(out, sector * 12)
        }
        keyB?.let {
            require(it.size == 6)
            it.copyInto(out, sector * 12 + 6)
        }
    }
    return out
}

/** 解包扇区密钥映射。 */
fun unpackCardKeys(data: ByteArray): List<KeyPair> {
    require(data.size % 12 == 0)
    return (data.indices step 12).map { i ->
        val keyA = data.copyOfRange(i, i + 6).takeUnless { it.all { b -> b == 0xFF.toByte() } }
        val keyB = data.copyOfRange(i + 6, i + 12).takeUnless { it.all { b -> b == 0xFF.toByte() } }
        keyA to keyB
    }
}
