package com.chamelex.connection.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.chamelex.connection.ConnState
import com.chamelex.connection.Transport
import com.chamelex.connection.TransportKind
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 枚举到的一个可用 USB 串口设备。 */
data class UsbDeviceEntry(
    val device: UsbDevice,
    val driver: UsbSerialDriver,
    val displayName: String,
)

/**
 * USB OTG 传输：CDC 串口（CH343/CP210x 等，自动探测驱动），115200 8N1，DTR=true。
 */
class UsbTransport(
    private val context: Context,
    private val entry: UsbDeviceEntry,
) : Transport {

    companion object {
        const val ACTION_USB_PERMISSION = "com.chamelex.app.USB_PERMISSION"
        const val BAUD_RATE = 115200
        const val WRITE_TIMEOUT_MS = 2000

        /** 枚举当前已接入的 USB 串口设备。 */
        fun listDevices(context: Context): List<UsbDeviceEntry> {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return UsbSerialProber.getDefaultProber().findAllDrivers(manager).map { driver ->
                UsbDeviceEntry(
                    device = driver.device,
                    driver = driver,
                    displayName = "${driver.device.productName ?: "USB 串口"} (${driver.javaClass.simpleName})",
                )
            }
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    override val kind = TransportKind.USB

    override val deviceName: String = entry.displayName

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    override val state: StateFlow<ConnState> = _state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = _incoming

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var permissionReceiver: BroadcastReceiver? = null

    override suspend fun connect() {
        if (_state.value is ConnState.Connected) return
        _state.value = ConnState.Connecting(kind)
        try {
            if (!usbManager.hasPermission(entry.device)) {
                requestPermission()
            }
            openPort()
            _state.value = ConnState.Connected(deviceName, kind)
        } catch (e: Exception) {
            closeQuietly()
            _state.value = ConnState.Error(e.message ?: "USB 连接失败")
            throw e
        }
    }

    private suspend fun requestPermission() {
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    unregisterPermissionReceiver()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(SecurityException("USB 权限被拒绝"))
                    }
                }
            }
            permissionReceiver = receiver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
            }
            cont.invokeOnCancellation { unregisterPermissionReceiver() }

            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            // 系统需要往 intent 里回填授权结果，必须为 MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(entry.device, pendingIntent)
        }
    }

    private fun unregisterPermissionReceiver() {
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
    }

    private fun openPort() {
        val connection = usbManager.openDevice(entry.device)
            ?: throw IllegalStateException("无法打开 USB 设备")
        val serialPort = entry.driver.ports.firstOrNull()
            ?: throw IllegalStateException("设备无可用串口")
        serialPort.open(connection)
        serialPort.setParameters(
            BAUD_RATE,
            UsbSerialPort.DATABITS_8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE,
        )
        runCatching { serialPort.dtr = true }
        port = serialPort

        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                _incoming.tryEmit(data)
            }

            override fun onRunError(e: Exception) {
                closeQuietly()
                _state.value = ConnState.Error("USB 读取异常：${e.message}")
            }
        }
        ioManager = SerialInputOutputManager(serialPort, listener).also { it.start() }
    }

    override suspend fun disconnect() {
        closeQuietly()
        _state.value = ConnState.Disconnected
    }

    override suspend fun send(bytes: ByteArray) {
        val p = port ?: throw IllegalStateException("USB 未连接")
        check(_state.value is ConnState.Connected) { "USB 未连接" }
        p.write(bytes, WRITE_TIMEOUT_MS)
    }

    private fun closeQuietly() {
        unregisterPermissionReceiver()
        ioManager?.let { runCatching { it.stop() } }
        ioManager = null
        port?.let { runCatching { it.close() } }
        port = null
    }
}
