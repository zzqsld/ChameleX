package com.chamelex.app.ui.screens.data

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chamelex.app.R
import com.chamelex.app.worker.DumpWriteWorker
import com.chamelex.app.worker.WriteResult
import com.chamelex.connection.ConnectionManager
import com.chamelex.data.db.DumpDao
import com.chamelex.data.db.DumpEntity
import com.chamelex.protocol.CardLostException
import com.chamelex.protocol.DumpFormat
import com.chamelex.protocol.Mf1DumpData
import com.chamelex.protocol.MfcType
import com.chamelex.protocol.SectorKeys
import com.chamelex.protocol.SlotNumber
import com.chamelex.protocol.mfcBlocksInSector
import com.chamelex.protocol.mfcFirstBlockOfSector
import com.chamelex.protocol.parseDump
import com.chamelex.protocol.serializeDump
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 写入（卡槽/魔术卡）进度状态。 */
sealed interface WriteUiState {
    data object Idle : WriteUiState
    data class Running(val labelRes: Int, val done: Int, val total: Int) : WriteUiState
    data class Done(val result: WriteResult) : WriteUiState
    data class Failed(@StringRes val msgRes: Int, val cause: String?) : WriteUiState
}

@HiltViewModel
class DataViewModel @Inject constructor(
    private val dumpDao: DumpDao,
    private val connectionManager: ConnectionManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val dumps: StateFlow<List<DumpEntity>> = dumpDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val events: SharedFlow<Int> = _events

    private val _writeState = MutableStateFlow<WriteUiState>(WriteUiState.Idle)
    val writeState: StateFlow<WriteUiState> = _writeState

    /** 选槽对话框用的槽位列表（loadSlotsForPicker 后填充）。 */
    private val _slotsForPicker = MutableStateFlow<List<com.chamelex.protocol.SlotInfo>?>(null)
    val slotsForPicker: StateFlow<List<com.chamelex.protocol.SlotInfo>?> = _slotsForPicker

    fun loadSlotsForPicker() {
        val slots = connectionManager.slotCommands ?: return
        viewModelScope.launch {
            runCatching { _slotsForPicker.value = slots.getSlots() }
        }
    }

    private var writeJob: Job? = null

    suspend fun getById(id: Long): DumpEntity? = dumpDao.getById(id)

    fun delete(id: Long) {
        viewModelScope.launch { dumpDao.deleteById(id) }
    }

    fun rename(entity: DumpEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { dumpDao.insert(entity.copy(name = newName.trim())) }
    }

    // ---- 导入 / 导出 ----

    /** SAF 导入 dump 文件（.bin/.mct/.eml/.json，按扩展名+内容识别）。 */
    fun importDump(fileName: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("无法读取文件")
                val dump = parseDump(fileName, bytes)
                dumpDao.insert(dump.toEntity(name = fileName.substringBeforeLast('.'), source = "import"))
                _events.tryEmit(R.string.import_success)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _events.tryEmit(R.string.import_failed)
            }
        }
    }

    /** 导出为指定格式的字节内容（由 UI 经 SAF 写出）。 */
    fun exportDump(entity: DumpEntity, format: DumpFormat): ByteArray =
        serializeDump(format, entity.toDumpData())

    // ---- 编辑 ----

    /** 批量更新多块数据并保存（就地编辑器用；trailer 同步 keys 区）。 */
    fun updateBlocks(entity: DumpEntity, edits: Map<Int, ByteArray>) {
        if (edits.isEmpty()) return
        viewModelScope.launch {
            val newData = entity.data.copyOf()
            var newKeys = entity.keys
            edits.forEach { (block, data) ->
                if (data.size != 16) return@forEach
                data.copyInto(newData, block * 16)
                val sector = sectorOfBlock(entity, block)
                if (sector != null && block == trailerOf(entity, sector)) {
                    if (newKeys === entity.keys) newKeys = entity.keys.copyOf()
                    data.copyOfRange(0, 6).copyInto(newKeys, sector * 12)
                    data.copyOfRange(10, 16).copyInto(newKeys, sector * 12 + 6)
                }
            }
            dumpDao.insert(
                entity.copy(
                    data = newData,
                    keys = newKeys,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun sectorOfBlock(entity: DumpEntity, block: Int): Int? {
        val mfcType = MfcType.fromSak(entity.tagType) ?: return null
        for (s in 0 until mfcType.sectorCount) {
            val first = mfcFirstBlockOfSector(s)
            if (block >= first && block < first + mfcBlocksInSector(s)) return s
        }
        return null
    }

    private fun trailerOf(entity: DumpEntity, sector: Int): Int {
        val mfcType = MfcType.fromSak(entity.tagType)!!
        return mfcFirstBlockOfSector(sector) + mfcBlocksInSector(sector) - 1
    }

    // ---- 写入卡槽 / 魔术卡 ----

    /** dump 写入指定模拟卡槽（M1 eload 或 LF id）。 */
    fun writeToSlot(entity: DumpEntity, slot: SlotNumber) {
        if (writeJob?.isActive == true) return
        val mf1 = connectionManager.mf1Commands
        val slots = connectionManager.slotCommands
        if (mf1 == null || slots == null) {
            _events.tryEmit(R.string.device_not_connected)
            return
        }
        writeJob = viewModelScope.launch {
            val worker = DumpWriteWorker(mf1, slots)
            try {
                val result = if (entity.isLfDump()) {
                    _writeState.value = WriteUiState.Running(R.string.write_lf_progress, 0, 1)
                    worker.writeLfToSlot(slot, entity.lfType(), entity.data)
                    WriteResult(1, 1, emptyList())
                } else {
                    worker.writeM1ToSlot(entity.toDumpData(), slot) { done, total ->
                        _writeState.value = WriteUiState.Running(R.string.write_slot_progress, done, total)
                    }
                }
                _writeState.value = WriteUiState.Done(result)
            } catch (e: CancellationException) {
                _writeState.value = WriteUiState.Idle
                throw e
            } catch (e: CardLostException) {
                _writeState.value = WriteUiState.Failed(R.string.attack_card_lost, null)
            } catch (e: Exception) {
                _writeState.value = WriteUiState.Failed(R.string.write_failed, e.message)
            }
        }
    }

    /** 写入实体魔术卡（Gen1A 后门 / Gen2 密钥写）。调用前 UI 需完成确认与放卡引导。 */
    fun writeMagicCard(entity: DumpEntity, gen1a: Boolean) {
        if (writeJob?.isActive == true) return
        val mf1 = connectionManager.mf1Commands ?: run {
            _events.tryEmit(R.string.device_not_connected)
            return
        }
        writeJob = viewModelScope.launch {
            val worker = DumpWriteWorker(mf1, null)
            try {
                val dump = entity.toDumpData()
                val result = if (gen1a) {
                    worker.writeGen1aCard(dump) { done, total ->
                        _writeState.value = WriteUiState.Running(R.string.write_card_progress, done, total)
                    }
                } else {
                    worker.writeGen2Card(dump, entity.sectorKeysList()) { done, total ->
                        _writeState.value = WriteUiState.Running(R.string.write_card_progress, done, total)
                    }
                }
                _writeState.value = WriteUiState.Done(result)
            } catch (e: CancellationException) {
                _writeState.value = WriteUiState.Idle
                throw e
            } catch (e: CardLostException) {
                _writeState.value = WriteUiState.Failed(R.string.attack_card_lost, null)
            } catch (e: Exception) {
                _writeState.value = WriteUiState.Failed(R.string.write_failed, e.message)
            }
        }
    }

    fun resetWriteState() {
        _writeState.value = WriteUiState.Idle
    }

    fun cancelWrite() {
        writeJob?.cancel()
    }
}

// ---- 内部工具 ----

private fun DumpEntity.sectorKeysList(): List<SectorKeys> =
    (0 until sectorCount).map { sector ->
        SectorKeys(
            keyA = sectorKeyA(sector),
            keyB = sectorKeyB(sector),
        )
    }
