package com.chamelex.app.ui.screens.slots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chamelex.app.R
import com.chamelex.connection.ConnState
import com.chamelex.protocol.SlotInfo
import com.chamelex.protocol.SlotNumber
import com.chamelex.protocol.TagSpecificType

/** 收集 SlotViewModel 的操作失败事件并弹 Snackbar（各卡槽页面都需调用）。 */
@Composable
fun CollectSlotEvents(viewModel: SlotViewModel, snackbarHostState: SnackbarHostState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbarHostState.showSnackbar(
                context.getString(
                    R.string.op_failed_fmt,
                    context.getString(event.opRes),
                    event.cause ?: "",
                )
            )
        }
    }
}

@Composable
fun SlotsScreen(
    viewModel: SlotViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenDetail: (SlotNumber) -> Unit,
) {
    val connState by viewModel.connState.collectAsStateWithLifecycle()
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    CollectSlotEvents(viewModel, snackbarHostState)

    LaunchedEffect(connState) {
        if (connState is ConnState.Connected) viewModel.refresh()
    }

    when {
        connState !is ConnState.Connected -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.slots_not_connected),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        slots.isEmpty() && loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.slots_loading),
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(slots, key = { it.slot.uiIndex }) { slot ->
                    SlotCard(
                        slot = slot,
                        onClick = { viewModel.activate(slot.slot) },
                        onMore = { onOpenDetail(slot.slot) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotCard(
    slot: SlotInfo,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    Card(
        onClick = onClick,
        border = if (slot.isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (slot.isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.slot_title_fmt, slot.slot.uiIndex),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (slot.isActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            stringResource(R.string.slot_active),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            Spacer(Modifier.padding(top = 8.dp))
            SenseLine(
                label = stringResource(R.string.slot_hf_label),
                type = slot.hfType,
                nick = slot.hfNick,
                enabled = slot.hfEnabled,
            )
            SenseLine(
                label = stringResource(R.string.slot_lf_label),
                type = slot.lfType,
                nick = slot.lfNick,
                enabled = slot.lfEnabled,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onMore) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.slot_more),
                    )
                }
            }
        }
    }
}

@Composable
private fun SenseLine(label: String, type: TagSpecificType, nick: String, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            // 无昵称时显示类型名
            nick.ifEmpty { stringResource(type.nameRes()) },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(if (enabled) R.string.slot_enabled else R.string.slot_disabled),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
