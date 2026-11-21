package org.cabetus.ui.timetable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.cabetus.domain.AttendanceGoal
import org.cabetus.domain.AttendanceGoalCalculator
import org.cabetus.domain.AttendanceGoalMode

/**
 * 目標線の位置（0f..1f）。目標が未設定・範囲外なら null。
 *
 * PERCENT は value/100。COUNT は座標を丸めず value/totalClasses をそのまま使う。
 */
internal fun attendanceGoalFraction(goal: AttendanceGoal?): Float? {
    if (goal == null || !AttendanceGoalCalculator.validate(goal)) return null
    return when (goal.mode) {
        AttendanceGoalMode.PERCENT -> goal.value / 100f
        AttendanceGoalMode.COUNT -> goal.value.toFloat() / goal.totalClasses.toFloat()
    }
}

/** 目標ラベル。目標が未設定・範囲外なら null。 */
internal fun attendanceGoalLabel(goal: AttendanceGoal?): String? {
    if (goal == null || !AttendanceGoalCalculator.validate(goal)) return null
    return when (goal.mode) {
        AttendanceGoalMode.PERCENT -> "目標 ${goal.value}%"
        AttendanceGoalMode.COUNT -> "目標 ${goal.value} / ${goal.totalClasses}回"
    }
}

/**
 * アプリ集計の出席率（0〜100）を表す棒と、赤い垂直の目標線。
 *
 * [ratePercent] が null のときは「記録なし」と表示し、棒は塗らない。
 * [goal] が未設定・範囲外なら目標線を描かない。
 * 0%・100% の位置でも線が欠けないよう、線幅の半分だけ内側に座標を収める。
 */
@Composable
fun AttendanceRateBar(
    ratePercent: Int?,
    goal: AttendanceGoal?,
    modifier: Modifier = Modifier,
) {
    val rateText = ratePercent?.let { "${it}%" } ?: "記録なし"
    val goalLabel = attendanceGoalLabel(goal)
    val goalFraction = attendanceGoalFraction(goal)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    val goalColor = MaterialTheme.colorScheme.error

    val description = buildString {
        append("出席率（アプリ集計） $rateText")
        goalLabel?.let { append("、$it") }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "出席率（アプリ集計）",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                rateText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .semantics { contentDescription = description },
        ) {
            val lineWidth = 3.dp.toPx()
            // 目標線（赤）が 0%・100% でも切れないよう、半分の幅を内側に確保する。
            val inset = lineWidth / 2f
            val left = inset
            val right = (size.width - inset).coerceAtLeast(left + 1f)
            val span = right - left
            val corner = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(left, 0f),
                size = Size(span, size.height),
                cornerRadius = corner,
            )
            val rate = ratePercent?.coerceIn(0, 100)
            if (rate != null) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(left, 0f),
                    size = Size(span * rate / 100f, size.height),
                    cornerRadius = corner,
                )
            }
            goalFraction?.let { fraction ->
                val x = left + span * fraction.coerceIn(0f, 1f)
                drawLine(
                    color = goalColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = lineWidth,
                    cap = StrokeCap.Butt,
                )
            }
        }
        goalLabel?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = goalColor)
        }
    }
}
