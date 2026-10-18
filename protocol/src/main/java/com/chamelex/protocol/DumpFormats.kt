package com.chamelex.protocol

/**
 * dump 文件四格式互转（阶段 4）。结构对齐：
 * - .bin：原始字节（320/1024/2048/4096）
 * - .mct：MCT 文本（+Sector: N 分节，每行 32 hex；'-' 表示未知字节）
 * - .eml：纯 hex 文本（每行 32 hex；'-' 表示未知字节）
 * - .json：Proxmark3 兼容 JSON（FileType mfcard/mfc v2/mfc v3，blocks 表 + Card 元数据）
 *
 * 参考 chameleon-ultra.js（MIT）的 mf1DumpTo/From* 语义实现。
 */
enum class DumpFormat(val extension: String) {
    BIN("bin"),
    MCT("mct"),
    EML("eml"),
    JSON("json"),
}

/**
 * 内部 dump 结构（阶段 4 通用，与 Room DumpEntity 可互换）。
 *
 * @param blocks 块数据（N×16，未读出的块全 0）
 * @param readableMask 每块 1 位可读位图（MSB 优先），null = 全部可读
 * @param sak 卡型 SAK（null = 按块数推断）
 */
data class Mf1DumpData(
    val blocks: ByteArray,
    val readableMask: ByteArray? = null,
    val uid: ByteArray? = null,
    val atqa: ByteArray? = null,
    val sak: Int? = null,
    val ats: ByteArray? = null,
) {
    val blockCount: Int get() = blocks.size / 16
    val mfcType: MfcType? get() = sak?.let { MfcType.fromSak(it) } ?: MfcType.fromByteSize(blocks.size)

    fun isBlockReadable(block: Int): Boolean =
        readableMask?.let { it[block / 8].toInt() and (1 shl (7 - block % 8)) != 0 } ?: true

    fun blockAt(block: Int): ByteArray = blocks.copyOfRange(block * 16, (block + 1) * 16)

    override fun equals(other: Any?): Boolean {
        if (other !is Mf1DumpData || !blocks.contentEquals(other.blocks)) return false
        if (readableMask != null && other.readableMask != null &&
            !readableMask.contentEquals(other.readableMask)
        ) return false
        if (uid != null && other.uid != null && !uid.contentEquals(other.uid)) return false
        if (atqa != null && other.atqa != null && !atqa.contentEquals(other.atqa)) return false
        if (ats != null && other.ats != null && !ats.contentEquals(other.ats)) return false
        return sak == other.sak
    }

    override fun hashCode(): Int = blocks.contentHashCode()
}

private val HEX_ROW_REGEX = Regex("^[0-9a-fA-F\\-]{32}$")

// ---- 解析 ----

/** 按扩展名 + 内容嗅探识别格式并解析。 */
fun parseDump(fileName: String, content: ByteArray): Mf1DumpData {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val format = DumpFormat.entries.firstOrNull { it.extension == ext } ?: sniffDumpFormat(content)
    return parseDump(format, content)
}

fun parseDump(format: DumpFormat, content: ByteArray): Mf1DumpData = when (format) {
    DumpFormat.BIN -> parseBin(content)
    DumpFormat.MCT -> parseMct(content.decodeToString())
    DumpFormat.EML -> parseEml(content.decodeToString())
    DumpFormat.JSON -> parseDumpJson(content.decodeToString())
}

/** 内容嗅探（无扩展名时）。 */
fun sniffDumpFormat(content: ByteArray): DumpFormat {
    val text = runCatching { content.decodeToString() }.getOrNull() ?: return DumpFormat.BIN
    val trimmed = text.trimStart()
    if (trimmed.startsWith("{")) return DumpFormat.JSON
    if (text.contains("+Sector:")) return DumpFormat.MCT
    val lines = text.lines().filter { it.isNotBlank() }
    val hexLines = lines.count { HEX_ROW_REGEX.matches(it.trim()) }
    return if (hexLines > 0 && hexLines == lines.size) DumpFormat.EML else DumpFormat.BIN
}

fun parseBin(content: ByteArray): Mf1DumpData {
    require(content.size % 16 == 0 && content.size in 320..4096) {
        "BIN 大小必须是 16 的倍数且在 320..4096 之间（当前 ${content.size}）"
    }
    return Mf1DumpData(blocks = content)
}

