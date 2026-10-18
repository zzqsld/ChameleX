package com.chamelex.app

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chamelex.connection.ConnState
import com.chamelex.connection.ConnectionEvent
import com.chamelex.connection.ConnectionManager
import com.chamelex.connection.DeviceInfo
import com.chamelex.connection.ble.BleDevice
import com.chamelex.connection.ble.BleScanner
import com.chamelex.connection.usb.UsbDeviceEntry
import com.chamelex.protocol.AnimationMode
import com.chamelex.protocol.ButtonPressFunction
import com.chamelex.protocol.ButtonType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state
    val deviceInfo: StateFlow<DeviceInfo?> = connectionManager.deviceInfo
    val events: SharedFlow<ConnectionEvent> = connectionManager.events

    private val scanner = BleScanner(context)

    private val foundDevices = MutableStateFlow<Map<String, BleDevice>>(emptyMap())
    val scanResults: StateFlow<List<BleDevice>> = foundDevices
        .map { it.values.sortedByDescending(BleDevice::rssi) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _usbDevices = MutableStateFlow<List<UsbDeviceEntry>>(emptyList())
    val usbDevices: StateFlow<List<UsbDeviceEntry>> = _usbDevices

    private var scanJob: Job? = null

    // ---- 硬件设置 ----

    private val _hwSettings = MutableStateFlow<HwSettings?>(null)
    val hwSettings: StateFlow<HwSettings?> = _hwSettings

    init {
        viewModelScope.launch {
            connState.collect { state ->
                if (state is ConnState.Connected) loadHwSettings() else _hwSettings.value = null
            }
        }
    }

    fun loadHwSettings() {
        val cmd = connectionManager.protocol ?: return
        viewModelScope.launch {
            runCatching {
                _hwSettings.value = HwSettings(
                    animation = cmd.getAnimationMode(),
                    shortA = cmd.getButtonPressConfig(ButtonType.A),
                    shortB = cmd.getButtonPressConfig(ButtonType.B),
                    longA = cmd.getLongButtonPressConfig(ButtonType.A),
                    longB = cmd.getLongButtonPressConfig(ButtonType.B),
                )
            }
        }
    }

    fun setAnimation(mode: AnimationMode) {
        val cmd = connectionManager.protocol ?: return
        viewModelScope.launch {
            runCatching {
                cmd.setAnimationMode(mode)
                _hwSettings.value = _hwSettings.value?.copy(animation = mode)
            }
        }
    }

    fun setButtonConfig(button: ButtonType, longPress: Boolean, function: ButtonPressFunction) {
        val cmd = connectionManager.protocol ?: return
        viewModelScope.launch {
            runCatching {
                if (longPress) cmd.setLongButtonPressConfig(button, function)
                else cmd.setButtonPressConfig(button, function)
                _hwSettings.value = when {
                    longPress && button == ButtonType.A -> _hwSettings.value?.copy(longA = function)
                    longPress -> _hwSettings.value?.copy(longB = function)
                    button == ButtonType.A -> _hwSettings.value?.copy(shortA = function)
                    else -> _hwSettings.value?.copy(shortB = function)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        if (scanJob?.isActive == true) return
        foundDevices.value = emptyMap()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { _scanning.value = false }
                .collect { device ->
                    foundDevices.value = foundDevices.value + (device.address to device)
                }
        }
    }

    fun stopBleScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    fun refreshUsbDevices() {
        _usbDevices.value = runCatching { connectionManager.listUsbDevices() }.getOrDefault(emptyList())
    }

    fun connectBle(device: BleDevice) {
        android.util.Log.d("ChameleX", "connectBle 点击: ${device.name} ${device.address}")
        stopBleScan()
        viewModelScope.launch {
            runCatching { connectionManager.connectBle(device.device, device.name) }
                .onFailure { android.util.Log.e("ChameleX", "connectBle 失败", it) }
        }
    }

    fun connectUsb(entry: UsbDeviceEntry) {
        android.util.Log.d("ChameleX", "connectUsb 点击: ${entry.displayName}")
        viewModelScope.launch {
            runCatching { connectionManager.connectUsb(entry) }
                .onFailure { android.util.Log.e("ChameleX", "connectUsb 失败", it) }
        }
    }

    fun disconnect() {
        viewModelScope.launch { connectionManager.disconnect() }
    }

    override fun onCleared() {
        stopBleScan()
    }
}

/** 硬件设置快照。 */
data class HwSettings(
    val animation: AnimationMode?,
    val shortA: ButtonPressFunction?,
    val shortB: ButtonPressFunction?,
    val longA: ButtonPressFunction?,
    val longB: ButtonPressFunction?,
)
