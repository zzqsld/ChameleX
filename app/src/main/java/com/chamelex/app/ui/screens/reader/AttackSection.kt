package com.chamelex.app.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chamelex.app.R
import com.chamelex.app.ui.components.MctBlockRow
import com.chamelex.app.ui.components.SlotPickerDialog
import com.chamelex.app.ui.screens.data.WriteUiState
import com.chamelex.protocol.MfcType
import com.chamelex.protocol.SlotNumber
import com.chamelex.protocol.mfcBlocksInSector
import com.chamelex.protocol.mfcFirstBlockOfSector

/** 字典攻击/dump 的进度与结果区。 */
@Composable
fun AttackSection(viewModel: ReaderViewModel) {
    val state by viewModel.attackState.collectAsStateWithLifecycle()
    val autopwnState by viewModel.autopwnState.collectAsStateWithLifecycle()

    // autopwn 运行中优先展示其进度
    when (val ap = autopwnState) {
        AutopwnUiState.Idle -> {}
        is AutopwnUiState.Running -> {
            AutopwnProgressCard(ap, viewModel)
            return
        }

        AutopwnUiState.Cancelled -> {
            Text(
                stringResource(R.string.autopwn_cancelled),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        is AutopwnUiState.Failed -> {
            Text(
                stringResource(ap.msgRes, ap.cause ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
    }

    when (val s = state) {
        AttackState.Idle -> {}

        is AttackState.Running -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(
                            if (s.stage == AttackStage.CHECK_KEYS) R.string.attack_stage_check
                            else R.string.attack_stage_dump
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (s.stage == AttackStage.CHECK_KEYS) {
                    if (s.awaitingDevice) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.attack_waiting_device),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { if (s.keysTotal > 0) s.keysChecked.toFloat() / s.keysTotal else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        stringResource(R.string.attack_progress_keys, s.keysChecked, s.keysTotal),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.attack_progress_found, s.keysFound),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s.etaSeconds >= 0) {
                        Text(
                            stringResource(R.string.attack_eta_fmt, s.etaSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        progress = { if (s.blocksTotal > 0) s.blocksRead.toFloat() / s.blocksTotal else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.attack_progress_blocks, s.blocksRead, s.blocksTotal),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.attack_elapsed, s.elapsedMs / 1000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { viewModel.cancelAttack() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.attack_cancel))
                }
            }
        }

        AttackState.Cancelled -> Text(
            stringResource(R.string.attack_cancelled),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is AttackState.Failed -> Text(
            stringResource(s.msgRes, s.cause ?: ""),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )

        is AttackState.Done -> AttackResultView(s.result, viewModel)
    }
}

@Composable
private fun AttackResultView(result: M1DumpResult, viewModel: ReaderViewModel) {
    var showSlotPicker by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.attack_result_title), style = MaterialTheme.typography.titleMedium)
            if (result.usedMemoryKeys) {
                Text(
                    stringResource(R.string.keys_used_memory),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            com.chamelex.app.ui.components.SectorKeyGrid(
                sectorCount = result.mfcType.sectorCount,
                states = result.sectorKeys.flatMap { keys ->
                    listOf(
                        if (keys.keyA != null) com.chamelex.app.ui.components.KeyBitState.FOUND
                        else com.chamelex.app.ui.components.KeyBitState.MISSING,
                        if (keys.keyB != null) com.chamelex.app.ui.components.KeyBitState.FOUND
                        else com.chamelex.app.ui.components.KeyBitState.MISSING,
                    )
                },
            )
            Text(
                stringResource(R.string.attack_sector_keys),
                style = MaterialTheme.typography.titleSmall,
            )
            result.sectorKeys.forEachIndexed { sector, keys ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.sector_fmt, sector),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(0.25f),
                    )
                    KeyText(keys.keyA?.toHexUpper(), Modifier.weight(0.375f))
                    KeyText(keys.keyB?.toHexUpper(), Modifier.weight(0.375f))
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.attack_dump_blocks), style = MaterialTheme.typography.titleSmall)
            DumpBlockTable(result)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { viewModel.saveDump("crack") },
            modifier = Modifier.weight(1f),
            enabled = result.savedId == null,
        ) {
            Text(
                stringResource(if (result.savedId != null) R.string.dump_saved_mark else R.string.save_to_data)
            )
        }
        OutlinedButton(
            onClick = {
                viewModel.loadSlotsForPicker()
                showSlotPicker = true
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.save_to_slot))
        }
    }

    // 选槽对话框（默认选中当前激活槽）
    if (showSlotPicker) {
        val slots by viewModel.slotsForPicker.collectAsStateWithLifecycle()
        val slotList = slots
        if (slotList != null) {
            val active = slotList.firstOrNull { it.isActive }?.slot ?: SlotNumber.SLOT_1
            SlotPickerDialog(
                slots = slotList,
                initialSelected = active,
                onDismiss = { showSlotPicker = false },
                onConfirm = { slot ->
                    showSlotPicker = false
                    viewModel.writeResultToSlot(slot)
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { showSlotPicker = false },
                title = { Text(stringResource(R.string.write_to_slot_title)) },
                text = { CircularProgressIndicator() },
                confirmButton = {
                    TextButton(onClick = { showSlotPicker = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun KeyText(keyHex: String?, modifier: Modifier) {
    if (keyHex != null) {
        Text(
            keyHex,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = modifier,
        )
    } else {
        Text(
            stringResource(R.string.key_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}

/** dump 块表格：扇区分组、trailer 高亮、失败块标注。 */
@Composable
fun DumpBlockTable(result: M1DumpResult) {
    val mfcType = result.mfcType
    for (sector in 0 until mfcType.sectorCount) {
        val blockCount = mfcBlocksInSector(sector)
        val firstBlock = mfcFirstBlockOfSector(sector)
        Text(
            stringResource(R.string.sector_fmt, sector),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        for (i in 0 until blockCount) {
            val blockIndex = firstBlock + i
            val block = result.blocks.getOrNull(blockIndex) ?: continue
            val isTrailer = i == blockCount - 1
            MctBlockRow(
                blockIndex = blockIndex,
                data = block.data,
                isTrailer = isTrailer,
                label = if (blockIndex == 0) stringResource(R.string.block0_badge)
                else stringResource(R.string.block_fmt, blockIndex),
                modifier = Modifier.background(
                    if (isTrailer) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                ),
            )
        }
        if (sector < mfcType.sectorCount - 1) {
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        }
    }
}

fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }

/** MfcType 显示名。 */
fun MfcType.displayNameRes(): Int = when (this) {
    MfcType.CLASSIC_1K -> R.string.mfc_type_1k
    MfcType.CLASSIC_4K -> R.string.mfc_type_4k
    MfcType.MINI -> R.string.mfc_type_mini
    MfcType.CLASSIC_2K -> R.string.mfc_type_2k
}

/** autopwn 实时进度卡。 */
@Composable
private fun AutopwnProgressCard(state: AutopwnUiState.Running, viewModel: ReaderViewModel) {
    val p = state.progress
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    if (p.detail.isEmpty()) stringResource(p.stageRes)
                    else stringResource(p.stageRes, p.detail),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            p.keyGrid?.let { (sectorCount, found) ->
                com.chamelex.app.ui.components.SectorKeyGrid(
                    sectorCount = sectorCount,
                    states = found.map {
                        if (it) com.chamelex.app.ui.components.KeyBitState.FOUND
                        else com.chamelex.app.ui.components.KeyBitState.UNKNOWN
                    },
                )
            }
            if (p.keysTotal > 0) {
                LinearProgressIndicator(
                    progress = { p.keysFound.toFloat() / p.keysTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.autopwn_keys_progress, p.keysFound, p.keysTotal),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(R.string.autopwn_attempts, p.attempts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.attack_elapsed, state.elapsedMs / 1000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { viewModel.cancelAutopwn() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.attack_cancel))
            }
        }
    }
}

/** 写入卡槽状态（需求 B：破解结果/LF 卡写入当前激活槽）。 */
@Composable
fun SlotWriteStatus(viewModel: ReaderViewModel) {
    val state by viewModel.slotWriteState.collectAsStateWithLifecycle()
    when (val s = state) {
        WriteUiState.Idle -> {}
        is WriteUiState.Running -> Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    if (s.total > 1) stringResource(s.labelRes, s.done, s.total)
                    else stringResource(s.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is WriteUiState.Done -> Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (s.result.mismatchedBlocks.isEmpty()) {
                        stringResource(R.string.write_done_ok)
                    } else {
                        stringResource(
                            R.string.write_done_mismatch_fmt,
                            s.result.mismatchedBlocks.size,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (s.result.mismatchedBlocks.isEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.error,
                )
            }
        }

        is WriteUiState.Failed -> Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(s.msgRes, s.cause ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
