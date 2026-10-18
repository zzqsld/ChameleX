package com.chamelex.app.ui.screens.reader

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chamelex.app.R
import com.chamelex.connection.ConnState
import com.chamelex.connection.ConnectionManager
import com.chamelex.crypto.NativeCrypto
import com.chamelex.data.db.DumpDao
import com.chamelex.data.db.DumpEntity
import com.chamelex.protocol.AuthFailedException
import com.chamelex.protocol.CardLostException
import com.chamelex.protocol.DeviceStatusException
import com.chamelex.protocol.HfTagInfo
import com.chamelex.protocol.LfTagInfo
import com.chamelex.protocol.Mf1Commands
import com.chamelex.protocol.MfcType
import com.chamelex.app.worker.DumpWriteWorker
import com.chamelex.app.worker.WriteResult
import com.chamelex.app.ui.screens.data.WriteUiState
import com.chamelex.app.ui.screens.data.makeLfDumpEntity
import com.chamelex.protocol.ReaderCommands
import com.chamelex.protocol.SectorKeys
import com.chamelex.protocol.Status
import com.chamelex.protocol.TagSpecificType
import com.chamelex.protocol.createCheckMask
import com.chamelex.protocol.maskAllSet
import com.chamelex.protocol.maskGet
import com.chamelex.protocol.maskCountClear
import com.chamelex.protocol.maskSet
import com.chamelex.protocol.mfcBlocksInSector
import com.chamelex.protocol.mfcFirstBlockOfSector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** 扫描状态。 */
sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class HfResult(val tags: List<HfTagInfo>) : ScanState
    data class LfResult(val tag: LfTagInfo) : ScanState
    data object NoTag : ScanState

    /** Lite 设备不支持读卡器模式 */
    data object LiteNoReader : ScanState
    data class Failed(val cause: String?) : ScanState
}

/** 攻击/dump 阶段。 */
enum class AttackStage { CHECK_KEYS, DUMP }

/** 一个块的读取结果。data=null 表示读取失败。 */
data class M1Block(val index: Int, val data: ByteArray?, val isTrailer: Boolean) {
    val hex: String get() = data?.joinToString("") { "%02X".format(it) } ?: ""
}

/** 攻击/dump 结果。 */
data class M1DumpResult(
    val uidHex: String,
    val mfcType: MfcType,
    val sectorKeys: List<SectorKeys>,
    val blocks: List<M1Block>,
    val savedId: Long? = null,
    /** 全部密钥由记忆密钥命中（秒开） */
    val usedMemoryKeys: Boolean = false,
)

sealed interface AttackState {
    data object Idle : AttackState
    data class Running(
        val stage: AttackStage,
        val keysChecked: Int,
        val keysTotal: Int,
        val keysFound: Int,
        val blocksRead: Int,
        val blocksTotal: Int,
        val startedAtMs: Long,
        val elapsedMs: Long,
        /** 正在等待设备返回本批检测结果（此时显示不确定进度动画）。 */
        val awaitingDevice: Boolean = false,
        /** 检测阶段预计最长剩余秒数（-1 = 未知/不适用）。 */
        val etaSeconds: Int = -1,
    ) : AttackState

    data class Done(val result: M1DumpResult) : AttackState
    data class Failed(@StringRes val msgRes: Int, val cause: String?) : AttackState
    data object Cancelled : AttackState
}

/** 字典档位。 */
enum class DictMode(val assetLimit: Int?) {
    QUICK(50),
    FULL(null),
}

/** autopwn 运行状态（完成时复用 [AttackState.Done] 展示结果）。 */
sealed interface AutopwnUiState {
    data object Idle : AutopwnUiState
    data class Running(
        val progress: AutopwnProgress,
        val startedAtMs: Long,
        val elapsedMs: Long,
    ) : AutopwnUiState

    data class Failed(@StringRes val msgRes: Int, val cause: String?) : AutopwnUiState
    data object Cancelled : AutopwnUiState
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val dumpDao: DumpDao,
    private val keyProvider: com.chamelex.data.keys.KeyProvider,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    private val _attackState = MutableStateFlow<AttackState>(AttackState.Idle)
    val attackState: StateFlow<AttackState> = _attackState

