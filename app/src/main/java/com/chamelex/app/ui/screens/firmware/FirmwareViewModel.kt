package com.chamelex.app.ui.screens.firmware

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chamelex.app.R
import com.chamelex.app.dfu.DfuController
import com.chamelex.app.dfu.FirmwareAsset
import com.chamelex.app.dfu.FirmwareRelease
import com.chamelex.app.dfu.downloadFirmware
import com.chamelex.app.dfu.fetchLatestRelease
import com.chamelex.app.dfu.isValidDfuZip
import com.chamelex.connection.ConnState
import com.chamelex.connection.ConnectionManager
import com.chamelex.connection.DeviceInfo
import com.chamelex.connection.ble.scanDfuDevice
import com.chamelex.protocol.DeviceModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

/** 固件文件就绪状态。 */
data class FirmwareFile(
    val file: File,
    val sourceDesc: String,
    /** 目标型号（ultra/lite），null=未知（本地文件） */
    val forModel: DeviceModel?,
)

/** 下载/获取状态。 */
sealed interface FetchState {
    data object Idle : FetchState
    data object LoadingRelease : FetchState
    data class ReleaseLoaded(val release: FirmwareRelease) : FetchState
    data class ReleaseError(val cause: String?) : FetchState
    data class Downloading(val downloaded: Long, val total: Long) : FetchState
    data object DownloadCancelled : FetchState
    data class DownloadError(val cause: String?) : FetchState
}

/** 刷机流程状态。 */
sealed interface FlashStep {
    data object Idle : FlashStep
    data object EnteringBootloader : FlashStep
    data object WaitingDfuDevice : FlashStep
    data object Flashing : FlashStep
    data object Done : FlashStep
    data class Error(@StringRes val msgRes: Int, val cause: String?) : FlashStep
}

@HiltViewModel
class FirmwareViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    val dfuController: DfuController,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state
    val deviceInfo: StateFlow<DeviceInfo?> = connectionManager.deviceInfo

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState

    private val _firmwareFile = MutableStateFlow<FirmwareFile?>(null)
    val firmwareFile: StateFlow<FirmwareFile?> = _firmwareFile

    private val _flashStep = MutableStateFlow<FlashStep>(FlashStep.Idle)
    val flashStep: StateFlow<FlashStep> = _flashStep

    val dfuState = dfuController.state

    private var downloadJob: Job? = null
    private var flashJob: Job? = null

    private val dfuDir: File get() = File(context.filesDir, "dfu").apply { mkdirs() }

    // ---- 固件来源 ----

    fun loadRelease() {
        if (_fetchState.value is FetchState.LoadingRelease) return
        _fetchState.value = FetchState.LoadingRelease
        viewModelScope.launch {
            try {
                _fetchState.value = FetchState.ReleaseLoaded(fetchLatestRelease())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _fetchState.value = FetchState.ReleaseError(e.message)
            }
        }
    }

    /** 下载对应型号 dfu zip。 */
    fun downloadAsset(model: DeviceModel?) {
        val release = (_fetchState.value as? FetchState.ReleaseLoaded)?.release ?: return
        val wantedName = when (model) {
            DeviceModel.LITE -> "lite-dfu-app.zip"
            else -> "ultra-dfu-app.zip"
        }
        val asset = release.assets.firstOrNull { it.name == wantedName }
            ?: release.assets.firstOrNull { it.name.endsWith("-dfu-app.zip") }
        if (asset == null) {
            _fetchState.value = FetchState.DownloadError("release 中未找到 $wantedName")
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                val dest = File(dfuDir, asset.name)
                downloadFirmware(asset, dest) { done, total ->
                    _fetchState.value = FetchState.Downloading(done, total)
                }
                if (!isValidDfuZip(dest)) {
                    dest.delete()
                    _fetchState.value = FetchState.DownloadError("下载的文件不是有效的 DFU 包")
                    return@launch
                }
                _firmwareFile.value = FirmwareFile(
                    file = dest,
                    sourceDesc = "${release.tagName} · ${asset.name}",
                    forModel = model,
                )
                _fetchState.value = FetchState.Idle
            } catch (e: CancellationException) {
                _fetchState.value = FetchState.DownloadCancelled
                throw e
            } catch (e: Exception) {
                _fetchState.value = FetchState.DownloadError(e.message)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** 本地 SAF 选择的 zip。 */
    fun onLocalZipPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val dest = withContext(Dispatchers.IO) {
                    val f = File(dfuDir, "local-firmware.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法读取所选文件")
                    f
                }
                if (!isValidDfuZip(dest)) {
                    dest.delete()
                    _fetchState.value = FetchState.DownloadError("所选文件不是有效的 DFU 包")
                    return@launch
                }
                _firmwareFile.value = FirmwareFile(dest, "本地文件", forModel = null)
                _fetchState.value = FetchState.Idle
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _fetchState.value = FetchState.DownloadError(e.message)
            }
        }
    }

    // ---- 刷机流程 ----

    /** 流程 A：APP 模式已连接设备 → ENTER_BOOTLOADER → 扫 DFU → 刷写。 */
    fun flashConnectedDevice() {
        val fw = _firmwareFile.value ?: return
        if (flashJob?.isActive == true) return
        flashJob = viewModelScope.launch {
            try {
                _flashStep.value = FlashStep.EnteringBootloader
                val expectedName = when {
                    deviceInfo.value?.model?.contains("Lite") == true -> "CL"
                    else -> "CU"
                }
                // 设备可能不应答直接重启，忽略响应异常
                runCatching {
                    kotlinx.coroutines.withTimeout(1500) {
                        connectionManager.protocol?.enterBootloader()
                    }
                }
                delay(500)
                // 主动断开，避免断线提示干扰
                connectionManager.disconnect()

                _flashStep.value = FlashStep.WaitingDfuDevice
                val device = withTimeoutOrNull(60_000) {
                    scanDfuDevice(context, expectedName)
                }
                if (device == null) {
                    _flashStep.value = FlashStep.Error(R.string.dfu_not_found, null)
                    return@launch
                }
                _flashStep.value = FlashStep.Flashing
                dfuController.start(device.address, device.name, fw.file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _flashStep.value = FlashStep.Error(R.string.dfu_enter_failed, e.message)
            }
        }
    }

    /** 流程 B：设备已在 DFU（恢复）模式 → 直接刷写。 */
    fun flashDfuDevice(address: String, name: String) {
        val fw = _firmwareFile.value ?: return
        _flashStep.value = FlashStep.Flashing
        dfuController.start(address, name, fw.file)
    }

    /** 退出刷机模式：向 bootloader 发 abort（设备复位回 APP 或保持 DFU，UI 另有重启提示）。 */
    fun exitDfuMode() {
        dfuController.abort()
    }

    fun cancelFlash() {
        flashJob?.cancel()
        dfuController.abort()
    }

    fun resetFlash() {
        _flashStep.value = FlashStep.Idle
        dfuController.reset()
    }
}
