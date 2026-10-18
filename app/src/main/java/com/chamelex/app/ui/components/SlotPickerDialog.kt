package com.chamelex.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.chamelex.app.R
import com.chamelex.app.ui.screens.slots.nameRes
import com.chamelex.protocol.SlotInfo
import com.chamelex.protocol.SlotNumber
import com.chamelex.protocol.TagSpecificType

/**
 * 共用卡槽选择对话框：槽号 + 昵称（无昵称显示类型）+ HF/LF 类型 + 激活标记。
 * 用于破解结果页与数据管理详情页的「写入卡槽」入口。
 */
@Composable
fun SlotPickerDialog(
    slots: List<SlotInfo>,
    initialSelected: SlotNumber,
    onDismiss: () -> Unit,
    onConfirm: (SlotNumber) -> Unit,
) {
    var selected by remember { mutableStateOf(initialSelected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.write_to_slot_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                slots.forEach { slot ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = slot.slot }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == slot.slot, onClick = null)
                        Column(Modifier.padding(start = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.slot_title_fmt, slot.slot.uiIndex),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (slot.isActive) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.padding(start = 6.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.slot_active),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                }
                            }
                            Text(
                                run {
                                    val hf = slot.hfNick.ifEmpty { null }
                                        ?: stringResource(slot.hfType.nameRes())
                                    val lf = slot.lfNick.ifEmpty { null }
                                        ?: stringResource(slot.lfType.nameRes())
                                    "HF: $hf · LF: $lf"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
