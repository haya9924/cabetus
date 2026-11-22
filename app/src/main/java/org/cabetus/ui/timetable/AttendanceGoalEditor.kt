package org.cabetus.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/** 出席目標の指定方法（出席率）。 */
const val ATTENDANCE_GOAL_MODE_PERCENT = "PERCENT"

/** 出席目標の指定方法（出席回数）。 */
const val ATTENDANCE_GOAL_MODE_COUNT = "COUNT"

internal const val GOAL_TOTAL_MIN = 1
internal const val GOAL_TOTAL_MAX = 200
internal const val GOAL_PERCENT_MIN = 1
internal const val GOAL_PERCENT_MAX = 100

/** 未設定時の総授業数の初期値。 */
internal const val GOAL_TOTAL_DEFAULT = 15

/** 未設定時の目標値の初期値（%）。 */
internal const val GOAL_VALUE_DEFAULT = 80

internal const val GOAL_NOTE =
    "総授業数は休講を除いた予定回数。遅刻は現在の集計では出席に含みません。この計算で未記録を欠席にはしません。未取得の回は把握できないため、表示は記録済み分に基づく暫定値です。設定の自動欠席記録が有効な場合、その記録も反映します。残り2回以下で警告します。"

/** 入力欄の検証結果。エラーが無い項目のみ数値が入る。 */
internal data class AttendanceGoalValidation(
    val totalClasses: Int?,
    val totalError: String?,
    val value: Int?,
    val valueError: String?,
) {
    val isValid: Boolean get() = totalClasses != null && value != null
}

/** 総授業数・目標値の入力を検証する。空欄・非数値・範囲外はエラーメッセージを返す。 */
internal fun validateAttendanceGoal(
    totalText: String,
    mode: String,
    valueText: String,
): AttendanceGoalValidation {
    val total = totalText.trim().toIntOrNull()
    val totalError = when {
        totalText.isBlank() -> "総授業数を入力してください"
        total == null -> "総授業数は数字で入力してください"
        total < GOAL_TOTAL_MIN || total > GOAL_TOTAL_MAX ->
            "総授業数は${GOAL_TOTAL_MIN}〜${GOAL_TOTAL_MAX}の範囲で入力してください"
        else -> null
    }

    val isPercent = mode != ATTENDANCE_GOAL_MODE_COUNT
    val value = valueText.trim().toIntOrNull()
    val valueError = when {
        valueText.isBlank() ->
            if (isPercent) "目標の出席率を入力してください" else "目標の出席回数を入力してください"
        value == null -> "目標は数字で入力してください"
        isPercent && (value < GOAL_PERCENT_MIN || value > GOAL_PERCENT_MAX) ->
            "目標の出席率は${GOAL_PERCENT_MIN}〜${GOAL_PERCENT_MAX}の範囲で入力してください"
        !isPercent && value < 1 -> "目標の出席回数は1以上で入力してください"
        !isPercent && total != null && totalError == null && value > total ->
            "目標の出席回数は総授業数（${total}回）以下で入力してください"
        else -> null
    }

    return AttendanceGoalValidation(
        totalClasses = total.takeIf { totalError == null },
        totalError = totalError,
        value = value.takeIf { valueError == null },
        valueError = valueError,
    )
}

/**
 * 出席目標（総授業数・目標出席率または目標出席回数）の編集 UI。
 *
 * スクロールは呼び出し側が用意する。保存時は (総授業数, "PERCENT"/"COUNT", 目標値) を返す。
 * [resultLabel] には現在の達成状況などの説明文を渡す（空なら非表示）。
 */
@Composable
fun AttendanceGoalEditor(
    totalClasses: Int?,
    mode: String?,
    value: Int?,
    resultLabel: String,
    onSave: (Int, String, Int) -> Unit,
    onRemove: () -> Unit,
) {
    val initialMode =
        if (mode == ATTENDANCE_GOAL_MODE_COUNT) ATTENDANCE_GOAL_MODE_COUNT else ATTENDANCE_GOAL_MODE_PERCENT
    var selectedMode by remember(mode) { mutableStateOf(initialMode) }
    var totalText by remember(totalClasses) {
        mutableStateOf((totalClasses ?: GOAL_TOTAL_DEFAULT).toString())
    }
    var valueText by remember(value) {
        mutableStateOf((value ?: GOAL_VALUE_DEFAULT).toString())
    }

    val validation = validateAttendanceGoal(totalText, selectedMode, valueText)
    val isPercent = selectedMode == ATTENDANCE_GOAL_MODE_PERCENT

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (resultLabel.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("現在の状況", style = MaterialTheme.typography.labelLarge)
                    Text(resultLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        OutlinedTextField(
            value = totalText,
            onValueChange = { totalText = it.filter(Char::isDigit).take(3) },
            label = { Text("総授業数（回）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = validation.totalError != null,
            supportingText = validation.totalError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("目標の指定方法", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = isPercent,
                onClick = { selectedMode = ATTENDANCE_GOAL_MODE_PERCENT },
                label = { Text("出席率（%）") },
            )
            FilterChip(
                selected = !isPercent,
                onClick = { selectedMode = ATTENDANCE_GOAL_MODE_COUNT },
                label = { Text("出席回数（回）") },
            )
        }

        OutlinedTextField(
            value = valueText,
            onValueChange = { valueText = it.filter(Char::isDigit).take(3) },
            label = { Text(if (isPercent) "目標の出席率" else "目標の出席回数") },
            suffix = { Text(if (isPercent) "%" else "回") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = validation.valueError != null,
            supportingText = validation.valueError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            GOAL_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val total = validation.totalClasses ?: return@Button
                    val target = validation.value ?: return@Button
                    onSave(total, selectedMode, target)
                },
                enabled = validation.isValid,
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
            OutlinedButton(
                onClick = onRemove,
                enabled = value != null,
                modifier = Modifier.weight(1f),
            ) { Text("解除") }
        }
    }
}