    private val _autopwnState = MutableStateFlow<AutopwnUiState>(AutopwnUiState.Idle)
    val autopwnState: StateFlow<AutopwnUiState> = _autopwnState

    /** 密钥状态网格（扇区数 to 每密钥位状态），检测中逐批更新。 */
    private val _keyGrid = MutableStateFlow<Pair<Int, List<com.chamelex.app.ui.components.KeyBitState>>?>(null)
    val keyGrid: StateFlow<Pair<Int, List<com.chamelex.app.ui.components.KeyBitState>>?> = _keyGrid

    private fun updateGrid(mask: ByteArray, sectorCount: Int) {
        _keyGrid.value = sectorCount to (0 until sectorCount * 2).map { k ->
            if (maskGet(mask, k)) com.chamelex.app.ui.components.KeyBitState.FOUND
            else com.chamelex.app.ui.components.KeyBitState.UNKNOWN
        }
    }

    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val events: SharedFlow<Int> = _events

    private var attackJob: Job? = null
    private var tickerJob: Job? = null

    /**
     * 有序字典：该卡记忆密钥 → 用户自定义组 → 已破解组 → 内置默认组（KeyProvider）。
     */
    private suspend fun loadDictionary(mode: DictMode, uid: String?): List<ByteArray> =
        keyProvider.orderedDictionary(uid, mode.assetLimit)

    // ---- 扫描 ----

    /**
     * 一键扫描：确保读卡器模式 → HF 14A → 无卡则 LF（EM410X → HID）。
     */
    fun scan() {
        if (_scanState.value is ScanState.Scanning) return
        val cmd: ReaderCommands = connectionManager.readerCommands ?: run {
            _events.tryEmit(R.string.device_not_connected)
            return
        }
        // 新一轮扫描 = 新会话，重置攻击/破解/写卡槽状态，让操作按钮重新可用
        _attackState.value = AttackState.Idle
        _autopwnState.value = AutopwnUiState.Idle
        _slotWriteState.value = WriteUiState.Idle
        _keyGrid.value = null
        viewModelScope.launch {
            _scanState.value = ScanState.Scanning
            try {
                if (!cmd.isDeviceReaderMode()) {
                    try {
                        cmd.changeDeviceMode(true)
                    } catch (e: DeviceStatusException) {
                        if (e.status == Status.NOT_IMPLEMENTED.code) {
                            _scanState.value = ScanState.LiteNoReader
                            return@launch
                        }
                        throw e
                    }
                }
                val hfTags = cmd.hf14aScan()
                if (hfTags.isNotEmpty()) {
                    _scanState.value = ScanState.HfResult(hfTags)
                    return@launch
                }
                cmd.em410xScan()?.let {
                    _scanState.value = ScanState.LfResult(it)
                    return@launch
                }
                cmd.hidProxScan()?.let {
                    _scanState.value = ScanState.LfResult(it)
                    return@launch
                }
                _scanState.value = ScanState.NoTag
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _scanState.value = ScanState.Failed(e.message)
            }
        }
    }

    // ---- 字典检测 + dump ----

