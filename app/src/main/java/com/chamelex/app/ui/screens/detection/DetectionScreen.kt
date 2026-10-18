package com.chamelex.app.ui.screens.detection

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chamelex.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionScreen(
    viewModel: DetectionViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(context.getString(it)) }
        viewModel.loadLogs()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detection_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.fw_back),
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
            Text(
                stringResource(R.string.detection_guide),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                DetectionUiState.Idle -> {
                    Button(onClick = { viewModel.loadLogs() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.detection_load))
                    }
                }

                DetectionUiState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.detection_loading))
                }

                is DetectionUiState.LogList -> {
                    Text(
                        stringResource(R.string.detection_count_fmt, s.records.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (s.records.isEmpty()) {
                        Text(
                            stringResource(R.string.detection_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.recover() }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.detection_recover))
                            }
                            OutlinedButton(onClick = { viewModel.clearLogs() }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.detection_clear))
                            }
                        }
                        s.records.take(50).forEachIndexed { index, r ->
                            Text(
                                "#$index " + stringResource(
                                    R.string.detection_record_fmt,
                                    r.block, r.keyTypeLabel, "%08X".format(r.nt),
                                ) + " uid=%08X".format(r.uid),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }

                is DetectionUiState.Recovering -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.detection_recovering, s.done, s.total, s.found),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (s.total > 0) {
                            LinearProgressIndicator(
                                progress = { s.done.toFloat() / s.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(onClick = { viewModel.cancel() }) {
                            Text(stringResource(R.string.attack_cancel))
                        }
                    }
                }

                is DetectionUiState.Results -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.detection_results), style = MaterialTheme.typography.titleMedium)
                        if (s.keys.isNotEmpty()) {
                            TextButton(onClick = { viewModel.saveAll() }) {
                                Text(stringResource(R.string.detection_save_all))
                            }
                        }
                    }
                    if (s.keys.isEmpty()) {
                        Text(
                            stringResource(R.string.detection_no_key),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    s.keys.forEach { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(
                                            R.string.detection_key_fmt,
                                            "%08X".format(item.uid), item.block, item.keyTypeLabel(),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "%012X".format(item.key),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (item.saved) {
                                    Text(
                                        stringResource(R.string.dump_saved_mark),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    TextButton(onClick = { viewModel.saveKey(item) }) {
                                        Text(stringResource(R.string.detection_save))
                                    }
                                }
                            }
                        }
                    }
                }

                is DetectionUiState.Failed -> Text(
                    stringResource(s.msgRes, s.cause ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun RecoveredKey.keyTypeLabel(): String = if (isKeyB) "B" else "A"
