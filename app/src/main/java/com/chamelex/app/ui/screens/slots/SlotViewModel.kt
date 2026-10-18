package com.chamelex.app.ui.screens.slots

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import com.chamelex.app.R
import com.chamelex.app.ui.screens.data.WriteUiState
import com.chamelex.app.ui.screens.data.isLfDump
import com.chamelex.app.ui.screens.data.lfType
import com.chamelex.app.ui.screens.data.toDumpData
import com.chamelex.app.worker.DumpWriteWorker
import com.chamelex.app.worker.WriteResult
import com.chamelex.connection.ConnState
import com.chamelex.connection.ConnectionManager
import com.chamelex.data.db.DumpDao
import com.chamelex.data.db.DumpEntity
import com.chamelex.protocol.parseDump
import com.chamelex.protocol.parseLfIdFile
import com.chamelex.protocol.looksLikeLfIdFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import com.chamelex.protocol.EmulatorConfig
import com.chamelex.protocol.MifareClassicWriteMode
import com.chamelex.protocol.SlotCommands
import com.chamelex.protocol.SlotInfo
import com.chamelex.protocol.SlotNumber
import com.chamelex.protocol.TagSenseType
import com.chamelex.protocol.TagSpecificType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 操作失败事件，UI 层格式化后弹 Snackbar。 */
data class SlotOpFailed(@StringRes val opRes: Int, val cause: String?)

/** 模拟参数页状态。 */
sealed interface EmuConfigState {
    data object Idle : EmuConfigState
    data object Loading : EmuConfigState
    data class M1(val config: EmulatorConfig) : EmuConfigState
    data class Ntag(val uidMagic: Boolean) : EmuConfigState
    data object Unsupported : EmuConfigState
    data class Failed(val cause: String?) : EmuConfigState
}

