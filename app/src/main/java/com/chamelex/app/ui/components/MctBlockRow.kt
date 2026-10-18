package com.chamelex.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MCT 风格的 dump 块行：16 字节按 4 字节分组显示。
 * 配色：0 块整体高亮；trailer 块 KeyA/访问位+GPB/KeyB 三色；未读出块灰色斜体。
 */
@Composable
fun MctBlockRow(
    blockIndex: Int,
    data: ByteArray?,
    isTrailer: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val isBlock0 = blockIndex == 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        if (data == null) {
            Text(
                "---- ---- ---- ----",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
        Text(mctHexText(data, isBlock0, isTrailer))
    }
}

/** 按字节分段着色（KeyA 0-5 / AC+GPB 6-9 / KeyB 10-15），每 4 字节插空格分组。 */
@Composable
private fun mctHexText(data: ByteArray, isBlock0: Boolean, isTrailer: Boolean): AnnotatedString {
    val keyAColor = MaterialTheme.colorScheme.primary
    val acColor = MaterialTheme.colorScheme.tertiary
    val keyBColor = MaterialTheme.colorScheme.secondary
    val defaultColor = MaterialTheme.colorScheme.onSurface
    val block0Color = MaterialTheme.colorScheme.primary

    return buildAnnotatedString {
        val base = SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        // 按字节定色（trailer 的 KeyA 0-5 / AC+GPB 6-9 / KeyB 10-15 跨分组边界），每 4 字节插空格
        for (i in data.indices) {
            val color = when {
                isBlock0 -> block0Color
                isTrailer && i < 6 -> keyAColor
                isTrailer && i < 10 -> acColor
                isTrailer -> keyBColor
                else -> defaultColor
            }
            withStyle(base.merge(SpanStyle(color = color))) {
                append("%02X".format(data[i]))
            }
            if (i % 4 == 3 && i < 15) append(' ')
        }
    }
}
