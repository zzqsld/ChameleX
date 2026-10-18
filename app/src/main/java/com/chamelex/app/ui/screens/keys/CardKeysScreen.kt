package com.chamelex.app.ui.screens.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chamelex.app.R
import com.chamelex.data.db.CardKeysEntity

/** 按卡记忆：UID → 扇区密钥映射列表与明细。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardKeysScreen(
    viewModel: KeysViewModel,
    onBack: () -> Unit,
) {
    val cards by viewModel.rememberedCards.collectAsStateWithLifecycle()
    var detailFor by remember { mutableStateOf<CardKeysEntity?>(null) }
    var renameFor by remember { mutableStateOf<CardKeysEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keys_card_memory)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                stringResource(R.string.keys_card_memory_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cards.isEmpty()) {
                Text(
                    stringResource(R.string.keys_card_empty),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cards, key = { it.uid }) { card ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { detailFor = card },
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                card.nickname?.takeIf { it.isNotBlank() } ?: card.uid,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "UID ${card.uid} · " +
                                        stringResource(R.string.keys_card_keys_fmt, card.knownKeyCount) +
                                        " · " + stringResource(R.string.data_time_fmt, card.lastUsedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 明细 ----
    detailFor?.let { card ->
        val pairs = viewModel.cardKeyPairs(card)
        AlertDialog(
            onDismissRequest = { detailFor = null },
            title = { Text(card.nickname?.takeIf { it.isNotBlank() } ?: card.uid) },
            text = {
                Column {
                    Row {
                        Text("扇区", Modifier.weight(0.25f), style = MaterialTheme.typography.labelMedium)
                        Text("KeyA", Modifier.weight(0.375f), style = MaterialTheme.typography.labelMedium)
                        Text("KeyB", Modifier.weight(0.375f), style = MaterialTheme.typography.labelMedium)
                    }
                    pairs.forEachIndexed { sector, (keyA, keyB) ->
                        Row {
                            Text("$sector", Modifier.weight(0.25f), style = MaterialTheme.typography.bodySmall)
                            Text(
                                keyA?.joinToString("") { "%02X".format(it) } ?: "------",
                                Modifier.weight(0.375f),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            Text(
                                keyB?.joinToString("") { "%02X".format(it) } ?: "------",
                                Modifier.weight(0.375f),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        renameFor = card
                        detailFor = null
                    }) { Text(stringResource(R.string.keys_card_rename)) }
                    TextButton(onClick = {
                        viewModel.deleteRememberedCard(card.uid)
                        detailFor = null
                    }) { Text(stringResource(R.string.keys_card_delete)) }
                    TextButton(onClick = { detailFor = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            },
        )
    }

    // ---- 昵称 ----
    renameFor?.let { card ->
        var name by remember { mutableStateOf(card.nickname ?: "") }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.keys_card_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.keys_nickname_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameRememberedCard(card.uid, name)
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
}
