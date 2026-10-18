package com.chamelex.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 就地可编辑的 dump 块行（参考 NFC Tool）：hex 直接可改，区域配色与 MctBlockRow 一致，
 * 已修改未保存的块在块号前显示圆点标记。
 */
@Composable
fun EditableMctBlockRow(
    blockIndex: Int,
    isTrailer: Boolean,
    label: String,
    value: String,
    modified: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBlock0 = blockIndex == 0
    val keyAColor = MaterialTheme.colorScheme.primary
    val acColor = MaterialTheme.colorScheme.tertiary
    val keyBColor = MaterialTheme.colorScheme.secondary
    val defaultColor = MaterialTheme.colorScheme.onSurface
    val transformation = remember(isBlock0, isTrailer, keyAColor, acColor, keyBColor, defaultColor) {
        MctHexColorTransformation(isBlock0, isTrailer, keyAColor, acColor, keyBColor, defaultColor)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (modified) {
                Icon(
                    Icons.Filled.Circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .padding(end = 2.dp)
                        .size(6.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = { input ->
                onValueChange(input.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(32).uppercase())
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = defaultColor,
            ),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            visualTransformation = transformation,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 按字符位置对 32 位 hex 着色的 VisualTransformation（区域与 MctBlockRow 一致）。 */
private class MctHexColorTransformation(
    private val isBlock0: Boolean,
    private val isTrailer: Boolean,
    private val keyAColor: Color,
    private val acColor: Color,
    private val keyBColor: Color,
    private val defaultColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val colored = buildAnnotatedString {
            text.text.forEachIndexed { i, c ->
                val color = when {
                    isBlock0 -> keyAColor
                    // 32 hex 字符：KeyA 0-11 / AC+GPB 12-19 / KeyB 20-31
                    isTrailer && i < 12 -> keyAColor
                    isTrailer && i < 20 -> acColor
                    isTrailer -> keyBColor
                    else -> defaultColor
                }
                withStyle(SpanStyle(color = color)) { append(c) }
            }
        }
        return TransformedText(colored, OffsetMapping.Identity)
    }
}
