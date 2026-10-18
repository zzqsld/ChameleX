package com.chamelex.protocol

/**
 * MCT .dic 密钥文件解析：每行 12 位 hex，# 开头注释与空行忽略，去重保序。
 */
fun parseKeyDictionary(text: String): List<ByteArray> {
    val seen = LinkedHashSet<String>()
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.uppercase() }
        .filter { it.matches(Regex("[0-9A-F]{12}")) }
        .forEach { seen.add(it) }
    return seen.map { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
}
