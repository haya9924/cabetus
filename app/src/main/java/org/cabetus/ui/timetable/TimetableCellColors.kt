package org.cabetus.ui.timetable

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.cabetus.data.settings.TimetableColorMode

internal fun timetableCellColors(scheme: ColorScheme, mode: TimetableColorMode,
                                cell: TimetableCellUi?, risky: Boolean): Pair<Color, Color> {
    if (risky) return scheme.errorContainer to scheme.onErrorContainer
    if (cell == null) return scheme.surfaceContainerLow to scheme.onSurfaceVariant
    val background = when (mode) {
        TimetableColorMode.ATTENDANCE_RATE -> cell.effectiveRatePercent?.let { rate ->
            val dark = scheme.surface.luminance() < 0.5f
            val green = if (dark) Color(0xFF286244) else Color(0xFF92D7A8)
            lerp(scheme.surfaceContainerHigh, green, rate.coerceIn(0,100) / 100f)
        } ?: scheme.surfaceContainerHigh
        TimetableColorMode.MANUAL -> cell.manualColorArgb?.let { Color(it) } ?: scheme.surfaceContainerHigh
    }
    // WCAG相対輝度による黒白の大きい方のコントラストを選択。
    val foreground = if (background.luminance() > 0.179f) Color(0xFF111111) else Color.White
    return background to foreground
}
