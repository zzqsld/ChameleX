package com.chamelex.app.ui.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.chamelex.connection.ConnState
import com.chamelex.protocol.HfCardKind
import com.chamelex.protocol.HfTagInfo
import com.chamelex.protocol.LfTagInfo
import com.chamelex.protocol.MfcType
import com.chamelex.protocol.classifyHfTag

private fun HfCardKind.cardNameRes(): Int = when (this) {
    HfCardKind.MFC_1K -> R.string.card_kind_mfc_1k
    HfCardKind.MFC_4K -> R.string.card_kind_mfc_4k
    HfCardKind.MFC_MINI -> R.string.card_kind_mfc_mini
    HfCardKind.ULTRALIGHT_NTAG -> R.string.card_kind_ul_ntag
    HfCardKind.ISO14443_4_CPU -> R.string.card_kind_cpu
    HfCardKind.UNKNOWN_14A -> R.string.card_kind_unknown_14a
}

private fun HfCardKind.hintRes(): Int = when (this) {
    HfCardKind.MFC_1K, HfCardKind.MFC_4K, HfCardKind.MFC_MINI -> R.string.card_hint_mfc
    HfCardKind.ULTRALIGHT_NTAG -> R.string.card_hint_ul_ntag
    HfCardKind.ISO14443_4_CPU, HfCardKind.UNKNOWN_14A -> R.string.card_hint_cpu
}

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenDetection: () -> Unit,
) {
    val connState by viewModel.connState.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(context.getString(it)) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (connState !is ConnState.Connected) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.reader_not_connected),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Button(
            onClick = { viewModel.scan() },
            modifier = Modifier.fillMaxWidth(),
            enabled = scanState !is ScanState.Scanning,
        ) {
            Text(stringResource(R.string.reader_scan_start))
        }

        OutlinedButton(
            onClick = onOpenDetection,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.detection_entry))
        }

        Text(
            stringResource(R.string.reader_scan_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val s = scanState) {
            ScanState.Idle -> {}

            ScanState.Scanning -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.reader_scanning),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            ScanState.NoTag -> Text(
                stringResource(R.string.reader_no_tag),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ScanState.LiteNoReader -> Text(
                stringResource(R.string.scan_lite_no_reader),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )

            is ScanState.Failed -> Text(
                stringResource(R.string.scan_failed_fmt, s.cause ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )

            is ScanState.HfResult -> {
                Text(stringResource(R.string.reader_result_title), style = MaterialTheme.typography.titleMedium)
                s.tags.forEach { tag -> HfTagCard(tag, viewModel) }
                Text(
                    stringResource(R.string.card_hint_14b_15693),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AttackSection(viewModel)
                SlotWriteStatus(viewModel)
            }

            is ScanState.LfResult -> {
                Text(stringResource(R.string.reader_result_title), style = MaterialTheme.typography.titleMedium)
                LfTagCard(s.tag)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.saveLfResult(s.tag) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.save_to_data))
                    }
                    OutlinedButton(
                        onClick = { viewModel.writeLfToCurrentSlot(s.tag) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.save_to_slot))
                    }
                }
                SlotWriteStatus(viewModel)
            }
        }
    }
}

@Composable
private fun HfTagCard(tag: HfTagInfo, viewModel: ReaderViewModel) {
    val kind = classifyHfTag(tag)
    val attackState by viewModel.attackState.collectAsStateWithLifecycle()
    val autopwnState by viewModel.autopwnState.collectAsStateWithLifecycle()
    var dictDialogFor by remember { mutableStateOf<((HfTagInfo, DictMode) -> Unit)?>(null) }
    // M1 卡才提供字典读取/自动解密入口
    val isM1 = MfcType.fromSak(tag.sak) != null
    val busy = attackState is AttackState.Running || autopwnState is AutopwnUiState.Running

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoRow(stringResource(R.string.reader_uid), tag.uidHex.uppercase())
            InfoRow("ATQA", tag.atqaHex.uppercase())
            InfoRow("SAK", "%02X".format(tag.sak))
            if (tag.ats.isNotEmpty()) {
                InfoRow("ATS", tag.atsHex.uppercase())
            }
            InfoRow(stringResource(R.string.reader_card_type), stringResource(kind.cardNameRes()))
            Text(
                stringResource(kind.hintRes()),
                style = MaterialTheme.typography.bodySmall,
                color = if (kind == HfCardKind.MFC_1K || kind == HfCardKind.MFC_4K || kind == HfCardKind.MFC_MINI)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isM1 && attackState is AttackState.Idle && autopwnState is AutopwnUiState.Idle) {
                OutlinedButton(
                    onClick = { dictDialogFor = { t, m -> viewModel.startAttack(t, m) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.attack_button))
                }
                Button(
                    onClick = { viewModel.startAutopwn(tag, DictMode.FULL) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.autopwn_button))
                }
            }
        }
    }

    dictDialogFor?.let { action ->
        AlertDialog(
            onDismissRequest = { dictDialogFor = null },
            title = { Text(stringResource(R.string.attack_pick_dict)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        action(tag, DictMode.QUICK)
                        dictDialogFor = null
                    }) { Text(stringResource(R.string.attack_quick)) }
                    TextButton(onClick = {
                        action(tag, DictMode.FULL)
                        dictDialogFor = null
                    }) { Text(stringResource(R.string.attack_full)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { dictDialogFor = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun LfTagCard(tag: LfTagInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (tag) {
                is LfTagInfo.Em410x -> {
                    InfoRow(stringResource(R.string.reader_card_type), stringResource(tag.type.nameRes()))
                    InfoRow(stringResource(R.string.reader_lf_id), tag.idHex.uppercase())
                }

                is LfTagInfo.HidProx -> {
                    InfoRow(stringResource(R.string.reader_card_type), "HIDProx")
                    InfoRow(
                        stringResource(R.string.reader_hid_format),
                        tag.formatEnum?.let {
                            stringResource(R.string.reader_hid_bits_fmt, it.label, it.bits)
                        } ?: stringResource(R.string.tag_type_unknown),
                    )
                    if (tag.facilityCode > 0) {
                        InfoRow(stringResource(R.string.reader_hid_fc), tag.facilityCode.toString())
                    }
                    InfoRow(stringResource(R.string.reader_hid_cn), tag.cardNumber.toString())
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