    fun startAttack(tag: HfTagInfo, mode: DictMode) {
        if (attackJob?.isActive == true) return
        val mfcType = MfcType.fromSak(tag.sak) ?: return
        val cmd = connectionManager.mf1Commands ?: run {
            _events.tryEmit(R.string.device_not_connected)
            return
        }
        attackJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val uidHex = tag.uidHex.uppercase()
                // 阶段 0：该卡记忆密钥预检（同 UID 秒开）
                var usedMemoryKeys = false
                val mask = createCheckMask(mfcType.sectorCount)
                updateGrid(mask, mfcType.sectorCount)
                val sectorKeys = MutableList(mfcType.sectorCount) { SectorKeys() }
                var keysChecked = 0
                var keysFound = 0
                val remembered = keyProvider.rememberedKeysFor(uidHex)
                for (batch in remembered.chunked(Mf1Commands.CHECK_KEYS_BATCH_SIZE)) {
                    currentCoroutineContext().ensureActive()
                    if (maskAllSet(mask)) break
                    publishProgress(
                        AttackStage.CHECK_KEYS, keysChecked, remembered.size,
                        keysFound, 0, mfcType.blockCount, startedAt, awaitingDevice = true,
                    )
                    val result = cmd.mf1CheckKeysOfSectors(mask, batch)
                    keysChecked += batch.size
                    for (k in 0 until Mf1Commands.MASK_BITS) {
                        if (!result.isFound(k)) continue
                        maskSet(mask, k)
                        keysFound++
                        val sector = k / 2
                        if (sector >= mfcType.sectorCount) continue
                        val key = result.keyAt(k)
                        sectorKeys[sector] =
                            if (k % 2 == 0) sectorKeys[sector].copy(keyA = key)
                            else sectorKeys[sector].copy(keyB = key)
                    }
                    updateGrid(mask, mfcType.sectorCount)
                }
                if (remembered.isNotEmpty()) {
                    keyProvider.touchCard(uidHex)
                    usedMemoryKeys = maskAllSet(mask)
                }

                // 阶段 1：有序字典检测（记忆密钥已排最前，库内全局去重）
                val dictionary = loadDictionary(mode, uidHex)
                publishProgress(
                    AttackStage.CHECK_KEYS, keysChecked, dictionary.size,
                    keysFound, 0, mfcType.blockCount, startedAt,
                )
                for (batch in dictionary.chunked(Mf1Commands.CHECK_KEYS_BATCH_SIZE)) {
                    currentCoroutineContext().ensureActive()
                    if (maskAllSet(mask)) break
                    publishProgress(
                        AttackStage.CHECK_KEYS, keysChecked, dictionary.size,
                        keysFound, 0, mfcType.blockCount, startedAt, awaitingDevice = true,
                    )
                    val result = cmd.mf1CheckKeysOfSectors(mask, batch)
                    keysChecked += batch.size
                    for (k in 0 until Mf1Commands.MASK_BITS) {
                        if (!result.isFound(k)) continue
                        maskSet(mask, k)
                        keysFound++
                        val sector = k / 2
                        if (sector >= mfcType.sectorCount) continue
                        val key = result.keyAt(k)
                        sectorKeys[sector] =
                            if (k % 2 == 0) sectorKeys[sector].copy(keyA = key)
                            else sectorKeys[sector].copy(keyB = key)
                    }
                    updateGrid(mask, mfcType.sectorCount)
                    val remainingKeys = dictionary.size - keysChecked
                    val eta = (maskCountClear(mask) * remainingKeys * 0.1).toInt()
                    publishProgress(
                        AttackStage.CHECK_KEYS, keysChecked, dictionary.size,
                        keysFound, 0, mfcType.blockCount, startedAt, etaSeconds = eta,
                    )
                }

                // 阶段 2：用命中密钥逐块读取
                val blocks = ArrayList<M1Block>(mfcType.blockCount)
                var blocksRead = 0
                for (sector in 0 until mfcType.sectorCount) {
                    val keys = sectorKeys[sector]
                    val blockCount = mfcBlocksInSector(sector)
                    val firstBlock = mfcFirstBlockOfSector(sector)
                    for (i in 0 until blockCount) {
                        currentCoroutineContext().ensureActive()
                        val blockIndex = firstBlock + i
                        val data = readBlockWithKeys(cmd, blockIndex, keys)
                        blocks.add(M1Block(blockIndex, data, isTrailer = i == blockCount - 1))
                        blocksRead++
                        publishProgress(
                            AttackStage.DUMP, keysChecked, dictionary.size,
                            keysFound, blocksRead, mfcType.blockCount, startedAt,
                        )
                    }
                }

                _keyGrid.value = mfcType.sectorCount to sectorKeys.flatMap { keys ->
                    listOf(
                        if (keys.keyA != null) com.chamelex.app.ui.components.KeyBitState.FOUND
                        else com.chamelex.app.ui.components.KeyBitState.MISSING,
                        if (keys.keyB != null) com.chamelex.app.ui.components.KeyBitState.FOUND
                        else com.chamelex.app.ui.components.KeyBitState.MISSING,
                    )
                }
                val dumpResult = M1DumpResult(
                    uidHex = uidHex,
                    mfcType = mfcType,
                    sectorKeys = sectorKeys,
                    blocks = blocks,
                    usedMemoryKeys = usedMemoryKeys,
                )
                runCatching {
                    keyProvider.recordCrackedKeys(
                        uidHex, sectorKeys.map { it.keyA to it.keyB },
                    )
                }
                _attackState.value = AttackState.Done(dumpResult)
            } catch (e: CancellationException) {
                _attackState.value = AttackState.Cancelled
                throw e
            } catch (e: CardLostException) {
                _attackState.value = AttackState.Failed(R.string.attack_card_lost, null)
            } catch (e: Exception) {
                _attackState.value = AttackState.Failed(R.string.attack_failed, e.message)
            } finally {
                tickerJob?.cancel()
            }
        }
        startTicker()
    }

    /** KeyA 优先、失败再 KeyB；都失败返回 null（读取失败）。 */
    private suspend fun readBlockWithKeys(
        cmd: Mf1Commands,
        block: Int,
        keys: SectorKeys,
    ): ByteArray? {
        val keyA = keys.keyA
        val keyB = keys.keyB
        if (keyA != null) {
            try {
                return cmd.mf1ReadOneBlock(com.chamelex.protocol.MfcKeyType.A, block, keyA)
            } catch (_: AuthFailedException) {
            }
        }
        if (keyB != null) {
            try {
                return cmd.mf1ReadOneBlock(com.chamelex.protocol.MfcKeyType.B, block, keyB)
            } catch (_: AuthFailedException) {
            }
        }
        return null
    }

    fun cancelAttack() {
        attackJob?.cancel()
    }

    // ---- 一键自动解密（autopwn） ----

    fun startAutopwn(tag: HfTagInfo, mode: DictMode) {
        if (attackJob?.isActive == true) return
        val mf1 = connectionManager.mf1Commands
        val crack = connectionManager.crackCommands
        val reader = connectionManager.readerCommands
        if (mf1 == null || crack == null || reader == null) {
            _events.tryEmit(R.string.device_not_connected)
            return
        }
        attackJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val dictionary = loadDictionary(mode, tag.uidHex.uppercase())
                val runner = AutopwnRunner(
                    mf1 = mf1,
                    crack = crack,
                    reader = reader,
                    cacheDir = context.cacheDir.absolutePath,
                    onProgress = { progress ->
                        _autopwnState.value = AutopwnUiState.Running(
                            progress = progress,
                            startedAtMs = startedAt,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                        )
                    },
                )
                val result = runner.run(tag, dictionary)
                runCatching {
                    keyProvider.recordCrackedKeys(
                        result.uidHex, result.sectorKeys.map { it.keyA to it.keyB },
                    )
                }
                // 结果复用 3b 的结果页与保存流程
                _attackState.value = AttackState.Done(result)
                _autopwnState.value = AutopwnUiState.Idle
            } catch (e: CancellationException) {
                _autopwnState.value = AutopwnUiState.Cancelled
                throw e
            } catch (e: CardLostException) {
                _autopwnState.value = AutopwnUiState.Failed(R.string.attack_card_lost, null)
            } catch (e: AutopwnException) {
                _autopwnState.value = AutopwnUiState.Failed(e.msgRes, e.message)
            } catch (e: Exception) {
                _autopwnState.value = AutopwnUiState.Failed(R.string.attack_failed, e.message)
            } finally {
                tickerJob?.cancel()
            }
        }
        startAutopwnTicker()
    }

    fun cancelAutopwn() {
        // native hardnested 正在跑时先置取消标志，再取消协程
        runCatching { NativeCrypto.hardnestedCancel() }
        attackJob?.cancel()
    }

    fun resetAutopwn() {
        _autopwnState.value = AutopwnUiState.Idle
    }

    // ---- 结果保存：写入设备卡槽（eload） / LF 卡保存 ----

    private val _slotWriteState = MutableStateFlow<WriteUiState>(WriteUiState.Idle)
    val slotWriteState: StateFlow<WriteUiState> = _slotWriteState

    /** 选槽对话框用的槽位列表。 */
    private val _slotsForPicker = MutableStateFlow<List<com.chamelex.protocol.SlotInfo>?>(null)
    val slotsForPicker: StateFlow<List<com.chamelex.protocol.SlotInfo>?> = _slotsForPicker

    fun loadSlotsForPicker() {
        val slots = connectionManager.slotCommands ?: return
        viewModelScope.launch {
            runCatching { _slotsForPicker.value = slots.getSlots() }
        }
    }

    /** 把破解结果写入指定卡槽。 */
    fun writeResultToSlot(slot: com.chamelex.protocol.SlotNumber) {
        val done = _attackState.value as? AttackState.Done ?: return
        val mf1 = connectionManager.mf1Commands
        val slots = connectionManager.slotCommands
        if (mf1 == null || slots == null) {
            _events.tryEmit(R.string.device_not_connected)
            return
        }
        viewModelScope.launch {
            try {
                val result = done.result
                val worker = DumpWriteWorker(mf1, slots)
                val data = ByteArray(result.mfcType.blockCount * 16)
                result.blocks.forEach { b -> b.data?.copyInto(data, b.index * 16) }
                val dump = com.chamelex.protocol.Mf1DumpData(
                    blocks = data,
                    sak = result.mfcType.sak,
                )
                val writeResult = worker.writeM1ToSlot(dump, slot) { d, t ->
                    _slotWriteState.value = WriteUiState.Running(R.string.write_slot_progress, d, t)
                }
                _slotWriteState.value = WriteUiState.Done(writeResult)
            } catch (e: CancellationException) {
                _slotWriteState.value = WriteUiState.Idle
                throw e
            } catch (e: Exception) {
                _slotWriteState.value = WriteUiState.Failed(R.string.write_failed, e.message)
            }
        }
    }

    /** LF 扫描结果保存到数据管理。 */
    fun saveLfResult(tag: LfTagInfo) {
        viewModelScope.launch {
            val (type, id) = when (tag) {
                is LfTagInfo.Em410x -> tag.type to tag.id
                is LfTagInfo.HidProx -> TagSpecificType.HID_PROX to hidProxIdBytes(tag)
            }
            val time = SimpleDateFormat("MMdd-HHmmss", Locale.getDefault()).format(Date())
            dumpDao.insert(makeLfDumpEntity("LF_${tag.type.code}_${tag.type}_$time", type, id, "scan"))
            _events.tryEmit(R.string.lf_saved)
        }
    }

    /** LF 扫描结果写入当前激活卡槽。 */
    fun writeLfToCurrentSlot(tag: LfTagInfo) {
        val slots = connectionManager.slotCommands ?: run {
            _events.tryEmit(R.string.device_not_connected)
            return
        }
        val mf1 = connectionManager.mf1Commands ?: run {
            _events.tryEmit(R.string.device_not_connected)
            return
        }
        viewModelScope.launch {
            try {
                _slotWriteState.value = WriteUiState.Running(R.string.write_lf_progress, 0, 1)
                val slot = slots.getActiveSlot()
                val worker = DumpWriteWorker(mf1, slots)
                when (tag) {
                    is LfTagInfo.Em410x -> worker.writeLfToSlot(slot, tag.type, tag.id)
                    is LfTagInfo.HidProx -> worker.writeLfToSlot(slot, TagSpecificType.HID_PROX, hidProxIdBytes(tag))
                }
                _slotWriteState.value = WriteUiState.Done(WriteResult(1, 1, emptyList()))
                _events.tryEmit(R.string.lf_write_done)
            } catch (e: CancellationException) {
                _slotWriteState.value = WriteUiState.Idle
                throw e
            } catch (e: Exception) {
                _slotWriteState.value = WriteUiState.Failed(R.string.write_failed, e.message)
            }
        }
    }

    fun resetSlotWriteState() {
        _slotWriteState.value = WriteUiState.Idle
    }

    private fun startAutopwnTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val s = _autopwnState.value
                if (s is AutopwnUiState.Running) {
                    _autopwnState.value = s.copy(elapsedMs = System.currentTimeMillis() - s.startedAtMs)
                } else break
            }
        }
    }

    fun resetAttack() {
        _attackState.value = AttackState.Idle
    }

    /** 保存 dump 到数据管理（Room）。source: dict_attack / crack。 */
    fun saveDump(source: String = "dict_attack") {
        val done = _attackState.value as? AttackState.Done ?: return
        if (done.result.savedId != null) return
        viewModelScope.launch {
            try {
                val result = done.result
                val blockCount = result.mfcType.blockCount
                val data = ByteArray(blockCount * 16)
                val readableMask = ByteArray((blockCount + 7) / 8)
                result.blocks.forEach { block ->
                    block.data?.copyInto(data, block.index * 16)
                    if (block.data != null) {
                        readableMask[block.index / 8] =
                            (readableMask[block.index / 8].toInt() or (1 shl (7 - block.index % 8))).toByte()
                    }
                }
                val keysArea = ByteArray(result.mfcType.sectorCount * 12) { 0xFF.toByte() }
                result.sectorKeys.forEachIndexed { sector, keys ->
                    keys.keyA?.copyInto(keysArea, sector * 12)
                    keys.keyB?.copyInto(keysArea, sector * 12 + 6)
                }
                val time = SimpleDateFormat("MMdd-HHmmss", Locale.getDefault()).format(Date())
                val entity = DumpEntity(
                    name = "M1_${result.uidHex}_$time",
                    tagType = result.mfcType.sak,
                    uid = result.uidHex,
                    source = source,
                    createdAt = System.currentTimeMillis(),
                    sectorCount = result.mfcType.sectorCount,
                    blockCount = blockCount,
                    data = data,
                    readableMask = readableMask,
                    keys = keysArea,
                )
                val id = dumpDao.insert(entity)
                _attackState.value = AttackState.Done(result.copy(savedId = id))
                _events.tryEmit(R.string.dump_saved)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _events.tryEmit(R.string.dump_save_failed)
            }
        }
    }

    private fun publishProgress(
        stage: AttackStage,
        keysChecked: Int,
        keysTotal: Int,
        keysFound: Int,
        blocksRead: Int,
        blocksTotal: Int,
        startedAt: Long,
        awaitingDevice: Boolean = false,
        etaSeconds: Int = -1,
    ) {
        _attackState.value = AttackState.Running(
            stage = stage,
            keysChecked = keysChecked,
            keysTotal = keysTotal,
            keysFound = keysFound,
            blocksRead = blocksRead,
            blocksTotal = blocksTotal,
            startedAtMs = startedAt,
            elapsedMs = System.currentTimeMillis() - startedAt,
            awaitingDevice = awaitingDevice,
            etaSeconds = etaSeconds,
        )
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val s = _attackState.value
                if (s is AttackState.Running) {
                    _attackState.value = s.copy(elapsedMs = System.currentTimeMillis() - s.startedAtMs)
                } else break
            }
        }
    }

    override fun onCleared() {
        attackJob?.cancel()
        tickerJob?.cancel()
    }
}

/** HIDProx 卡号打包为 13 字节（与 HIDPROX_SET_EMU_ID 响应格式 `>BIBIBH` 一致）。 */
private fun hidProxIdBytes(tag: LfTagInfo.HidProx): ByteArray {
    val out = ByteArray(13)
    out[0] = tag.format.toByte()
    writeU32BE(out, 1, tag.facilityCode)
    out[5] = (tag.cardNumber shr 32).toByte()
    writeU32BE(out, 6, tag.cardNumber and 0xFFFFFFFFL)
    out[10] = tag.issueLevel.toByte()
    out[11] = (tag.oem shr 8).toByte()
    out[12] = tag.oem.toByte()
    return out
}

private fun writeU32BE(out: ByteArray, offset: Int, value: Long) {
    out[offset] = (value shr 24).toByte()
    out[offset + 1] = (value shr 16).toByte()
    out[offset + 2] = (value shr 8).toByte()
    out[offset + 3] = value.toByte()
}
