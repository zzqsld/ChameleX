package com.chamelex.app.ui.screens.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chamelex.app.R
import com.chamelex.app.ui.components.MctBlockRow
import com.chamelex.app.ui.components.SlotPickerDialog
import com.chamelex.data.db.DumpEntity
import com.chamelex.protocol.MfcType
import com.chamelex.protocol.SlotNumber
import com.chamelex.protocol.mfcBlocksInSector
import com.chamelex.protocol.mfcFirstBlockOfSector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataDetailScreen(
    dumpId: Long,
    viewModel: DataViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 每次写入/编辑后重新拉取（createdAt 会变）
    var reloadTick by remember { mutableStateOf(0) }
    val dump by produceState<DumpEntity?>(initialValue = null, dumpId, reloadTick) {
        value = viewModel.getById(dumpId)
    }
    val writeState by viewModel.writeState.collectAsStateWithLifecycle()

    var showSlotPicker by remember { mutableStateOf(false) }
    var showMagicPick by remember { mutableStateOf(false) }
    var magicConfirm by remember { mutableStateOf<Boolean?>(null) } // true=gen1a, false=gen2
    var showExportPick by remember { mutableStateOf(false) }

    // 导出（SAF）
    var exportFormat by remember { mutableStateOf<com.chamelex.protocol.DumpFormat?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val entity = dump
        val format = exportFormat
        if (uri != null && entity != null && format != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(viewModel.exportDump(entity, format))
                }
            }
        }
        exportFormat = null
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(context.getString(it)) }
    }

    // 写入完成后刷新
    LaunchedEffect(writeState) {
        if (writeState is WriteUiState.Done) {
            reloadTick++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(dump?.name ?: stringResource(R.string.data_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.slot_back),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val entity = dump ?: return@Column
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(stringResource(R.string.reader_card_type), dumpTypeName(entity))
                    InfoRow(stringResource(R.string.reader_uid), entity.uid)
                    InfoRow(stringResource(R.string.data_source_label), sourceName(entity.source))
                    InfoRow(
                        stringResource(R.string.data_time_label),
                        stringResource(R.string.data_time_fmt, entity.createdAt),
                    )
                }
            }

            if (entity.isLfDump()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoRow(
                            stringResource(R.string.reader_lf_id),
                            entity.data.joinToString("") { "%02X".format(it) },
                        )
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.attack_dump_blocks),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        InPlaceDumpEditor(
                            entity = entity,
                            onSave = { edits ->
                                viewModel.updateBlocks(entity, edits)
                                reloadTick++
                            },
                        )
                    }
                }
            }

            // ---- 写入操作 ----
            Button(
                onClick = {
                    viewModel.loadSlotsForPicker()
                    showSlotPicker = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.write_to_slot))
            }
            OutlinedButton(
                onClick = { showExportPick = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.export_dump))
            }
            if (!entity.isLfDump()) {
                OutlinedButton(
                    onClick = { showMagicPick = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.write_magic_card))
                }
            }

            // ---- 写入进度 ----
            when (val ws = writeState) {
                WriteUiState.Idle -> {}
                is WriteUiState.Running -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            Text(
                                if (ws.total > 1) stringResource(ws.labelRes, ws.done, ws.total)
                                else stringResource(ws.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (ws.total > 1) {
                            LinearProgressIndicator(
                                progress = { ws.done.toFloat() / ws.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(onClick = { viewModel.cancelWrite() }) {
                            Text(stringResource(R.string.attack_cancel))
                        }
                    }
                }

                is WriteUiState.Done -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (ws.result.mismatchedBlocks.isEmpty()) {
                                stringResource(R.string.write_done_ok)
                            } else {
                                stringResource(
                                    R.string.write_done_mismatch_fmt,
                                    ws.result.mismatchedBlocks.size,
                                ) + "：" + ws.result.mismatchedBlocks.joinToString()
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ws.result.mismatchedBlocks.isEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { viewModel.resetWriteState() }) {
                            Text(stringResource(R.string.common_ok))
                        }
                    }
                }

                is WriteUiState.Failed -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(ws.msgRes, ws.cause ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { viewModel.resetWriteState() }) {
                            Text(stringResource(R.string.common_ok))
                        }
                    }
                }
            }
        }
    }

    // ---- 导出格式选择 ----
    if (showExportPick) {
        val entity = dump
        AlertDialog(
            onDismissRequest = { showExportPick = false },
            title = { Text(stringResource(R.string.export_format_title)) },
            text = {
                Column {
                    com.chamelex.protocol.DumpFormat.entries.forEach { format ->
                        TextButton(onClick = {
                            showExportPick = false
                            val time = java.text.SimpleDateFormat(
                                "MMdd-HHmmss", java.util.Locale.getDefault()
                            ).format(java.util.Date())
                            exportFormat = format
                            exportLauncher.launch("${entity?.name ?: "dump"}_$time.${format.extension}")
                        }) { Text(".${format.extension}") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportPick = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // ---- 卡槽选择（共用组件，默认激活槽） ----
    if (showSlotPicker) {
        val entity = dump
        val pickerSlots by viewModel.slotsForPicker.collectAsStateWithLifecycle()
        val slotList = pickerSlots
        if (slotList != null) {
            val active = slotList.firstOrNull { it.isActive }?.slot ?: SlotNumber.SLOT_1
            SlotPickerDialog(
                slots = slotList,
                initialSelected = active,
                onDismiss = { showSlotPicker = false },
                onConfirm = { slot ->
                    showSlotPicker = false
                    if (entity != null) viewModel.writeToSlot(entity, slot)
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

    // ---- 魔术卡类型选择 ----
    if (showMagicPick) {
        AlertDialog(
            onDismissRequest = { showMagicPick = false },
            title = { Text(stringResource(R.string.magic_pick_title)) },
            text = {
                Column {
                    TextButton(onClick = {
                        showMagicPick = false
                        magicConfirm = true
                    }) { Text(stringResource(R.string.magic_gen1a)) }
                    TextButton(onClick = {
                        showMagicPick = false
                        magicConfirm = false
                    }) { Text(stringResource(R.string.magic_gen2)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMagicPick = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // ---- 魔术卡危险确认 ----
    magicConfirm?.let { gen1a ->
        val entity = dump
        AlertDialog(
            onDismissRequest = { magicConfirm = null },
            title = {
                Text(
                    stringResource(R.string.magic_danger_title),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            text = { Text(stringResource(R.string.magic_danger_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        magicConfirm = null
                        if (entity != null) viewModel.writeMagicCard(entity, gen1a)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { magicConfirm = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 就地 dump 编辑器：块 hex 直接可编辑，统一「保存」提交（参考 NFC Tool）。 */
@Composable
private fun InPlaceDumpEditor(
    entity: DumpEntity,
    onSave: (Map<Int, ByteArray>) -> Unit,
) {
    val edits = androidx.compose.runtime.saveable.rememberSaveable(
        entity.id, entity.createdAt,
    ) { mutableMapOf<String, String>() }
    val valid = edits.values.all { it.length == 32 }

    for (sector in 0 until entity.sectorCount) {
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
            if (blockIndex >= entity.blockCount) break
            val isTrailer = i == blockCount - 1
            val readable = entity.isBlockReadable(blockIndex)
            val key = blockIndex.toString()
            if (readable) {
                com.chamelex.app.ui.components.EditableMctBlockRow(
                    blockIndex = blockIndex,
                    isTrailer = isTrailer,
                    label = if (blockIndex == 0) stringResource(R.string.block0_badge)
                    else stringResource(R.string.block_fmt, blockIndex),
                    value = edits[key] ?: entity.blockAt(blockIndex)
                        .joinToString("") { "%02X".format(it) },
                    modified = edits.containsKey(key),
                    onValueChange = { newHex ->
                        val initial = entity.blockAt(blockIndex)
                            .joinToString("") { "%02X".format(it) }
                        if (newHex == initial) edits.remove(key) else edits[key] = newHex
                    },
                    modifier = Modifier.background(
                        if (isTrailer) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    ),
                )
            } else {
                com.chamelex.app.ui.components.MctBlockRow(
                    blockIndex = blockIndex,
                    data = null,
                    isTrailer = isTrailer,
                    label = stringResource(R.string.block_fmt, blockIndex),
                    modifier = Modifier.background(
                        if (isTrailer) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    ),
                )
            }
        }
    }

    if (edits.isNotEmpty()) {
        Button(
            onClick = {
                if (!valid) return@Button
                onSave(edits.mapKeys { it.key.toInt() }.mapValues { (_, hex) ->
                    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                })
                edits.clear()
            },
            enabled = valid,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(
                stringResource(R.string.common_save) +
                        if (!valid) "（${stringResource(R.string.edit_invalid_hex)}）" else ""
            )
        }
    }
}