@HiltViewModel
class SlotViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val dumpDao: DumpDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** dump 列表（写入数据来源选择用）。 */
    val savedDumps: StateFlow<List<DumpEntity>> = dumpDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connState: StateFlow<ConnState> = connectionManager.state

    private val _slots = MutableStateFlow<List<SlotInfo>>(emptyList())
    val slots: StateFlow<List<SlotInfo>> = _slots

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _events = MutableSharedFlow<SlotOpFailed>(extraBufferCapacity = 8)
    val events: SharedFlow<SlotOpFailed> = _events

    private val _emuState = MutableStateFlow<EmuConfigState>(EmuConfigState.Idle)
    val emuState: StateFlow<EmuConfigState> = _emuState

    private fun commands(): SlotCommands? {
        val cmd = connectionManager.slotCommands
        if (cmd == null) _events.tryEmit(SlotOpFailed(R.string.op_read_slots, "device not connected"))
        return cmd
    }

    fun refresh() {
        val cmd = commands() ?: return
        viewModelScope.launch {
            _loading.value = true
            try {
                _slots.value = cmd.getSlots()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _events.tryEmit(SlotOpFailed(R.string.op_read_slots, e.message))
            } finally {
                _loading.value = false
            }
        }
    }

    /** 点击卡片 = 切换激活槽，成功后刷新。 */
    fun activate(slot: SlotNumber) = runOp(R.string.op_activate_slot) { cmd ->
        cmd.setActiveSlot(slot)
        _slots.value = cmd.getSlots()
    }

    fun setEnabled(slot: SlotNumber, senseType: TagSenseType, enabled: Boolean) =
        runOp(R.string.op_set_enable) { cmd ->
            cmd.setSlotEnable(slot, senseType, enabled)
            _slots.value = cmd.getSlots()
        }

    /** name 为空串时删除昵称。 */
    fun setNick(slot: SlotNumber, senseType: TagSenseType, name: String) =
        runOp(R.string.op_set_nick) { cmd ->
            if (name.isEmpty()) {
                cmd.deleteSlotTagNick(slot, senseType)
            } else {
                cmd.setSlotTagNick(slot, senseType, name)
            }
            _slots.value = cmd.getSlots()
        }

    fun deleteSense(slot: SlotNumber, senseType: TagSenseType) =
        runOp(R.string.op_delete_sense) { cmd ->
            cmd.deleteSlotSenseType(slot, senseType)
            _slots.value = cmd.getSlots()
        }

    /** 重置为指定卡型：与官方 CLI `hw slot type` 一致，先设类型再重置默认数据。 */
    fun resetSlot(slot: SlotNumber, type: TagSpecificType) =
        runOp(R.string.op_reset_slot) { cmd ->
            cmd.setSlotTagType(slot, type)
            cmd.setSlotDataDefault(slot, type)
            _slots.value = cmd.getSlots()
        }

    // ---- 模拟参数（作用于设备当前激活槽） ----

    fun loadEmulatorConfig(slot: SlotNumber) {
        val cmd = commands() ?: run {
            _emuState.value = EmuConfigState.Failed("device not connected")
            return
        }
        viewModelScope.launch {
            _emuState.value = EmuConfigState.Loading
            try {
                // 模拟参数指令作用于当前激活槽，先确保目标槽已激活
                if (cmd.getActiveSlot() != slot) {
                    cmd.setActiveSlot(slot)
                    runCatching { _slots.value = cmd.getSlots() }
                }
                val hfType = cmd.getSlotInfo().getOrNull(slot.toFw())?.first
                    ?: TagSpecificType.UNDEFINED
                _emuState.value = when (hfType) {
                    in MF1_CONFIG_TYPES -> EmuConfigState.M1(cmd.mf1GetEmulatorConfig())
                    in NTAG_CONFIG_TYPES -> EmuConfigState.Ntag(cmd.mf0NtagGetUidMagicMode())
                    else -> EmuConfigState.Unsupported
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _emuState.value = EmuConfigState.Failed(e.message)
            }
        }
    }

    fun setGen1a(enabled: Boolean) = mutateM1({ copy(gen1a = enabled) }) { it.mf1SetGen1aMode(enabled) }
    fun setGen2(enabled: Boolean) = mutateM1({ copy(gen2 = enabled) }) { it.mf1SetGen2Mode(enabled) }
    fun setAntiColl(enabled: Boolean) =
        mutateM1({ copy(antiCollFromBlock0 = enabled) }) { it.mf1SetBlockAntiCollMode(enabled) }

    fun setWriteMode(mode: MifareClassicWriteMode) =
        mutateM1({ copy(writeMode = mode) }) { it.mf1SetWriteMode(mode) }

    fun setDetection(enabled: Boolean) =
        mutateM1({ copy(detection = enabled) }) { it.mf1SetDetectionEnable(enabled) }

    fun setUidMagic(enabled: Boolean) {
        val current = _emuState.value as? EmuConfigState.Ntag ?: return
        val cmd = commands() ?: return
        _emuState.value = EmuConfigState.Ntag(enabled)
        viewModelScope.launch {
            try {
                cmd.mf0NtagSetUidMagicMode(enabled)
                persistConfig(cmd)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _emuState.value = current
                _events.tryEmit(SlotOpFailed(R.string.op_emu_config, e.message))
            }
        }
    }

    /** 乐观更新 UI，失败回滚并提示。 */
    private fun mutateM1(
        update: EmulatorConfig.() -> EmulatorConfig,
        send: suspend (SlotCommands) -> Unit,
    ) {
        val current = _emuState.value as? EmuConfigState.M1 ?: return
        val cmd = commands() ?: return
        _emuState.value = EmuConfigState.M1(current.config.update())
        viewModelScope.launch {
            try {
                send(cmd)
                persistConfig(cmd)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _emuState.value = current
                _events.tryEmit(SlotOpFailed(R.string.op_emu_config, e.message))
            }
        }
    }

    /** 模拟参数修改后持久化到 flash（固件仅写运行时值，见 app_cmd.c 各 set 处理器）。 */
    private suspend fun persistConfig(cmd: SlotCommands) {
        runCatching { cmd.slotDataConfigSave() }
    }

    // ---- 写入数据到本槽（数据管理 dump / 本地文件） ----

    private val _writeState = MutableStateFlow<WriteUiState>(WriteUiState.Idle)
    val writeState: StateFlow<WriteUiState> = _writeState

    private var writeJob: Job? = null

    fun resetWriteState() {
        _writeState.value = WriteUiState.Idle
    }

    fun cancelWrite() {
        writeJob?.cancel()
    }

    /** 从数据管理选 dump 写入指定槽（M1/LF 均可，跨界会自动设类型并 save）。 */
    fun writeDumpToSlot(entity: DumpEntity, slot: SlotNumber) {
        startWrite { worker ->
            if (entity.isLfDump()) {
                _writeState.value = WriteUiState.Running(R.string.write_lf_progress, 0, 1)
                worker.writeLfToSlot(slot, entity.lfType(), entity.data)
                WriteResult(1, 1, emptyList())
            } else {
                worker.writeM1ToSlot(entity.toDumpData(), slot) { done, total ->
                    _writeState.value = WriteUiState.Running(R.string.write_slot_progress, done, total)
                }
            }
        }
    }

    /** 从本地文件写入指定槽：先按 M1 四格式解析，失败再按 LF hex 文本解析。 */
    fun writeFileToSlot(slot: SlotNumber, fileName: String, uri: Uri) {
        startWrite { worker ->
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: throw IllegalStateException("无法读取文件")
            // M1 四格式优先
            try {
                val dump = parseDump(fileName, bytes)
                return@startWrite worker.writeM1ToSlot(dump, slot) { done, total ->
                    _writeState.value = WriteUiState.Running(R.string.write_slot_progress, done, total)
                }
            } catch (_: Exception) {
                // 继续尝试 LF
            }
            val text = bytes.decodeToString()
            if (looksLikeLfIdFile(text)) {
                val (type, id) = parseLfIdFile(text)
                _writeState.value = WriteUiState.Running(R.string.write_lf_progress, 0, 1)
                worker.writeLfToSlot(slot, type, id)
                WriteResult(1, 1, emptyList())
            } else {
                throw IllegalArgumentException("无法识别的文件格式（支持 .bin/.mct/.eml/.json 或 LF hex 文本）")
            }
        }
    }

    private fun startWrite(block: suspend (DumpWriteWorker) -> WriteResult) {
        if (writeJob?.isActive == true) return
        val mf1 = connectionManager.mf1Commands
        val slots = connectionManager.slotCommands
        if (mf1 == null || slots == null) {
            _events.tryEmit(SlotOpFailed(R.string.op_reset_slot, "device not connected"))
            return
        }
        writeJob = viewModelScope.launch {
            try {
                val worker = DumpWriteWorker(mf1, slots)
                val result = block(worker)
                _writeState.value = WriteUiState.Done(result)
                // 写完后刷新槽位信息（类型可能已切换）
                runCatching { _slots.value = slots.getSlots() }
            } catch (e: CancellationException) {
                _writeState.value = WriteUiState.Idle
                throw e
            } catch (e: Exception) {
                _writeState.value = WriteUiState.Failed(R.string.write_failed, e.message)
            }
        }
    }

    private fun runOp(@StringRes opRes: Int, block: suspend (SlotCommands) -> Unit) {
        val cmd = commands() ?: return
        viewModelScope.launch {
            try {
                block(cmd)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _events.tryEmit(SlotOpFailed(opRes, e.message))
                // 回滚 UI：重新拉取设备真实状态
                runCatching { _slots.value = cmd.getSlots() }
            }
        }
    }
}