/** MCT：'+Sector: N' 分节；secNo<32 → block=secNo*4，否则 (secNo-24)*16；'-' → 0 且标记不可读。 */
fun parseMct(text: String): Mf1DumpData {
    val buf = ByteArray(4096)
    val readable = ByteArray(4096 / 16 / 8) { 0xFF.toByte() }
    var blockNo = 0
    var maxBlockNo = -1
    text.lines().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.startsWith("+Sector:")) {
            val secNo = line.removePrefix("+Sector:").trim().toInt()
            blockNo = if (secNo < 32) secNo * 4 else (secNo - 24) * 16
        } else if (HEX_ROW_REGEX.matches(line)) {
            require(blockNo < 256) { "MCT 块号越界: $blockNo" }
            hexWithDashToBytes(line).copyInto(buf, blockNo * 16)
            if (line.contains('-')) setReadable(readable, blockNo, false)
            if (blockNo > maxBlockNo) maxBlockNo = blockNo
            blockNo++
        }
    }
    require(maxBlockNo >= 0) { "MCT 内容为空" }
    val size = if (maxBlockNo < 64) 1024 else if (maxBlockNo < 128) 2048 else 4096
    return Mf1DumpData(
        blocks = buf.copyOf(size),
        readableMask = readable.copyOf(size / 16 / 8),
    )
}

/** EML：每行 32 hex（兼容前导地址列 "xx:"）；'-' → 0 且标记不可读。 */
fun parseEml(text: String): Mf1DumpData {
    val buf = ByteArray(4096)
    val readable = ByteArray(4096 / 16 / 8) { 0xFF.toByte() }
    var blockNo = 0
    text.lines().forEach { rawLine ->
        var line = rawLine.trim()
        if (line.isEmpty()) return@forEach
        // 兼容 "xx: hex" 地址列前缀
        if (line.length > 33 && line.substring(0, 6).contains(':')) {
            line = line.substringAfter(':').trim()
        }
        line = line.replace(" ", "")
        if (HEX_ROW_REGEX.matches(line)) {
            hexWithDashToBytes(line).copyInto(buf, blockNo * 16)
            if (line.contains('-')) setReadable(readable, blockNo, false)
            blockNo++
        }
    }
    require(blockNo > 0) { "EML 内容为空" }
    val size = if (blockNo * 16 <= 1024) 1024 else if (blockNo * 16 <= 2048) 2048 else 4096
    return Mf1DumpData(
        blocks = buf.copyOf(size),
        readableMask = readable.copyOf(size / 16 / 8),
    )
}

/** Proxmark3 兼容 JSON：FileType mfcard/mfc v2/mfc v3，blocks{"n": hex}，Card{UID,ATQA,SAK,ATS,SIGNATURE}。 */
fun parseDumpJson(text: String): Mf1DumpData {
    val json = MiniJson.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("JSON 根必须是对象")
    val fileType = json["FileType"] as? String
    require(fileType in listOf("mfcard", "mfc v2", "mfc v3")) { "不支持的 FileType: $fileType" }
    val blocksObj = (json["blocks"] as? Map<*, *>) ?: emptyMap<String, Any>()
    val blockNumbers = blocksObj.keys.mapNotNull { (it as? String)?.toIntOrNull() }
    val maxBlkNo = blockNumbers.maxOrNull() ?: 0
    val size = if (maxBlkNo < 64) 1024 else if (maxBlkNo < 128) 2048 else 4096
    val buf = ByteArray(size)
    val readable = ByteArray(size / 16 / 8) { 0xFF.toByte() }
    for (blkNo in blockNumbers) {
        if (blkNo * 16 >= size) continue
        val hex = blocksObj[blkNo.toString()] as? String ?: continue
        if (hex.length != 32) continue
        hexWithDashToBytes(hex).copyInto(buf, blkNo * 16)
        if (hex.contains('-')) setReadable(readable, blkNo, false)
    }
    val card = json["Card"] as? Map<*, *>
    fun cardHex(name: String): ByteArray? = (card?.get(name) as? String)?.hexOrNull()
    return Mf1DumpData(
        blocks = buf,
        readableMask = readable,
        uid = cardHex("UID"),
        atqa = cardHex("ATQA"),
        sak = cardHex("SAK")?.firstOrNull()?.toInt()?.and(0xFF),
        ats = cardHex("ATS"),
    )
}

// ---- 序列化 ----

fun serializeDump(format: DumpFormat, dump: Mf1DumpData): ByteArray = when (format) {
    DumpFormat.BIN -> dump.blocks
    DumpFormat.MCT -> serializeMct(dump).encodeToByteArray()
    DumpFormat.EML -> serializeEml(dump).encodeToByteArray()
    DumpFormat.JSON -> serializeDumpJson(dump).encodeToByteArray()
}

/** MCT 导出：扇区分节；未读出的块按 MCT 惯例写 '-'。 */
fun serializeMct(dump: Mf1DumpData): String {
    val sb = StringBuilder()
    for (i in 0 until dump.blockCount) {
        val blocksPerSector = if (i < 128) 4 else 16
        if (i % blocksPerSector == 0) {
            val secNo = if (i < 128) i / 4 else i / 16 + 24
            sb.append("+Sector: ").append(secNo).append('\n')
        }
        sb.append(blockToHex(dump, i)).append('\n')
    }
    return sb.toString()
}

