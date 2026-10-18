package com.chamelex.connection

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.chamelex.connection.ble.BleTransport
import com.chamelex.connection.usb.UsbDeviceEntry
import com.chamelex.connection.usb.UsbTransport
import com.chamelex.protocol.ChameleonCommands
import com.chamelex.protocol.ChameleonProtocol
import com.chamelex.protocol.CrackCommands
import com.chamelex.protocol.DetectionCommands
import com.chamelex.protocol.DeviceModel
import com.chamelex.protocol.Mf1Commands
import com.chamelex.protocol.ReaderCommands
import com.chamelex.protocol.SlotCommands
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 连接成功后读取的设备信息。 */
data class DeviceInfo(
    val appVersion: String,
    val gitVersion: String,
    val model: String,
    val chipId: String,
    val batteryMv: Int,
    val batteryPercent: Int,
    val isReaderMode: Boolean,
)

/** 全局事件（断线提示等），UI 层收集后弹 Snackbar。 */
sealed interface ConnectionEvent {
    data class Lost(val message: String) : ConnectionEvent
    data class Failure(val message: String) : ConnectionEvent
}

/**
 * 全局连接管理（Singleton）：持有当前 Transport 与协议会话，
 * 连接成功后自动读取设备信息。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _transport = MutableStateFlow<Transport?>(null)

    val state: StateFlow<ConnState> = _transport
        .flatMapLatest { it?.state ?: flowOf(ConnState.Disconnected) }
        .stateIn(scope, SharingStarted.Eagerly, ConnState.Disconnected)

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    private val _events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ConnectionEvent> = _events

    /** 当前协议会话（已连接时可用）。 */
    @Volatile
    var protocol: ChameleonCommands? = null
        private set

    /** 卡槽指令会话（已连接时可用）。 */
    @Volatile
    var slotCommands: SlotCommands? = null
        private set

    /** 读卡器指令会话（已连接时可用）。 */
    @Volatile
    var readerCommands: ReaderCommands? = null
        private set

    /** M1 密钥读取指令会话（已连接时可用）。 */
    @Volatile
    var mf1Commands: Mf1Commands? = null
        private set

    /** M1 破解采集指令会话（已连接时可用）。 */
    @Volatile
    var crackCommands: CrackCommands? = null
        private set

    /** M1 侦测指令会话（已连接时可用）。 */
    @Volatile
    var detectionCommands: DetectionCommands? = null
        private set

    private var protocolScope: CoroutineScope? = null
    private var stateWatchJob: Job? = null

    fun listUsbDevices(): List<UsbDeviceEntry> = UsbTransport.listDevices(context)

    suspend fun connectBle(device: BluetoothDevice, name: String) {
        switchTransport(BleTransport(context, device, name))
    }

    suspend fun connectUsb(entry: UsbDeviceEntry) {
        switchTransport(UsbTransport(context, entry))
    }

    private suspend fun switchTransport(newTransport: Transport) {
        android.util.Log.d("ChameleX", "switchTransport: ${newTransport.deviceName}")
        mutex.withLock {
            disconnectInternal()
            _transport.value = newTransport
            watchState(newTransport)
        }
        try {
            newTransport.connect()
        } catch (e: Exception) {
            android.util.Log.e("ChameleX", "transport.connect 失败", e)
            _events.tryEmit(ConnectionEvent.Failure(e.message ?: "连接失败"))
            mutex.withLock {
                if (_transport.value === newTransport) disconnectInternal()
            }
            throw e
        }
        android.util.Log.d("ChameleX", "transport.connect 成功，读取设备信息")
        // 连接成功，读取设备信息
        try {
            readDeviceInfo(newTransport)
        } catch (e: Exception) {
            _events.tryEmit(ConnectionEvent.Failure("读取设备信息失败：${e.message}"))
        }
    }

    private suspend fun readDeviceInfo(transport: Transport) {
        val pScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        protocolScope = pScope
        val chameleonProtocol = ChameleonProtocol(
            send = { transport.send(it) },
            incoming = transport.incoming,
            scope = pScope,
        )
        val commands = ChameleonCommands(chameleonProtocol)
        protocol = commands
        slotCommands = SlotCommands(chameleonProtocol)
        readerCommands = ReaderCommands(chameleonProtocol)
        mf1Commands = Mf1Commands(chameleonProtocol)
        crackCommands = CrackCommands(chameleonProtocol)
        detectionCommands = DetectionCommands(chameleonProtocol)

        val (major, minor) = commands.getAppVersion()
        val git = commands.getGitVersion()
        val model = when (commands.getDeviceModel()) {
            DeviceModel.ULTRA -> "ChameleonUltra"
            DeviceModel.LITE -> "ChameleonLite"
            null -> "未知型号"
        }
        val chipId = commands.getDeviceChipId()
        // 固件的电压/百分比由 ADC 定时采样更新，开机后首次采样前恒为 0（见 ble_main.c），
        // 读到 0/0 时最多重试 3 次（间隔 1 秒），仍为 0/0 则显示「电量未知」。
        var battery = 0 to 0
        repeat(3) { attempt ->
            battery = commands.getBatteryInfo()
            if (battery.first != 0 || battery.second != 0) return@repeat
            if (attempt < 2) kotlinx.coroutines.delay(1000)
        }
        val (mv, percent) = battery
        val readerMode = commands.isDeviceReaderMode()
        _deviceInfo.value = DeviceInfo(
            appVersion = "$major.$minor",
            gitVersion = git,
            model = model,
            chipId = chipId,
            batteryMv = mv,
            batteryPercent = percent,
            isReaderMode = readerMode,
        )
    }

    private fun watchState(transport: Transport) {
        stateWatchJob?.cancel()
        stateWatchJob = scope.launch {
            var wasConnected = false
            transport.state.collect { s ->
                if (s is ConnState.Connected) wasConnected = true
                // 断线回落：异常断开（非主动 disconnect）时清理并提示
                if (wasConnected && (s is ConnState.Disconnected || s is ConnState.Error)) {
                    val msg = when (s) {
                        is ConnState.Error -> "连接异常断开：${s.message}"
                        else -> "设备已断开"
                    }
                    mutex.withLock {
                        if (_transport.value === transport) disconnectInternal()
                    }
                    // 注意 disconnectInternal 会取消当前 job，此后不能再挂起，用 tryEmit
                    _events.tryEmit(ConnectionEvent.Lost(msg))
                }
            }
        }
    }

    suspend fun disconnect() {
        mutex.withLock { disconnectInternal() }
    }

    private fun disconnectInternal() {
        stateWatchJob?.cancel()
        stateWatchJob = null
        protocol = null
        slotCommands = null
        readerCommands = null
        mf1Commands = null
        crackCommands = null
        detectionCommands = null
        protocolScope?.cancel()
        protocolScope = null
        _deviceInfo.value = null
        val t = _transport.value ?: return
        _transport.value = null
        scope.launch {
            runCatching { t.disconnect() }
            (t as? BleTransport)?.close()
        }
    }
}
