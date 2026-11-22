package org.cabetus.ui.gp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cabetus.domain.GpCalculator
import org.cabetus.domain.GpCalculator.Grade
import java.math.BigDecimal

private val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
private const val DEFAULT_TARGET_GP = 2
private const val DEFAULT_MAX = "100"
private val RAIL_WIDTH = 68.dp
private val RAIL_MIN_CONTENT_WIDTH = 320.dp
private val RAIL_MIN_HEIGHT = 240.dp
private val TARGET_GPS = listOf(4, 3, 2, 1)
private const val NUMERIC_INPUT_LIMIT = 16
private val DECIMAL_INPUT = Regex("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
private val COUNT_INPUT = Regex("-?[0-9]+")

private enum class GpMode { PREDICT, REQUIRE }

/**
 * 画面で編集中の1項目。数値は入力途中の文字列のまま保持し、計算時にだけ解釈する。
 * 数値の不正を画面の入力欄で勝手に消さないため、0%の項目も含めて文字列で持つ。
 */
private data class GpItemInput(
    val id: Long,
    val name: String,
    val weight: String,
    val score: String,
    val max: String,
    val isAttendance: Boolean = false,
    val total: String = "",
    val completed: String = "0",
    val present: String = "0",
    val expected: String = "",
)

private data class TargetOption(
    val id: Long,
    val name: String,
    val weight: BigDecimal,
)

private fun initialGpItems(): List<GpItemInput> = listOf(
    GpItemInput(
        id = 1L,
        name = "出席",
        weight = "",
        score = "",
        max = "",
        isAttendance = true,
        total = "",
        completed = "0",
        present = "0",
        expected = "",
    ),
    GpItemInput(id = 2L, name = "中間", weight = "", score = "", max = DEFAULT_MAX),
    GpItemInput(id = 3L, name = "期末", weight = "", score = "", max = DEFAULT_MAX),
    GpItemInput(id = 4L, name = "課題", weight = "", score = "", max = DEFAULT_MAX),
)

/** 入力値を保存し、画面回転やプロセス再生成後も復元する。 */
private val GpItemListSaver: Saver<List<GpItemInput>, Any> = listSaver<List<GpItemInput>, Any>(
    save = { rows ->
        rows.map { row ->
            arrayListOf<Any>(
                row.id,
                row.name,
                row.weight,
                row.score,
                row.max,
                row.isAttendance,
                row.total,
                row.completed,
                row.present,
                row.expected,
            )
        }
    },
    restore = { saved ->
        val restored = saved.mapNotNull { entry ->
            val values = entry as? List<*> ?: return@mapNotNull null
            val id = (values.getOrNull(0) as? Number)?.toLong() ?: return@mapNotNull null
            GpItemInput(
                id = id,
                name = values.getOrNull(1) as? String ?: "",
                weight = values.getOrNull(2) as? String ?: "",
                score = values.getOrNull(3) as? String ?: "",
                max = values.getOrNull(4) as? String ?: DEFAULT_MAX,
                isAttendance = values.getOrNull(5) as? Boolean ?: false,
                total = values.getOrNull(6) as? String ?: "",
                completed = values.getOrNull(7) as? String ?: "0",
                present = values.getOrNull(8) as? String ?: "0",
                expected = values.getOrNull(9) as? String ?: "",
            )
        }
        restored.takeIf { it.isNotEmpty() }
    },
)

private fun GpItemInput.displayName(): String =
    name.trim().ifBlank { if (isAttendance) "出席" else "名称未設定の項目" }

private fun parseWeight(text: String): BigDecimal? {
    val trimmed = text.trim()
    val value = if (trimmed.isEmpty()) BigDecimal.ZERO else parseDecimal(trimmed) ?: return null
    return value.takeIf { it.signum() >= 0 && it <= HUNDRED }
}

private fun formatDecimal(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()

/**
 * 他の項目の比重から、この項目を合計100%ちょうどにする比重を求める。
 *
 * 空欄の比重は0%として扱う。他の比重が不正、他の合計が100%以上、
 * 提案が入力上限を超える、または現在値と同じ数値なら提案しない。
 * 現在値が不正でも提案を返し、明示的な操作で置き換えられるようにする。
 */
internal fun suggestRemainingWeight(
    currentWeight: String,
    otherWeights: Iterable<String>,
): String? {
    var othersSum = BigDecimal.ZERO
    for (other in otherWeights) {
        val weight = parseWeight(other) ?: return null
        othersSum = othersSum.add(weight)
        if (othersSum >= HUNDRED) return null
    }

    val suggestion = HUNDRED.subtract(othersSum)
    val formatted = formatDecimal(suggestion)
    if (formatted.length > NUMERIC_INPUT_LIMIT) return null

    val current = parseWeight(currentWeight)
    if (current != null && current.compareTo(suggestion) == 0) return null
    return formatted
}

private fun gradeForGp(gp: Int): Grade = when (gp) {
    4 -> Grade.S
    3 -> Grade.A
    2 -> Grade.B
    else -> Grade.C
}

private fun parseDecimal(text: String): BigDecimal? =
    text.takeIf(DECIMAL_INPUT::matches)?.toBigDecimalOrNull()

// 全角数字は受け付けるが、符号や小数点などを削って別の値に変えない。
private fun normalizeNumber(input: String): String = buildString(input.length) {
    for (char in input) {
        append(when (char) {
            in '０'..'９' -> '0' + (char - '０')
            '．' -> '.'
            '－', '−' -> '-'
            else -> char
        })
    }
}

private fun parseCountField(
    text: String,
    label: String,
    field: String,
    issues: MutableList<String>,
    min: Int,
    max: Int,
): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
        issues += "「$label」の${field}を入力してください。"
        return null
    }
    val value = trimmed.takeIf(COUNT_INPUT::matches)?.toIntOrNull()
    if (value == null) {
        issues += "「$label」の${field}は整数で入力してください。"
        return null
    }
    if (value < min || value > max) {
        issues += "「$label」の${field}は${min}〜${max}回の範囲で入力してください。"
        return null
    }
    return value
}