fun serializeEml(dump: Mf1DumpData): String =
    (0 until dump.blockCount).joinToString("\n") { blockToHex(dump, it) } + "\n"

fun serializeDumpJson(dump: Mf1DumpData): String {
    val antiColl = parseAntiCollFromBlock0(dump.blockAt(0))
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"Created\": \"ChameleX\",\n")
    sb.append("  \"FileType\": \"mfc v3\",\n")
    sb.append("  \"blocks\": {\n")
    for (i in 0 until dump.blockCount) {
        sb.append("    \"").append(i).append("\": \"").append(blockToHex(dump, i)).append("\"")
        sb.append(if (i == dump.blockCount - 1) "\n" else ",\n")
    }
    sb.append("  },\n")
    sb.append("  \"Card\": {\n")
    sb.append("    \"UID\": \"").append((dump.uid ?: antiColl.uid).toHexUpper()).append("\",\n")
    sb.append("    \"ATQA\": \"").append((dump.atqa ?: antiColl.atqa).toHexUpper()).append("\",\n")
    sb.append("    \"SAK\": \"").append("%02X".format(dump.sak ?: antiColl.sak)).append("\",\n")
    sb.append("    \"ATS\": \"").append((dump.ats ?: ByteArray(0)).toHexUpper()).append("\",\n")
    sb.append("    \"SIGNATURE\": \"\"\n")
    sb.append("  }\n")
    sb.append("}\n")
    return sb.toString()
}

// ---- 工具 ----

/** 未读块按 MCT 惯例输出 '-'（导入时 '-' → 0 且标记不可读）。 */
private fun blockToHex(dump: Mf1DumpData, block: Int): String =
    if (dump.isBlockReadable(block)) {
        dump.blockAt(block).toHexUpper()
    } else {
        "-".repeat(32)
    }

private fun hexWithDashToBytes(hex: String): ByteArray {
    require(hex.length == 32)
    return ByteArray(16) { i ->
        val b = hex.substring(i * 2, i * 2 + 2)
        if (b == "--") 0 else b.toInt(16).toByte()
    }
}

private fun setReadable(mask: ByteArray, block: Int, readable: Boolean) {
    if (!readable) {
        mask[block / 8] = (mask[block / 8].toInt() and (1 shl (7 - block % 8)).inv()).toByte()
    }
}

private fun String.hexOrNull(): ByteArray? =
    takeIf { it.isNotBlank() && it.length % 2 == 0 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
        ?.let { s -> s.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }

fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }

/**
 * 极简 JSON 解析器（protocol 是纯 JVM 模块，不能用 Android 的 org.json）。
 * 只覆盖 dump 文件所需子集：对象/数组/字符串/数字/true/false/null。
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.parseValue()
        p.skipWs()
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("JSON 意外结束")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(token: String) {
            if (!s.startsWith(token, i)) throw IllegalArgumentException("JSON 解析失败 @$i")
            i += token.length
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("JSON 缺 ':' @$i")
                i++
                map[key] = parseValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("JSON 意外结束")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return map
                    }

                    else -> throw IllegalArgumentException("JSON 解析失败 @$i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return list
                    }

                    else -> throw IllegalArgumentException("JSON 解析失败 @$i")
                }
            }
        }

        private fun parseString(): String {
            if (s[i] != '"') throw IllegalArgumentException("JSON 缺字符串 @$i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }

                    '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (s[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'u' -> {
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }

                            else -> sb.append(s[i])
                        }
                        i++
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            throw IllegalArgumentException("JSON 字符串未闭合")
        }

        private fun parseNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val str = s.substring(start, i)
            return str.toLongOrNull() ?: str.toDoubleOrNull()
                ?: throw IllegalArgumentException("JSON 数字解析失败: $str")
        }
    }
}

// ---- LF（ID 卡）hex 文本解析 ----

/**
 * LF 文件约定：内容为纯 hex 的 ID 文本（.eml/.txt，容错空格/换行）：
 * - 10 hex（5 字节）→ EM410X
 * - 26 hex（13 字节）→ HIDProx
 * 其他长度抛 IllegalArgumentException。
 */
fun parseLfIdFile(text: String): Pair<TagSpecificType, ByteArray> {
    val hex = text.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    require(hex.length == 10 || hex.length == 26) {
        "LF ID 必须是 10 位（EM410X）或 26 位（HIDProx）hex，当前 ${hex.length} 位"
    }
    val id = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return (if (hex.length == 10) TagSpecificType.EM410X else TagSpecificType.HID_PROX) to id
}

/** 判断文本是否像 LF hex ID 文件（用于 M1 解析失败后的兜底探测）。 */
fun looksLikeLfIdFile(text: String): Boolean {
    val hex = text.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    val others = text.filter { !(it.isDigit() || it.lowercaseChar() in 'a'..'f') }
    return (hex.length == 10 || hex.length == 26) && others.all { it.isWhitespace() }
}
