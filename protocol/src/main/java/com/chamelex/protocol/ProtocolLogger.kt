package com.chamelex.protocol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 协议日志环形缓冲（内存最近 [CAPACITY] 条）。
 * 挂接在 ChameleonProtocol 收发帧处；条目只在读取时才格式化（热路径零字符串开销）。
 */
object ProtocolLogger {

    const val CAPACITY = 500

    data class Entry(
        val timestampMs: Long,
        /** true=发送（APP→设备） */
        val tx: Boolean,
        val cmd: Int,
        /** 响应帧的状态码；请求帧为 null */
        val status: Int? = null,
        val dataLen: Int,
    )

    @Volatile
    var enabled: Boolean = true

    private val buffer = ArrayDeque<Entry>(CAPACITY)

    fun log(tx: Boolean, cmd: Int, status: Int?, dataLen: Int) {
        if (!enabled) return
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), tx, cmd, status, dataLen))
        }
    }

    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    fun formatLine(e: Entry): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(e.timestampMs))
        val dir = if (e.tx) "TX" else "RX"
        val cmdName = Command.fromCode(e.cmd)?.name ?: "UNKNOWN"
        val statusPart = e.status?.let { s ->
            " STATUS=0x%02X(%s)".format(s, Status.fromCode(s)?.name ?: "?")
        } ?: ""
        return "$time $dir CMD=${e.cmd}($cmdName)$statusPart LEN=${e.dataLen}"
    }

    fun exportText(): String = snapshot().joinToString("\n") { formatLine(it) }
}
