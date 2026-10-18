package com.chamelex.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 单个密钥位（扇区×KeyA/B）状态。 */
enum class KeyBitState {
    /** 未检测/检测中 */
    UNKNOWN,

    /** 已解出 */
    FOUND,

    /** 已检测未命中 */
    MISSING,
}

/**
 * 扇区密钥状态网格（参考 NFC Tool 读卡进度）：
 * 「S: 00 01 ...」一行扇区号，「A:」「B:」两行圆形图标（对钩=已解出，叉=未命中，空心圈=未检测）。
 * 扇区多时横向滚动。
 */
@Composable
fun SectorKeyGrid(
    sectorCount: Int,
    states: List<KeyBitState>,
    modifier: Modifier = Modifier,
) {
    require(states.size >= sectorCount * 2)
    val scroll = rememberScrollState()
    Column(modifier.horizontalScroll(scroll)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "S:",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (sector in 0 until sectorCount) {
                Text(
                    "%02d".format(sector),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        KeyRow("A:", states, sectorCount, offset = 0)
        KeyRow("B:", states, sectorCount, offset = 1)
    }
}

@Composable
private fun KeyRow(label: String, states: List<KeyBitState>, sectorCount: Int, offset: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (sector in 0 until sectorCount) {
            val state = states[sector * 2 + offset]
            val (icon, tint) = when (state) {
                KeyBitState.FOUND -> Icons.Filled.Check to MaterialTheme.colorScheme.primary
                KeyBitState.MISSING -> Icons.Filled.Close to MaterialTheme.colorScheme.error
                KeyBitState.UNKNOWN -> Icons.Filled.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(16.dp),
            )
        }
    }
}