private fun parseDecimalField(
    text: String,
    label: String,
    field: String,
    issues: MutableList<String>,
    mustBePositive: Boolean,
): BigDecimal? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
        issues += "「$label」の${field}を入力してください。"
        return null
    }
    val value = parseDecimal(trimmed)
    if (value == null) {
        issues += "「$label」の${field}は数値で入力してください。"
        return null
    }
    if (mustBePositive && value.signum() <= 0) {
        issues += "「$label」の${field}は0より大きい数値で入力してください。"
        return null
    }
    if (!mustBePositive && value.signum() < 0) {
        issues += "「$label」の${field}は0以上で入力してください。"
        return null
    }
    return value
}

private fun buildAttendance(
    row: GpItemInput,
    label: String,
    isReverseTarget: Boolean,
    issues: MutableList<String>,
): GpCalculator.Attendance? {
    val total = parseCountField(
        row.total,
        label,
        "授業総回数（公休を除く）",
        issues,
        min = 1,
        max = 200,
    )
    val completed = parseCountField(
        row.completed,
        label,
        "終了済み回数",
        issues,
        min = 0,
        max = 200,
    )
    val present = parseCountField(
        row.present,
        label,
        "出席済み回数",
        issues,
        min = 0,
        max = 200,
    )
    if (total == null || completed == null || present == null) return null

    if (completed > total) {
        issues += "「$label」の終了済み回数は授業総回数（${total}回）以下で入力してください。"
        return null
    }
    if (present > completed) {
        issues += "「$label」の出席済み回数は終了済み回数（${completed}回）以下で入力してください。"
        return null
    }

    val remaining = total - completed
    val expected = when {
        isReverseTarget -> null
        row.expected.isBlank() && remaining == 0 -> 0
        row.expected.isBlank() -> {
            issues += "「$label」の今後出席見込みを入力してください（残り${remaining}回）。"
            null
        }

        else -> parseCountField(
            row.expected,
            label,
            "今後出席見込み",
            issues,
            min = 0,
            max = remaining,
        )
    }

    if (!isReverseTarget && expected == null) return null
    return GpCalculator.Attendance(
        total = total,
        completed = completed,
        present = present,
        expectedAdditional = expected,
    )
}

private data class BuildOutcome(
    val items: List<GpCalculator.Item>,
    val issues: List<String>,
    val weightSum: BigDecimal?,
)

