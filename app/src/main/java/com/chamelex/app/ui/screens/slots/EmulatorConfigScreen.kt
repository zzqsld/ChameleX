package com.chamelex.app.ui.screens.slots

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chamelex.app.R
import com.chamelex.protocol.MifareClassicWriteMode
import com.chamelex.protocol.SlotNumber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulatorConfigScreen(
    slotIndex: Int,
    viewModel: SlotViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val state by viewModel.emuState.collectAsStateWithLifecycle()

    CollectSlotEvents(viewModel, snackbarHostState)

    LaunchedEffect(slotIndex) {
        viewModel.loadEmulatorConfig(SlotNumber.fromUi(slotIndex))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${stringResource(R.string.emu_title)} · " +
                                stringResource(R.string.slot_title_fmt, slotIndex)
                    )
                },
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
            when (val s = state) {
                EmuConfigState.Idle, EmuConfigState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.emu_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is EmuConfigState.M1 -> M1ConfigSection(s, viewModel)

                is EmuConfigState.Ntag -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        ConfigSwitchRow(
                            title = stringResource(R.string.emu_uid_magic),
                            desc = stringResource(R.string.emu_uid_magic_desc),
                            checked = s.uidMagic,
                            onCheckedChange = viewModel::setUidMagic,
                        )
                    }
                }

                EmuConfigState.Unsupported -> Text(
                    stringResource(R.string.emu_unsupported),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is EmuConfigState.Failed -> Text(
                    stringResource(R.string.emu_apply_failed_fmt, s.cause ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun M1ConfigSection(state: EmuConfigState.M1, viewModel: SlotViewModel) {
    val config = state.config
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            ConfigSwitchRow(
                title = stringResource(R.string.emu_detection),
                desc = stringResource(R.string.emu_detection_desc) + "\n" +
                        stringResource(R.string.emu_detection_hint),
                checked = config.detection,
                onCheckedChange = viewModel::setDetection,
            )
            ConfigSwitchRow(
                title = stringResource(R.string.emu_gen1a),
                desc = stringResource(R.string.emu_gen1a_desc),
                checked = config.gen1a,
                onCheckedChange = viewModel::setGen1a,
            )
            ConfigSwitchRow(
                title = stringResource(R.string.emu_gen2),
                desc = stringResource(R.string.emu_gen2_desc),
                checked = config.gen2,
                onCheckedChange = viewModel::setGen2,
            )
            ConfigSwitchRow(
                title = stringResource(R.string.emu_anticoll),
                desc = stringResource(R.string.emu_anticoll_desc),
                checked = config.antiCollFromBlock0,
                onCheckedChange = viewModel::setAntiColl,
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.emu_write_mode), style = MaterialTheme.typography.titleMedium)
            val modes = listOf(
                Triple(
                    MifareClassicWriteMode.NORMAL,
                    stringResource(R.string.write_mode_normal),
                    stringResource(R.string.write_mode_normal_desc),
                ),
                Triple(
                    MifareClassicWriteMode.DENIED,
                    stringResource(R.string.write_mode_denied),
                    stringResource(R.string.write_mode_denied_desc),
                ),
                Triple(
                    MifareClassicWriteMode.DECEIVE,
                    stringResource(R.string.write_mode_deceive),
                    stringResource(R.string.write_mode_deceive_desc),
                ),
                Triple(
                    MifareClassicWriteMode.SHADOW,
                    stringResource(R.string.write_mode_shadow),
                    stringResource(R.string.write_mode_shadow_desc),
                ),
            )
            Column(Modifier.selectableGroup()) {
                modes.forEach { (mode, name, desc) ->
                    val selected = config.writeMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { if (!selected) viewModel.setWriteMode(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
