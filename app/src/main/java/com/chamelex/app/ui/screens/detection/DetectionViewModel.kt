package com.chamelex.app.ui.screens.detection

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chamelex.app.R
import com.chamelex.connection.ConnectionManager
import com.chamelex.crypto.NativeCrypto
import com.chamelex.data.keys.KeyProvider
import com.chamelex.protocol.DetectionCommands
import com.chamelex.protocol.DetectionRecord
import com.chamelex.protocol.mfcSectorOfBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 反推出的密钥。 */
data class RecoveredKey(
    val uid: Long,
    val block: Int,
    val isKeyB: Boolean,
    val key: Long,
    var saved: Boolean = false,
)

sealed interface DetectionUiState {
    data object Idle : DetectionUiState
    data object Loading : DetectionUiState
    data class LogList(val records: List<DetectionRecord>) : DetectionUiState
    data class Recovering(val done: Int, val total: Int, val found: Int) : DetectionUiState
    data class Results(val keys: List<RecoveredKey>) : DetectionUiState
    data class Failed(@StringRes val msgRes: Int, val cause: String?) : DetectionUiState
}

@HiltViewModel
class DetectionViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val keyProvider: KeyProvider,
) : ViewModel() {

    private val _state = MutableStateFlow<DetectionUiState>(DetectionUiState.Idle)
    val state: StateFlow<DetectionUiState> = _state

    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val events: SharedFlow<Int> = _events

    private var job: Job? = null
    private var lastRecords: List<DetectionRecord> = emptyList()

    private fun commands(): DetectionCommands? {
        val c = connectionManager.detectionCommands
        if (c == null) _events.tryEmit(R.string.device_not_connected)
        return c
    }

    /** 拉取设备内全部侦测日志。 */
    fun loadLogs() {
        val cmd = commands() ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = DetectionUiState.Loading
            try {
                val records = cmd.downloadAllLogs()
                lastRecords = records
                _state.value = DetectionUiState.LogList(records)
            } catch (e: CancellationException) {
                _state.value = DetectionUiState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = DetectionUiState.Failed(R.string.detection_load_failed, e.message)
            }
        }
    }

    /**
     * 反推密钥：按 (uid, block, 密钥类型) 分组，组内两两组合跑 native mfkey32v2，
     * 候选密钥对组内全部记录验证（与官方 CLI elog --decrypt 一致）。
     */
    fun recover() {
        if (lastRecords.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val groups = lastRecords.groupBy { Triple(it.uid, it.block, it.isKeyB) }
                val totalPairs = groups.values.sumOf { it.size * (it.size - 1) / 2 }
                var donePairs = 0
                val results = mutableListOf<RecoveredKey>()

                for ((key, records) in groups) {
                    currentCoroutineContext().ensureActive()
                    val (uid, block, isKeyB) = key
                    var found = false
                    outer@ for (i in records.indices) {
                        for (j in i + 1 until records.size) {
                            currentCoroutineContext().ensureActive()
                            val a = records[i]
                            val b = records[j]
                            donePairs++
                            val candidate = withContext(Dispatchers.Default) {
                                NativeCrypto.mfkey32Recover(
                                    uid, a.nt, a.nr, a.ar, b.nt, b.nr, b.ar,
                                )
                            }
                            if (candidate >= 0 &&
                                records.all { r ->
                                    NativeCrypto.mfkey32Verify(uid, r.nt, r.nr, r.ar, candidate)
                                }
                            ) {
                                results.add(RecoveredKey(uid, block, isKeyB, candidate))
                                found = true
                                break@outer
                            }
                            _state.value = DetectionUiState.Recovering(donePairs, totalPairs, results.size)
                        }
                    }
                    _state.value = DetectionUiState.Recovering(donePairs, totalPairs, results.size)
                    if (!found) continue
                }
                _state.value = DetectionUiState.Results(results)
            } catch (e: CancellationException) {
                _state.value = DetectionUiState.LogList(lastRecords)
                throw e
            } catch (e: Exception) {
                _state.value = DetectionUiState.Failed(R.string.detection_recover_failed, e.message)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** 单条入库（已破解组 + 按卡记忆合并）。 */
    fun saveKey(item: RecoveredKey) {
        val current = _state.value as? DetectionUiState.Results ?: return
        viewModelScope.launch {
            runCatching {
                keyProvider.mergeCardKeys(
                    uidHex(item.uid),
                    listOf(
                        mfcSectorOfBlock(item.block) to (
                                if (item.isKeyB) null to item.key.toKeyBytes()
                                else item.key.toKeyBytes() to null
                                ),
                    ),
                )
            }.onSuccess {
                item.saved = true
                _state.value = DetectionUiState.Results(current.keys.toList())
                _events.tryEmit(R.string.detection_saved)
            }.onFailure {
                _events.tryEmit(R.string.dump_save_failed)
            }
        }
    }

    /** 全部入库。 */
    fun saveAll() {
        val current = _state.value as? DetectionUiState.Results ?: return
        viewModelScope.launch {
            var ok = 0
            for (item in current.keys) {
                runCatching {
                    keyProvider.mergeCardKeys(
                        uidHex(item.uid),
                        listOf(
                            mfcSectorOfBlock(item.block) to (
                                    if (item.isKeyB) null to item.key.toKeyBytes()
                                    else item.key.toKeyBytes() to null
                                    ),
                        ),
                    )
                }.onSuccess {
                    item.saved = true
                    ok++
                }
            }
            _state.value = DetectionUiState.Results(current.keys.toList())
            _events.tryEmit(R.string.detection_saved)
        }
    }

    /** 清空设备侦测记录（固件在 SET_DETECTION_ENABLE 时清日志，app_cmd.c:1555）。 */
    fun clearLogs() {
        viewModelScope.launch {
            runCatching {
                connectionManager.slotCommands?.mf1SetDetectionEnable(true)
                lastRecords = emptyList()
                _state.value = DetectionUiState.Idle
                _events.tryEmit(R.string.detection_cleared)
            }
        }
    }

    private fun uidHex(uid: Long): String = "%08X".format(uid)

    private fun Long.toKeyBytes(): ByteArray {
        val out = ByteArray(6)
        for (i in 0 until 6) out[i] = (this shr (8 * (5 - i))).toByte()
        return out
    }
}
