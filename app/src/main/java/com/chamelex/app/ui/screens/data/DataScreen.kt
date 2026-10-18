package com.chamelex.app.ui.screens.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chamelex.app.R
import com.chamelex.app.ui.screens.slots.nameRes
import com.chamelex.data.db.DumpEntity
import com.chamelex.protocol.DumpFormat
import com.chamelex.protocol.MfcType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DataScreen(
    viewModel: DataViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenDetail: (Long) -> Unit,
) {
    val dumps by viewModel.dumps.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var menuFor by remember { mutableStateOf<DumpEntity?>(null) }
    var renameFor by remember { mutableStateOf<DumpEntity?>(null) }
    var exportFor by remember { mutableStateOf<DumpEntity?>(null) }

    // 导入
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else "dump"
                }
            }.getOrNull() ?: "dump.bin"
            viewModel.importDump(name, uri)
        }
    }

    // 导出
    var exportFormat by remember { mutableStateOf<DumpFormat?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val entity = exportFor
        val format = exportFormat
        if (uri != null && entity != null && format != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(viewModel.exportDump(entity, format))
                }
            }
        }
        exportFor = null
        exportFormat = null
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(context.getString(it)) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.import_dump))
            }
        }

        if (dumps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.data_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(dumps, key = { it.id }) { dump ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDetail(dump.id) },
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(dump.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${dumpTypeName(dump)} · UID ${dump.uid} · " +
                                        if (dump.isLfDump()) "" else stringResource(
                                            R.string.data_block_count_fmt, dump.blockCount
                                        ) + " · ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.data_time_fmt, dump.createdAt) + " · " +
                                        sourceName(dump.source),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { menuFor = dump }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                    }
                }
            }
        }
    }

    // ---- 条目菜单 ----
    menuFor?.let { dump ->
        DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = {
                    renameFor = dump
                    menuFor = null
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_dump)) },
                onClick = {
                    exportFor = dump
                    menuFor = null
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.data_delete)) },
                onClick = {
                    viewModel.delete(dump.id)
                    menuFor = null
                },
            )
        }
    }

    // ---- 重命名 ----
    renameFor?.let { dump ->
        var text by remember { mutableStateOf(dump.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.rename_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(dump, text)
                    renameFor = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameFor = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // ---- 导出格式选择 ----
    exportFor?.let { dump ->
        AlertDialog(
            onDismissRequest = { exportFor = null },
            title = { Text(stringResource(R.string.export_format_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DumpFormat.entries.forEach { format ->
                        TextButton(onClick = {
                            val time = SimpleDateFormat("MMdd-HHmmss", Locale.getDefault()).format(Date())
                            exportFormat = format
                            exportLauncher.launch("${dump.name}_$time.${format.extension}")
                        }) { Text(".${format.extension}") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { exportFor = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
fun dumpTypeName(dump: DumpEntity): String = when {
    dump.isLfDump() -> stringResource(dump.lfType().nameRes())
    else -> when (MfcType.fromSak(dump.tagType)) {
        MfcType.CLASSIC_1K -> stringResource(R.string.mfc_type_1k)
        MfcType.CLASSIC_4K -> stringResource(R.string.mfc_type_4k)
        MfcType.MINI -> stringResource(R.string.mfc_type_mini)
        MfcType.CLASSIC_2K -> stringResource(R.string.mfc_type_2k)
        null -> stringResource(R.string.mfc_type_unknown)
    }
}

@Composable
fun sourceName(source: String): String = when (source) {
    "dict_attack" -> stringResource(R.string.data_source_dict_attack)
    "crack" -> stringResource(R.string.data_source_crack)
    "scan" -> stringResource(R.string.data_source_scan)
    "import" -> stringResource(R.string.data_source_import)
    else -> source
}
