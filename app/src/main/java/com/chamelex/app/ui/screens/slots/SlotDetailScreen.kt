package com.chamelex.app.ui.screens.slots

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chamelex.app.R
import com.chamelex.app.ui.screens.data.WriteUiState
import com.chamelex.protocol.SlotInfo
import com.chamelex.protocol.SlotNumber
import com.chamelex.protocol.SlotCommands
import com.chamelex.protocol.TagSenseType
import com.chamelex.protocol.TagSpecificType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotDetailScreen(
    slotIndex: Int,
    viewModel: SlotViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenEmuConfig: (SlotNumber) -> Unit,
) {
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val slot = slots.firstOrNull { it.slot.uiIndex == slotIndex }
    val writeState by viewModel.writeState.collectAsStateWithLifecycle()
    val savedDumps by viewModel.savedDumps.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var writeSourceDialog by remember { mutableStateOf(false) }
    var dumpPickDialog by remember { mutableStateOf(false) }

    // 从文件导入写入
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else "file"
                }
            }.getOrNull() ?: "file"
            slot?.let { viewModel.writeFileToSlot(it.slot, name, uri) }
        }
    }

    CollectSlotEvents(viewModel, snackbarHostState)

    var nickDialog by remember { mutableStateOf<TagSenseType?>(null) }
    var deleteDialog by remember { mutableStateOf<TagSenseType?>(null) }
    var resetPickDialog by remember { mutableStateOf(false) }
    var resetConfirmType by remember { mutableStateOf<TagSpecificType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.slot_detail_title)) },
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
            if (slot == null) {
                Text(
                    stringResource(R.string.slots_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(R.string.slot_title_fmt, slot.slot.uiIndex) +
                            if (slot.isActive) "（${stringResource(R.string.slot_active)}）" else "",
                    style = MaterialTheme.typography.titleLarge,
                )

                SenseSection(
                    senseLabel = stringResource(R.string.slot_hf_label),
                    type = slot.hfType,
                    nick = slot.hfNick,
                    enabled = slot.hfEnabled,
                    onToggle = { viewModel.setEnabled(slot.slot, TagSenseType.HF, it) },
                    onEditNick = { nickDialog = TagSenseType.HF },
                    onDelete = { deleteDialog = TagSenseType.HF },
                    deleteLabel = stringResource(R.string.delete_hf_data),
                )
                SenseSection(
                    senseLabel = stringResource(R.string.slot_lf_label),
                    type = slot.lfType,
                    nick = slot.lfNick,
                    enabled = slot.lfEnabled,
                    onToggle = { viewModel.setEnabled(slot.slot, TagSenseType.LF, it) },
                    onEditNick = { nickDialog = TagSenseType.LF },
                    onDelete = { deleteDialog = TagSenseType.LF },
                    deleteLabel = stringResource(R.string.delete_lf_data),
                )

                Button(
                    onClick = { writeSourceDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = writeState !is WriteUiState.Running,
                ) {
                    Text(stringResource(R.string.slot_write_data))
                }

                WriteStatusCard(writeState, viewModel)

                Button(
                    onClick = { onOpenEmuConfig(slot.slot) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = slot.hfType != TagSpecificType.UNDEFINED,
                ) {
                    Text(stringResource(R.string.emu_config_entry))
                }

                OutlinedButton(
                    onClick = { resetPickDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.reset_button))
                }
            }
        }
    }

    // ---- 写入数据来源选择 ----
    if (writeSourceDialog) {
        WriteSourceDialog(
            onDismiss = { writeSourceDialog = false },
            onPickDump = {
                writeSourceDialog = false
                dumpPickDialog = true
            },
            onPickFile = {
                writeSourceDialog = false
                importLauncher.launch(arrayOf("*/*"))
            },
        )
    }

    // ---- dump 选择 ----
    if (dumpPickDialog) {
        DumpPickDialog(
            dumps = savedDumps,
            onDismiss = { dumpPickDialog = false },
            onConfirm = { entity ->
                dumpPickDialog = false
                slot?.let { viewModel.writeDumpToSlot(entity, it.slot) }
            },
        )
    }

    // ---- 修改昵称对话框 ----
    nickDialog?.let { sense ->
        val currentNick = when (sense) {
            TagSenseType.HF -> slot?.hfNick
            TagSenseType.LF -> slot?.lfNick
            else -> null
        }.orEmpty()
        NickDialog(
            senseLabel = sense.name,
            initial = currentNick,
            onDismiss = { nickDialog = null },
            onConfirm = { name ->
                slot?.let { viewModel.setNick(it.slot, sense, name) }
                nickDialog = null
            },
        )
    }

    // ---- 删除频段数据确认 ----
    deleteDialog?.let { sense ->
        AlertDialog(
            onDismissRequest = { deleteDialog = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_confirm_msg_fmt,
                        slotIndex,
                        sense.name,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    slot?.let { viewModel.deleteSense(it.slot, sense) }
                    deleteDialog = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialog = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // ---- 重置：卡型选择 ----
    if (resetPickDialog) {
        AlertDialog(
            onDismissRequest = { resetPickDialog = false },
            title = { Text(stringResource(R.string.reset_pick_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    RESETTABLE_TYPES.forEach { type ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = false,
                                    onClick = {
                                        resetPickDialog = false
                                        resetConfirmType = type
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = false, onClick = null)
                            Text(
                                stringResource(type.nameRes()),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { resetPickDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // ---- 重置：红色危险确认 ----
    resetConfirmType?.let { type ->
        AlertDialog(
            onDismissRequest = { resetConfirmType = null },
            title = {
                Text(
                    stringResource(R.string.reset_confirm_title),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.reset_confirm_msg_fmt,
                        slotIndex,
                        stringResource(type.nameRes()),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        slot?.let { viewModel.resetSlot(it.slot, type) }
                        resetConfirmType = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirmType = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SenseSection(
    senseLabel: String,
    type: TagSpecificType,
    nick: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEditNick: () -> Unit,
    onDelete: () -> Unit,
    deleteLabel: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(senseLabel, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (enabled) R.string.slot_enabled else R.string.slot_disabled),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
            }
            Text(stringResource(type.nameRes()), style = MaterialTheme.typography.bodyLarge)
            if (nick.isNotEmpty()) {
                Text(
                    nick,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditNick) {
                    Text(stringResource(R.string.nick_modify))
                }
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(deleteLabel)
                }
            }
        }
    }
}

@Composable
private fun NickDialog(
    senseLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val tooLong = text.encodeToByteArray().size > SlotCommands.MAX_NICK_BYTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nick_dialog_title_fmt, senseLabel)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = tooLong,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tooLong) {
                    Text(
                        stringResource(R.string.nick_too_long),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = !tooLong) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            Row {
                // 空输入 = 清除昵称
                TextButton(onClick = { onConfirm("") }) {
                    Text(stringResource(R.string.nick_clear))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

/** 写入来源选择：数据管理 dump / 本地文件。 */
@Composable
private fun WriteSourceDialog(
    onDismiss: () -> Unit,
    onPickDump: () -> Unit,
    onPickFile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.slot_write_data)) },
        text = {
            Column {
                TextButton(onClick = onPickDump) {
                    Text(stringResource(R.string.write_source_dump))
                }
                TextButton(onClick = onPickFile) {
                    Text(stringResource(R.string.write_source_file))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** dump 选择对话框（名称/类型/来源/时间）。 */
@Composable
private fun DumpPickDialog(
    dumps: List<com.chamelex.data.db.DumpEntity>,
    onDismiss: () -> Unit,
    onConfirm: (com.chamelex.data.db.DumpEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.write_source_dump)) },
        text = {
            if (dumps.isEmpty()) {
                Text(
                    stringResource(R.string.data_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    dumps.forEach { dump ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(dump) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(dump.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${com.chamelex.app.ui.screens.data.dumpTypeName(dump)} · " +
                                        com.chamelex.app.ui.screens.data.sourceName(dump.source) +
                                        " · " + stringResource(R.string.data_time_fmt, dump.createdAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** 写入进度与结果（与数据管理页一致）。 */
@Composable
private fun WriteStatusCard(writeState: WriteUiState, viewModel: SlotViewModel) {
    when (val ws = writeState) {
        WriteUiState.Idle -> {}
        is WriteUiState.Running -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
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
                        )
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
