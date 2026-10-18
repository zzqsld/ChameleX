package com.chamelex.connection.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** 扫描到的 BLE 设备。 */
data class BleDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    /** 是否处于 DFU（恢复/刷机）模式。 */
    val isDfu: Boolean = false,
)

object BleUuids {
    /** Nordic UART Service */
    val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** RX 特征（APP -> 设备 写入） */
    val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** TX 特征（设备 -> APP 通知） */
    val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Nordic legacy DFU service */
    val DFU_SERVICE: UUID = UUID.fromString("00001530-1212-EFDE-1523-785FEABCD123")

    /** nRF5 SDK secure DFU（bootloader 广播的 16 位 UUID 0xFE59） */
    val SECURE_DFU_SERVICE: UUID = UUID.fromString("0000FE59-0000-1000-8000-00805F9B34FB")

    val NAME_PREFIXES = listOf("ChameleonUltra", "ChameleonLite")

    /** DFU 模式广播名前缀（Ultra / Lite，实际广播形如 "CU-B49A"，带 MAC 后缀）。 */
    val DFU_NAMES = setOf("CU", "CL")

    /** DFU 广播名匹配："CU"/"CL" 或 "CU-XXXX"/"CL-XXXX"（XXXX 为 MAC 后两位 hex）。 */
    val DFU_NAME_REGEX = Regex("^C[UL](-[0-9A-Fa-f]{4})?$")
}

/**
 * BLE 扫描器：发现 APP 模式设备（NUS UUID / Chameleon 名）与 DFU 模式设备（DFU UUID / CU、CL 名）。
 * 调用前需确保已授予 BLUETOOTH_SCAN 权限。
 */
class BleScanner(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @SuppressLint("MissingPermission")
    fun scan(verbose: Boolean = false): Flow<BleDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("蓝牙不可用"))
            return@callbackFlow
        }
        val seen = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                if (verbose && seen.add(result.device.address)) {
                    android.util.Log.d(
                        "ChameleX",
                        "扫描到设备: name=$name addr=${result.device.address} uuids=$uuids rssi=${result.rssi}",
                    )
                }
                val isDfu = BleUuids.DFU_SERVICE in uuids || BleUuids.SECURE_DFU_SERVICE in uuids ||
                        (name != null && BleUuids.DFU_NAME_REGEX.matches(name))
                val isApp = BleUuids.NUS_SERVICE in uuids ||
                        (name != null && BleUuids.NAME_PREFIXES.any { name.startsWith(it) })
                if (!isDfu && !isApp) return
                trySend(
                    BleDevice(
                        device = result.device,
                        name = name ?: result.device.address,
                        address = result.device.address,
                        rssi = result.rssi,
                        isDfu = isDfu,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE 扫描失败，错误码 $errorCode"))
            }
        }
        // 不过滤 service UUID，CU/CL 的 DFU 广播不一定带完整 UUID 列表，统一在回调里判定
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
