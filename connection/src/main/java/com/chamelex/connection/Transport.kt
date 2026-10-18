package com.chamelex.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 连接通道类型。 */
enum class TransportKind { BLE, USB }

/** 连接状态。 */
sealed interface ConnState {
    data object Disconnected : ConnState
    data class Connecting(val kind: TransportKind) : ConnState
    data class Connected(val deviceName: String, val kind: TransportKind) : ConnState
    data class Error(val message: String) : ConnState
}

/**
 * 双通道（BLE / USB OTG）统一传输抽象。
 */
interface Transport {
    /** 通道类型（BLE / USB）。 */
    val kind: TransportKind

    /** 当前连接状态。 */
    val state: StateFlow<ConnState>

    /** 上行字节流（未组帧的原始字节，组帧由 protocol 层负责）。 */
    val incoming: Flow<ByteArray>

    /** 已连接设备的展示名。 */
    val deviceName: String

    suspend fun connect()

    suspend fun disconnect()

    /** 发送原始字节（调用方负责组帧与分包）。 */
    suspend fun send(bytes: ByteArray)
}
