package com.chamelex.app.dfu

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceController
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** DFU 刷写状态。 */
sealed interface DfuFlashState {
    data object Idle : DfuFlashState
    data class Working(
        val stage: String,
        val percent: Int = 0,
        val speedKbps: Float = 0f,
        val part: Int = 1,
        val partsTotal: Int = 1,
    ) : DfuFlashState

    data object Completed : DfuFlashState
    data object Aborted : DfuFlashState
    data class Error(val code: Int, val message: String) : DfuFlashState
}

/**
 * DFU 刷写控制（独立于 ConnectionManager：DFU 模式设备不是 Chameleon 协议设备，
 * 不走 Transport / ChameleonProtocol）。
 */
@Singleton
class DfuController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<DfuFlashState>(DfuFlashState.Idle)
    val state: StateFlow<DfuFlashState> = _state

    private var controller: DfuServiceController? = null

    private val listener = object : DfuProgressListenerAdapter() {
        override fun onDeviceConnecting(deviceAddress: String) {
            _state.value = DfuFlashState.Working("连接 DFU 设备中")
        }

        override fun onDeviceConnected(deviceAddress: String) {
            _state.value = DfuFlashState.Working("已连接，准备刷写")
        }

        override fun onDfuProcessStarting(deviceAddress: String) {
            _state.value = DfuFlashState.Working("启动 DFU 流程")
        }

        override fun onDfuProcessStarted(deviceAddress: String) {
            _state.value = DfuFlashState.Working("刷写开始")
        }

        override fun onEnablingDfuMode(deviceAddress: String) {
            _state.value = DfuFlashState.Working("切换 DFU 模式")
        }

        override fun onFirmwareValidating(deviceAddress: String) {
            _state.value = DfuFlashState.Working("校验固件")
        }

        override fun onDeviceDisconnecting(deviceAddress: String) {
            _state.value = DfuFlashState.Working("设备重启中")
        }

        override fun onProgressChanged(
            deviceAddress: String,
            percent: Int,
            speed: Float,
            avgSpeed: Float,
            currentPart: Int,
            partsTotal: Int,
        ) {
            _state.value = DfuFlashState.Working(
                stage = "刷写中",
                percent = percent,
                speedKbps = avgSpeed,
                part = currentPart,
                partsTotal = partsTotal,
            )
        }

        override fun onDfuCompleted(deviceAddress: String) {
            _state.value = DfuFlashState.Completed
        }

        override fun onDfuAborted(deviceAddress: String) {
            _state.value = DfuFlashState.Aborted
        }

        override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
            _state.value = DfuFlashState.Error(error, dfuErrorText(error, errorType, message))
        }
    }

    init {
        DfuServiceListenerHelper.registerProgressListener(context, listener)
    }

    /** 开始刷写（zip 路径）。 */
    fun start(address: String, name: String?, zip: File) {
        _state.value = DfuFlashState.Working("准备刷写")
        DfuServiceInitiator.createDfuNotificationChannel(context)
        controller = DfuServiceInitiator(address)
            .setDeviceName(name)
            .setKeepBond(false)
            // ChameleonUltra bootloader 对丢包容忍度低，给每个数据对象留准备时间
            .setPrepareDataObjectDelay(300L)
            .setZip(zip.absolutePath)
            .start(context, DfuService::class.java)
    }

    /** 中止刷写 / 向 bootloader 发 abort（设备通常会复位）。 */
    fun abort() {
        controller?.abort()
    }

    fun reset() {
        _state.value = DfuFlashState.Idle
    }
}

/** DfuError 中文化（常见码，其余原样展示）。 */
fun dfuErrorText(error: Int, errorType: Int, message: String?): String {
    val base = message ?: "未知错误"
    val hint = when (errorType) {
        0 -> "（协议错误）"
        1 -> "（通信错误）"
        2 -> "（GATT 错误）"
        else -> ""
    }
    val advice = when (error and 0xFF) {
        0x01 -> "：固件 zip 无效或不完整，请重新下载"
        0x02 -> "：固件与设备不匹配，请确认型号（Ultra/Lite）"
        0x08 -> "：设备拒绝该操作，可能电量不足或固件不兼容"
        else -> ""
    }
    return "$base$hint$advice（错误码 $error/$errorType）"
}