/**
 * 画面入力を [GpCalculator] のモデルへ変換する。
 *
 * 比重が空欄なら0%として扱い、0%の項目の得点・出席入力は一切参照しない。
 * 出席行は正の比重のとき必ず出席モデルとして組み立て、組み立てられない場合は
 * 通常項目へ読み替えず計算を止める。
 */
private fun buildOutcome(
    rows: List<GpItemInput>,
    mode: GpMode,
    targetId: Long,
): BuildOutcome {
    val issues = mutableListOf<String>()
    val items = mutableListOf<GpCalculator.Item>()
    var weightSum = BigDecimal.ZERO
    var weightsComplete = true

    rows.forEach { row ->
        val label = row.displayName()
        val weight = parseWeight(row.weight)
        if (weight == null) {
            issues += "「$label」の比重は0〜100の数値で入力してください。"
            weightsComplete = false
            // 行と項目の対応が崩れないよう、計算に使われないプレースホルダーを入れる。
            items += GpCalculator.Item(BigDecimal.ZERO, null, null, null)
            return@forEach
        }
        weightSum = weightSum.add(weight)

        val isReverseTarget = mode == GpMode.REQUIRE && row.id == targetId

        if (row.isAttendance) {
            if (weight.signum() == 0) {
                // 0%なので出席の入力は参照しない。通常項目としても扱わない。
                items += GpCalculator.Item(weight, null, null, null)
                return@forEach
            }
            val attendance = buildAttendance(row, label, isReverseTarget, issues)
            if (attendance != null) {
                items += GpCalculator.Item(weight, null, null, attendance)
            } else {
                // 出席モデルを作れない行は通常項目として流さない。対応だけ保つ。
                items += GpCalculator.Item(weight, null, null, null)
            }
            return@forEach
        }

        if (weight.signum() == 0) {
            items += GpCalculator.Item(weight, null, null, null)
            return@forEach
        }

        val score = if (isReverseTarget) {
            // 逆算対象の得点は GpCalculator.required が参照しない。
            null
        } else {
            parseDecimalField(row.score, label, "得点", issues, mustBePositive = false)
        }
        val max = parseDecimalField(row.max, label, "満点", issues, mustBePositive = true)
        if (score != null && max != null && score > max) {
            issues += "「$label」の得点は満点（${formatDecimal(max)}点）以下で入力してください。"
        }
        items += GpCalculator.Item(weight, score, max, null)
    }

    if (weightsComplete) {
        if (weightSum.compareTo(HUNDRED) != 0) {
            issues += "比重の合計が100%になっていません（現在 ${formatDecimal(weightSum)}%）。" +
                "空欄の比重は0%として扱います。"
        }
    } else {
        issues += "比重の合計を計算できません。すべての比重を確認してください。"
    }

    if (mode == GpMode.REQUIRE) {
        val hasPositive = rows.any { row ->
            val weight = parseWeight(row.weight)
            weight != null && weight.signum() > 0
        }
        if (!hasPositive) {
            issues += "逆算するには、比重が0%より大きい項目が1つ必要です。"
        }
    }

    return BuildOutcome(
        items = items,
        issues = issues.distinct(),
        weightSum = weightSum.takeIf { weightsComplete },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpCalculatorScreen(
    onBack: () -> Unit,
    attendanceImport: @Composable (onApply: (present: Int, completed: Int) -> Unit) -> Unit = {},
) {
    var rows by rememberSaveable(stateSaver = GpItemListSaver) {
        mutableStateOf(initialGpItems())
    }
    var nextId by rememberSaveable { mutableStateOf(5L) }
    var modeName by rememberSaveable { mutableStateOf(GpMode.PREDICT.name) }
    var targetGp by rememberSaveable { mutableStateOf(DEFAULT_TARGET_GP) }
    var targetId by rememberSaveable { mutableStateOf(-1L) }
    var editingId by rememberSaveable { mutableStateOf(-1L) }

    val mode = GpMode.entries.firstOrNull { it.name == modeName } ?: GpMode.PREDICT
    val positiveTargets = rows.filter { row ->
        parseWeight(row.weight)?.signum() == 1
    }
    val effectiveTargetId = targetId
        .takeIf { id -> positiveTargets.any { it.id == id } }
        ?: positiveTargets.firstOrNull()?.id
        ?: -1L
    LaunchedEffect(mode, effectiveTargetId) {
        if (mode == GpMode.REQUIRE) targetId = effectiveTargetId
    }
    val targetRow = rows.firstOrNull { it.id == effectiveTargetId }
    val targetOptions = positiveTargets.map { row ->
        TargetOption(
            id = row.id,
            name = row.displayName(),
            weight = parseWeight(row.weight) ?: BigDecimal.ZERO,
        )
    }
    val goalGrade = gradeForGp(targetGp)
    val targetIndex = rows.indexOfFirst { it.id == effectiveTargetId }
    val contentScroll = rememberScrollState()
    val scrollScope = rememberCoroutineScope()

    val outcome = remember(rows, mode, effectiveTargetId) {
        buildOutcome(rows, mode, effectiveTargetId)
    }
    val prediction = if (outcome.issues.isEmpty() && mode == GpMode.PREDICT) {
        GpCalculator.predict(outcome.items)
    } else {
        null
    }
    val requirement = if (
        outcome.issues.isEmpty() &&
        mode == GpMode.REQUIRE &&
        targetIndex >= 0
    ) {
        GpCalculator.required(outcome.items, targetIndex, goalGrade)
    } else {
        null
    }
    val calculationIssue = when {
        outcome.issues.isNotEmpty() -> null
        mode == GpMode.PREDICT && prediction == null ->
            "予想GPを計算できませんでした。入力内容を確認してください。"

        mode == GpMode.REQUIRE && targetIndex < 0 ->
            "逆算する項目を選択してください。"

        mode == GpMode.REQUIRE && requirement == null ->
            "必要値を計算できませんでした。入力内容を確認してください。"

        else -> null
    }
    val issues = outcome.issues + listOfNotNull(calculationIssue)
    val targetMax = targetRow
        ?.takeUnless { it.isAttendance }
        ?.max
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.toBigDecimalOrNull()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("GP電卓") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                )
                WeightSummaryBar(weightSum = outcome.weightSum)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            ModeSelector(
                mode = mode,
                onModeChange = { next ->
                    // 明示的に逆算へ切り替えたときだけ先頭へ戻す（入力変更では動かさない）。
                    if (next == GpMode.REQUIRE && mode != GpMode.REQUIRE) {
                        scrollScope.launch { contentScroll.scrollTo(0) }
                    }
                    modeName = next.name
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
            ) {
                val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                // 本文に最低限の幅と高さを残せるときだけ、右側の評価レールを出す。
                val showRail = maxWidth >= RAIL_MIN_CONTENT_WIDTH + RAIL_WIDTH + 12.dp &&
                    maxHeight >= RAIL_MIN_HEIGHT * fontScale
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(contentScroll),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (mode == GpMode.REQUIRE) {
                            RequirementSettings(
                                targetOptions = targetOptions,
                                selectedId = effectiveTargetId,
                                targetGp = targetGp,
                                onSelectTarget = { targetId = it },
                                onSelectGp = { targetGp = it },
                            )
                        }

                        ResultCard(
                            mode = mode,
                            prediction = prediction,
                            requirement = requirement,
                            targetGp = targetGp,
                            targetMax = targetMax,
                        )

                        if (issues.isNotEmpty()) {
                            IssuesCard(issues)
                        }

                        ItemList(
                            rows = rows,
                            mode = mode,
                            targetId = effectiveTargetId,
                            onEdit = { editingId = it },
                            onAdd = {
                                val id = nextId
                                nextId = nextId + 1L
                                rows = rows + GpItemInput(
                                    id = id,
                                    name = "追加項目",
                                    weight = "",
                                    score = "",
                                    max = DEFAULT_MAX,
                                )
                                editingId = id
                            },
                        )

                        NotesCard()
                    }

                    if (showRail) {
                        Column(
                            modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                prediction?.let { "${formatDecimal(it.score)}点 · GP ${it.grade.gp}" } ?: "評価目盛り",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            GradeRail(
                                prediction = prediction,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    val editingRow = rows.firstOrNull { it.id == editingId }
    if (editingRow != null) {
        val weightSuggestion = suggestRemainingWeight(
            currentWeight = editingRow.weight,
            otherWeights = rows.asSequence()
                .filter { it.id != editingRow.id }
                .map { it.weight }
                .asIterable(),
        )
        ItemEditorDialog(
            item = editingRow,
            isTarget = mode == GpMode.REQUIRE && editingRow.id == effectiveTargetId,
            mode = mode,
            weightSuggestion = weightSuggestion,
            onChange = { updated ->
                rows = rows.map { if (it.id == updated.id) updated else it }
            },
            onDelete = if (editingRow.isAttendance) {
                null
            } else {
                {
                    rows = rows.filterNot { it.id == editingRow.id }
                    if (targetId == editingRow.id) targetId = -1L
                    editingId = -1L
                }
            },
            onDismiss = { editingId = -1L },
            attendanceImport = attendanceImport,
        )
    }
}

@Composable
private fun WeightSummaryBar(weightSum: BigDecimal?) {
    val complete = weightSum?.compareTo(HUNDRED) == 0
    val delta = weightSum?.takeUnless { complete }?.let { HUNDRED.subtract(it) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (complete) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (complete) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("比重", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (weightSum == null) {
                        "入力エラー / 100%"
                    } else {
                        "${formatDecimal(weightSum)}% / 100%"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (delta != null) {
                Text(
                    if (delta.signum() > 0) {
                        "あと${formatDecimal(delta)}%"
                    } else {
                        "${formatDecimal(delta.abs())}%超過"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** 0〜100 の得点をレール上の高さへ変換する。成績帯は等間隔、S の上は余白にする。 */
private fun gradeRailFraction(score: BigDecimal?): Float? {
    if (score == null) return null
    val value = score.toFloat().coerceIn(0f, 100f)
    val thresholds = Grade.entries.asReversed().map { it.minimum.toFloat() }
    for (index in thresholds.indices.reversed()) {
        val low = thresholds[index]
        if (value >= low) {
            val high = if (index == thresholds.lastIndex) 100f else thresholds[index + 1]
            val within = if (high > low) ((value - low) / (high - low)).coerceIn(0f, 1f) else 1f
            return (index + within) / thresholds.size
        }
    }
    return 0f
}

@Composable
private fun GradeRail(
    prediction: GpCalculator.Prediction?,
    modifier: Modifier = Modifier,
) {
    val fraction = gradeRailFraction(prediction?.score)
    val description = if (prediction == null) {
        "評価スケール。予想結果はまだありません。"
    } else {
        "評価スケール。予想評価 ${prediction.grade.name}、" +
            "予想得点 ${prediction.score.toPlainString()}点。"
    }
    BoxWithConstraints(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val minimumHeight = with(LocalDensity.current) {
            MaterialTheme.typography.labelMedium.lineHeight.toDp() * 6
        }
        if (maxHeight < minimumHeight) {
            Text(
                prediction?.grade?.name ?: "S–D",
                style = MaterialTheme.typography.titleMedium,
                color = if (prediction == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        } else {
            val inset = maxHeight / 12f
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(24.dp).fillMaxHeight().padding(vertical = inset)
                        .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (fraction != null && fraction > 0f) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fraction)
                                .background(MaterialTheme.colorScheme.error),
                        )
                    }
                }
                Column(Modifier.fillMaxHeight().padding(start = 5.dp)) {
                    // 目盛りは各行の中央。先頭の空行はSの上（100点）を確保する。
                    Spacer(Modifier.weight(1f))
                    Grade.entries.forEach { grade ->
                        val current = prediction?.grade == grade
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.width(8.dp), color = MaterialTheme.colorScheme.outline)
                            Text(
                                grade.name,
                                modifier = Modifier.padding(start = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = if (current) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeSelector(
    mode: GpMode,
    onModeChange: (GpMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = mode == GpMode.PREDICT,
            onClick = { onModeChange(GpMode.PREDICT) },
            label = { Text("予想GP") },
        )
        FilterChip(
            selected = mode == GpMode.REQUIRE,
            onClick = { onModeChange(GpMode.REQUIRE) },
            label = { Text("目標から逆算") },
        )
    }
}

/**
 * 逆算の条件。項目と目標GPを、横幅と文字サイズに合わせて2列または縦1列に並べる。
 * 横スクロールは使わない。
 */
@Composable
private fun RequirementSettings(
    targetOptions: List<TargetOption>,
    selectedId: Long,
    targetGp: Int,
    onSelectTarget: (Long) -> Unit,
    onSelectGp: (Int) -> Unit,
) {
    val selectedTarget = targetOptions.firstOrNull { it.id == selectedId }
        ?: targetOptions.firstOrNull()

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val targetSelector: @Composable (Modifier) -> Unit = { modifier ->
                    SelectorField(
                        label = "逆算する項目",
                        value = selectedTarget?.name ?: "比重を入力",
                        enabled = selectedTarget != null,
                        modifier = modifier,
                    ) { dismiss ->
                        targetOptions.forEach { option ->
                            key(option.id) {
                                val selected = option.id == selectedTarget?.id
                                DropdownMenuItem(
                                    modifier = Modifier.semantics { this.selected = selected },
                                    trailingIcon = if (selected) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else null,
                                    text = {
                                        Text(
                                            "${option.name}（${formatDecimal(option.weight)}%）",
                                        )
                                    },
                                    onClick = {
                                        onSelectTarget(option.id)
                                        dismiss()
                                    },
                                )
                            }
                        }
                    }
                }
                val gpSelector: @Composable (Modifier) -> Unit = { modifier ->
                    SelectorField(
                        label = "目標GP",
                        value = "GP$targetGp（${gradeForGp(targetGp).name}）",
                        enabled = true,
                        modifier = modifier,
                    ) { dismiss ->
                        TARGET_GPS.forEach { gp ->
                            key(gp) {
                                val selected = gp == targetGp
                                DropdownMenuItem(
                                    modifier = Modifier.semantics { this.selected = selected },
                                    trailingIcon = if (selected) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else null,
                                    text = { Text("GP$gp（${gradeForGp(gp).name}）") },
                                    onClick = {
                                        onSelectGp(gp)
                                        dismiss()
                                    },
                                )
                            }
                        }
                    }
                }

                if (maxWidth >= 276.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targetSelector(Modifier.weight(1f))
                        gpSelector(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        targetSelector(Modifier.fillMaxWidth())
                        gpSelector(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** ラベル・現在値・展開アイコンを持つ、横スクロールなしの選択欄。 */
@Composable
private fun SelectorField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "$labelを選ぶ") {
                        expanded = true
                    },
                shape = MaterialTheme.shapes.small,
                color = if (enabled) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                contentColor = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        value,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (enabled) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                menu { expanded = false }
            }
        }
    }
}

private data class ResultColors(val container: Color, val content: Color)

@Composable
private fun resultColors(grade: Grade?, impossible: Boolean): ResultColors {
    val scheme = MaterialTheme.colorScheme
    if (impossible) return ResultColors(scheme.errorContainer, scheme.onErrorContainer)
    return when (grade) {
        Grade.S -> ResultColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        Grade.A -> ResultColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        Grade.B -> ResultColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        Grade.C -> ResultColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
        Grade.D -> ResultColors(scheme.errorContainer, scheme.onErrorContainer)
        null -> ResultColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultCard(
    mode: GpMode,
    prediction: GpCalculator.Prediction?,
    requirement: GpCalculator.Requirement?,
    targetGp: Int,
    targetMax: BigDecimal?,
) {
    val impossible = requirement == GpCalculator.Requirement.Impossible
    val grade = when {
        prediction != null -> prediction.grade
        requirement is GpCalculator.Requirement.Points ||
            requirement is GpCalculator.Requirement.Attend -> gradeForGp(targetGp)

        else -> null
    }
    val colors = resultColors(grade, impossible)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colors.container,
            contentColor = colors.content,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (mode == GpMode.REQUIRE) {
                Text(
                    "逆算結果 · GP$targetGp（${gradeForGp(targetGp).name}）",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            when {
                prediction != null -> {
                    Text(
                        "予想GP ${prediction.grade.gp}.0（評価 ${prediction.grade.name}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "予想得点 ${prediction.score.toPlainString()} / 100",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                mode == GpMode.PREDICT -> {
                    Text(
                        "予想GPは入力完了後に表示します。",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "比重と、出席なら今後出席見込みまで入力してください。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                requirement is GpCalculator.Requirement.Points -> {
                    Text(
                        buildString {
                            append("必要得点 ${formatDecimal(requirement.points)}点")
                            if (targetMax != null) {
                                append(" / ${formatDecimal(targetMax)}点")
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                requirement is GpCalculator.Requirement.Attend -> {
                    Text(
                        buildString {
                            append("必要な追加出席 ")
                            if (requirement.additional == 0) {
                                append("0回（追加不要）")
                            } else {
                                append("${requirement.additional}回")
                            }
                            append(" / 残り${requirement.remaining}回")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                impossible -> {
                    Text(
                        "達成できません",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "必要値が満点または残り回数を超えています。比重や他の項目を見直してください。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                else -> {
                    Text(
                        "逆算の条件を満たすと必要値を表示します。",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "他項目の得点・出席見込みを埋めると、選んだ項目に必要な値が表示されます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun IssuesCard(issues: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "入力の問題を閉じる" else "入力の問題を表示（${issues.size}件）")
            }
            if (expanded) {
                issues.forEach { issue ->
                    Text("・$issue", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ItemList(
    rows: List<GpItemInput>,
    mode: GpMode,
    targetId: Long,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "評価項目",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "比重",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rows.forEach { item ->
                    key(item.id) {
                        HorizontalDivider()
                        ItemListRow(
                            item = item,
                            isTarget = mode == GpMode.REQUIRE && item.id == targetId,
                            onClick = { onEdit(item.id) },
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("項目を追加", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun ItemListRow(
    item: GpItemInput,
    isTarget: Boolean,
    onClick: () -> Unit,
) {
    val weight = parseWeight(item.weight)
    val weightText = weight?.let { "${formatDecimal(it)}%" } ?: "比重エラー"
    val weightColor = if (weight == null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val detail = when {
        isTarget -> "逆算の対象"
        item.isAttendance && item.total.isNotBlank() -> "出席 ${item.present} / ${item.total}回"
        !item.isAttendance && item.score.isNotBlank() -> "得点 ${item.score} / ${item.max}点"
        else -> null
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "編集", role = Role.Button, onClick = onClick),
    ) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val label: @Composable (Modifier) -> Unit = { modifier ->
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(10.dp).clip(RoundedCornerShape(3.dp))
                        .background(
                            if (isTarget) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        ),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(item.displayName(), style = MaterialTheme.typography.titleSmall)
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // 狭い画面や大きな文字では、項目名と比重を縦に積んで切れないようにする。
        if (maxWidth >= 220.dp * fontScale) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                label(Modifier.weight(1f))
                Text(weightText, style = MaterialTheme.typography.titleSmall, color = weightColor)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                label(Modifier.fillMaxWidth())
                Text(weightText, style = MaterialTheme.typography.titleSmall, color = weightColor)
            }
        }
    }
}

@Composable
private fun ItemEditorDialog(
    item: GpItemInput,
    isTarget: Boolean,
    mode: GpMode,
    weightSuggestion: String?,
    onChange: (GpItemInput) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    attendanceImport: @Composable (onApply: (present: Int, completed: Int) -> Unit) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayName(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                ItemEditor(
                    item = item,
                    isTarget = isTarget,
                    mode = mode,
                    weightSuggestion = weightSuggestion,
                    onChange = onChange,
                    onDelete = onDelete,
                    attendanceImport = attendanceImport,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完了") } },
    )
}

@Composable
private fun ItemEditor(
    item: GpItemInput,
    isTarget: Boolean,
    mode: GpMode,
    weightSuggestion: String?,
    onChange: (GpItemInput) -> Unit,
    onDelete: (() -> Unit)?,
    attendanceImport: @Composable (onApply: (present: Int, completed: Int) -> Unit) -> Unit,
) {
    val hideTargetScore = isTarget && mode == GpMode.REQUIRE && !item.isAttendance
    val hideTargetEstimate = isTarget && mode == GpMode.REQUIRE && item.isAttendance

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (item.isAttendance) "出席" else "評価項目",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (isTarget) {
                    Text(
                        "逆算の対象",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "この項目を削除")
                }
            }
        }

        if (!item.isAttendance) {
            OutlinedTextField(
                value = item.name,
                onValueChange = { onChange(item.copy(name = it.take(24))) },
                label = { Text("項目名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = item.weight,
            onValueChange = { if (it.length <= NUMERIC_INPUT_LIMIT) onChange(item.copy(weight = normalizeNumber(it))) },
            label = { Text("比重（空欄は0%）") },
            suffix = { Text("%") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        if (weightSuggestion != null) {
            TextButton(
                onClick = { onChange(item.copy(weight = weightSuggestion)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("残りの比重を入力（${weightSuggestion}%）", textAlign = TextAlign.Center)
            }
        }

        if (item.isAttendance) {
            AttendanceFields(
                item = item,
                hideTargetEstimate = hideTargetEstimate,
                onChange = onChange,
            )
            AttendanceImportSlot(
                item = item,
                onChange = onChange,
                attendanceImport = attendanceImport,
            )
        } else {
            if (hideTargetScore) {
                InfoLine(
                    "逆算対象の得点は使いません。他の項目の得点から必要値を求めます。",
                )
                DecimalField(
                    value = item.max,
                    label = "満点",
                    suffix = "点",
                    onChange = { onChange(item.copy(max = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(
                        value = item.score,
                        label = "得点",
                        suffix = "点",
                        onChange = { onChange(item.copy(score = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    DecimalField(
                        value = item.max,
                        label = "満点",
                        suffix = "点",
                        onChange = { onChange(item.copy(max = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttendanceFields(
    item: GpItemInput,
    hideTargetEstimate: Boolean,
    onChange: (GpItemInput) -> Unit,
) {
    CountField(
        value = item.total,
        label = "授業総回数（公休を除く）",
        onChange = { onChange(item.copy(total = it)) },
        modifier = Modifier.fillMaxWidth(),
    )
    CountField(
        value = item.completed,
        label = "終了済み回数",
        onChange = { onChange(item.copy(completed = it)) },
        modifier = Modifier.fillMaxWidth(),
    )
    CountField(
        value = item.present,
        label = "出席済み回数",
        onChange = { onChange(item.copy(present = it)) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (hideTargetEstimate) {
        InfoLine(
            "逆算対象の出席見込みは使いません。授業総回数・終了済み・出席済みから必要回数を求めます。",
        )
    } else {
        CountField(
            value = item.expected,
            label = "今後出席見込み",
            onChange = { onChange(item.copy(expected = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        val total = item.total.trim().toIntOrNull()
        val completed = item.completed.trim().toIntOrNull()
        if (total != null && completed != null && total in 1..200 && completed in 0..total) {
            val remaining = total - completed
            Text(
                "残り${remaining}回。見込みが空欄でも、残り0回なら0として扱います。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttendanceImportSlot(
    item: GpItemInput,
    onChange: (GpItemInput) -> Unit,
    attendanceImport: @Composable (onApply: (present: Int, completed: Int) -> Unit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Text("保存済みの出席記録", style = MaterialTheme.typography.labelLarge)
        Text(
            "取り込むと、出席済み回数と終了済み回数だけを更新します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attendanceImport { present, completed ->
            val safeCompleted = completed.coerceAtLeast(0)
            val safePresent = present.coerceIn(0, safeCompleted)
            onChange(
                item.copy(
                    completed = safeCompleted.toString(),
                    present = safePresent.toString(),
                ),
            )
        }
    }
}

@Composable
private fun DecimalField(
    value: String,
    label: String,
    suffix: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= NUMERIC_INPUT_LIMIT) onChange(normalizeNumber(it)) },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun CountField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= NUMERIC_INPUT_LIMIT) onChange(normalizeNumber(it)) },
        label = { Text(label) },
        suffix = { Text("回") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun InfoLine(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NotesCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "この試算について",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "注意事項を閉じる" else "注意事項を表示")
                }
            }
            if (expanded) {
                Text(
                    "・科目独自の合格条件（出席回数の下限、課題ごとの必須条件など）は反映しません。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "・遅刻・早退・公欠の扱いは、科目や教員の基準と異なる場合があります。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "・GPの閾値は S=90、A=80、B=70、C=60、D=0 として計算します。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "・総合点の表示は小数第2位まで。必要得点は1点単位で切り上げます（小数の満点を超える場合は満点まで）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "・入力内容に基づく試算です。正式な評価はシラバスや授業の案内を確認してください。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
