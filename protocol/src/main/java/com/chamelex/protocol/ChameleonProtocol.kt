package com.chamelex.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** 设备响应：指令码 + 状态码 + 数据。 */
data class Response(val cmd: Int, val status: Int, val data: ByteArray) {
    val statusEnum: Status? get() = Status.fromCode(status)
    val isSuccess: Boolean get() = statusEnum?.isDeviceSuccess == true
    override fun equals(other: Any?): Boolean =
        other is Response && cmd == other.cmd && status == other.status && data.contentEquals(other.data)
    override fun hashCode(): Int = 31 * (31 * cmd + status) + data.contentHashCode()
}

/** 指令执行超时。 */
class CommandTimeoutException(cmd: Int) : Exception("CMD $cmd 执行超时")

/** 设备固件不支持该指令（STATUS = INVALID_CMD）。 */
class InvalidCommandException(cmd: Int) : Exception("设备不支持的指令: $cmd（固件可能过旧）")

/** 指令已下发但设备返回了错误状态。 */
class DeviceStatusException(val cmd: Int, val status: Int) :
    Exception("CMD $cmd 返回错误状态 0x${status.toString(16)}")

/**
 * 协议会话层：挂在一个字节收发抽象上，提供指令/响应匹配与超时控制。
 *
 * @param send  把编码后的帧写到底层通道
 * @param incoming 底层通道的上行字节流（USB 裸字节流 / BLE 通知分包均可，内部重组）
 * @param scope 会话生命周期作用域，断连时取消即可终止收包循环
 */
class ChameleonProtocol(
    private val send: suspend (ByteArray) -> Unit,
    incoming: Flow<ByteArray>,
    private val scope: CoroutineScope,
) {
    private val decoder = FrameCodec.Decoder()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Response>>()
    private val commandMutexes = ConcurrentHashMap<Int, Mutex>()
    private val sendMutex = Mutex()

    init {
        scope.launch {
            incoming.collect { bytes ->
                for (frame in decoder.feed(bytes)) {
                    // 协议日志：RX 帧
                    ProtocolLogger.log(tx = false, cmd = frame.cmd, status = frame.status, dataLen = frame.data.size)
                    pending.remove(frame.cmd)?.complete(Response(frame.cmd, frame.status, frame.data))
                }
            }
        }
    }

    /**
     * 发送指令并等待同指令码的响应。支持同时等待不同 cmd。
     *
     * @throws CommandTimeoutException 超时
     * @throws InvalidCommandException 设备返回 INVALID_CMD
     */
    suspend fun sendCommand(
        cmd: Command,
        data: ByteArray = ByteArray(0),
        timeoutMs: Long = 3000,
    ): Response {
        val commandMutex = commandMutexes.computeIfAbsent(cmd.code) { Mutex() }
        return commandMutex.withLock {
            val deferred = CompletableDeferred<Response>()
            pending[cmd.code] = deferred
            try {
                sendMutex.withLock {
                    send(FrameCodec.encode(cmd.code, 0, data))
                }
                // 协议日志：TX 帧
                ProtocolLogger.log(tx = true, cmd = cmd.code, status = null, dataLen = data.size)
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw CommandTimeoutException(cmd.code)
            } finally {
                pending.remove(cmd.code, deferred)
            }
        }
    }

    /**
     * 发送指令并要求设备返回成功状态（[expectStatus] 默认 [Status.SUCCESS]）。
     */
    suspend fun sendCommandExpect(
        cmd: Command,
        data: ByteArray = ByteArray(0),
        timeoutMs: Long = 3000,
        expectStatus: Status = Status.SUCCESS,
    ): Response {
        val resp = sendCommand(cmd, data, timeoutMs)
        if (resp.status == Status.INVALID_CMD.code) throw InvalidCommandException(cmd.code)
        if (resp.status != expectStatus.code) throw DeviceStatusException(cmd.code, resp.status)
        return resp
    }
}
