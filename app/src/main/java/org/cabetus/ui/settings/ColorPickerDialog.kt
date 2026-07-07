package org.cabetus.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * HSV スライダー式のカラーピッカーダイアログ。外部ライブラリ不要。
 * 初期色を HSV に分解し、色相・彩度・明度の3スライダーで調整する。
 */
@Composable
fun ColorPickerDialog(
    initial: Color,
    onPick: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }

    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("色を選ぶ") },
        text = {
            Column {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color),
                )
                Text("色相", style = MaterialTheme.typography.labelMedium)
                Slider(value = h, onValueChange = { h = it }, valueRange = 0f..360f)
                Text("彩度", style = MaterialTheme.typography.labelMedium)
                Slider(value = s, onValueChange = { s = it }, valueRange = 0f..1f)
                Text("明度", style = MaterialTheme.typography.labelMedium)
                Slider(value = v, onValueChange = { v = it }, valueRange = 0f..1f)
                Text(
                    "#%08X".format(color.toArgb()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(color); onDismiss() }) { Text("決定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
