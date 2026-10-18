@file:Suppress("OVERRIDE_DEPRECATION") // Nordic BLE 2.10 将 BleManagerGattCallback 回调标为废弃，但其文档示例仍用此模式

package com.chamelex.connection.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.chamelex.connection.ConnState
import com.chamelex.connection.Transport
import com.chamelex.connection.TransportKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.observer.ConnectionObserver
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE 传输：Nordic UART Service (NUS)。
 * 连接后协商 MTU 247，TX 通知上行（分包由 protocol 层重组），RX 按 (MTU-3) 分片写入。
 */
@SuppressLint("MissingPermission")
class BleTransport(
    context: Context,
    private val device: BluetoothDevice,
    override val deviceName: String,
) : Transport {

    companion object {
        const val REQUEST_MTU = 247
        const val DEFAULT_MTU = 23
    }

    override val kind = TransportKind.BLE

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    override val state: StateFlow<ConnState> = _state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = _incoming

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()

    @Volatile
    private var mtu = DEFAULT_MTU

    private var connectDeferred: CompletableDeferred<Unit>? = null

    // BleManager 构造时会立刻回调 getGattCallback()，此时匿名对象的属性尚未初始化，
    // 因此回调实例缓存在外层 BleTransport（其初始化先于 manager 属性），避免 NPE。
    // BleManagerGattCallback 是 BleManager 的 protected 类型，外层无法引用，故用 Any? 占位。
    private var gattCallbackHolder: Any? = null

    private val manager = object : BleManager(context) {
        private var rxCharacteristic: BluetoothGattCharacteristic? = null
        private var txCharacteristic: BluetoothGattCharacteristic? = null

        val rx: BluetoothGattCharacteristic? get() = rxCharacteristic

        fun emitIncoming(bytes: ByteArray) {
            _incoming.tryEmit(bytes)
        }

        override fun getGattCallback(): BleManagerGattCallback {
            return (gattCallbackHolder as? BleManagerGattCallback)
                ?: GattCallback().also { gattCallbackHolder = it }
        }

        private inner class GattCallback : @Suppress("DEPRECATION") BleManagerGattCallback() {
            override fun initialize() {
                requestMtu(REQUEST_MTU)
                    .with { _, newMtu -> this@BleTransport.mtu = newMtu }
                    .enqueue()
                txCharacteristic?.let { tx ->
                    setNotificationCallback(tx).with { _, data ->
                        data.value?.let { emitIncoming(it) }
                    }
                    enableNotifications(tx).enqueue()
                }
            }

            override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                val service = gatt.getService(BleUuids.NUS_SERVICE) ?: return false
                rxCharacteristic = service.getCharacteristic(BleUuids.NUS_RX)
                txCharacteristic = service.getCharacteristic(BleUuids.NUS_TX)
                val rx = rxCharacteristic
                val tx = txCharacteristic
                if (rx == null || tx == null) return false
                val rxProps = rx.properties
                val txProps = tx.properties
                return (rxProps and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ||
                        rxProps and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) &&
                        txProps and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }

            override fun onServicesInvalidated() {
                rxCharacteristic = null
                txCharacteristic = null
            }
        }

        suspend fun writeChunk(chunk: ByteArray) {
            val characteristic = rx ?: throw IllegalStateException("RX 特征不可用")
            suspendCancellableCoroutine { cont ->
                writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
                    .done { cont.resume(Unit) }
                    .fail { _, status ->
                        cont.resumeWithException(IllegalStateException("BLE 写入失败，status=$status"))
                    }
                    .invalid { cont.resumeWithException(IllegalStateException("BLE 写入请求无效")) }
                    .enqueue()
            }
        }
    }

    init {
        manager.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                _state.value = ConnState.Connecting(kind)
            }

            override fun onDeviceConnected(device: BluetoothDevice) {}

            override fun onDeviceReady(device: BluetoothDevice) {
                _state.value = ConnState.Connected(deviceName, kind)
                connectDeferred?.complete(Unit)
                connectDeferred = null
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                android.util.Log.e("ChameleX", "BLE onDeviceFailedToConnect reason=$reason")
                _state.value = ConnState.Error("BLE 连接失败（reason=$reason）")
                connectDeferred?.completeExceptionally(IllegalStateException("BLE 连接失败（reason=$reason）"))
                connectDeferred = null
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {}

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                _state.value = ConnState.Disconnected
                connectDeferred?.completeExceptionally(IllegalStateException("BLE 连接断开"))
                connectDeferred = null
            }
        })
    }

    override suspend fun connect() {
        if (_state.value is ConnState.Connected) return
        android.util.Log.d("ChameleX", "BleTransport.connect: 开始连接 ${device.address}")
        _state.value = ConnState.Connecting(kind)
        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred
        manager.connect(device)
            .timeout(15_000)
            .retry(1, 300)
            .enqueue()
        deferred.await()
        android.util.Log.d("ChameleX", "BleTransport.connect: onDeviceReady，MTU=$mtu")
    }

    override suspend fun disconnect() {
        connectDeferred?.completeExceptionally(IllegalStateException("主动断开"))
        connectDeferred = null
        manager.disconnect().enqueue()
    }

    override suspend fun send(bytes: ByteArray) {
        check(_state.value is ConnState.Connected) { "BLE 未连接" }
        val chunkSize = (mtu - 3).coerceAtLeast(DEFAULT_MTU - 3)
        sendMutex.withLock {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + chunkSize, bytes.size)
                manager.writeChunk(bytes.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    fun close() {
        manager.close()
        scope.cancel()
    }
}
