package org.cabetus.ui.assignments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.notification.NotificationHelper
import org.cabetus.ui.browser.BrowserIntents
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_HEADER_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)
private val DATE_A11Y_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日（E）", Locale.JAPANESE)

/** 日付セルの最小の高さ。文字サイズ拡大時はこの高さを超えて伸びる。 */
private val DAY_CELL_MIN_HEIGHT = 52.dp

/** 日付セルの角丸。 */
private val DAY_CELL_SHAPE = RoundedCornerShape(10.dp)

/** 件数 pill の上限表示。100 件以上は「99+」。 */
private const val COUNT_LABEL_CAP = 99

/**
 * 課題タブの一覧に差し込む月カレンダー（Dialog ではないインライン Card）。
 *
 * 親の LazyColumn の item に置く前提なので、内部スクロールや高さ指定は持たない。
 * 対象は一覧と同じ絞り込み（[AssignmentsUiState.filteredAssignments]）。
 * 期限なしの課題は日付に載せない。一覧のカードと同じ導線で課題を開く。
 */
@Composable
internal fun AssignmentCalendarPanel(
    state: AssignmentsUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val today = remember { AssignmentCalendar.today() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(today) }

    val byDate = remember(state.filteredAssignments) {
        AssignmentCalendar.groupByDeadlineDate(state.filteredAssignments)
    }
    val counts = remember(byDate) { byDate.mapValues { it.value.size } }
    val cells = remember(month, counts) { AssignmentCalendar.buildMonthCells(month, counts) }
    val selectedItems = selectedDate?.let { byDate[it].orEmpty() }.orEmpty()

    // カレンダー全体は primaryContainer をごく薄く混ぜた面にして、
    // 選択セルの primaryContainer が埋もれないようにする。
    val calendarContainer = lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primaryContainer,
        0.22f,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = calendarContainer),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ヘッダ：アイコン・タイトル・今日へ戻る
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "期限カレンダー",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    month = YearMonth.from(today)
                    selectedDate = today
                }) { Text("今日") }
            }
            // 何を対象にしているかの明示
            Text(
                "対象: ${state.calendarScopeLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 月送り・戻し
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    month = month.minusMonths(1)
                    selectedDate = month.atDay(1)
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "前の月",
                    )
                }
                Text(
                    "${month.year}年${month.monthValue}月",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    month = month.plusMonths(1)
                    selectedDate = month.atDay(1)
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "次の月",
                    )
                }
            }

            // 曜日ヘッダ（高コントラスト）
            Row(Modifier.fillMaxWidth()) {
                AssignmentCalendar.WEEKDAY_LABELS.forEachIndexed { index, label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = when (index) {
                            5 -> MaterialTheme.colorScheme.primary   // 土
                            6 -> MaterialTheme.colorScheme.error     // 日
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 6 週グリッド
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { cell ->
                        CalendarDayCell(
                            cell = cell,
                            isToday = cell.date == today,
                            isSelected = cell.date != null && cell.date == selectedDate,
                            onClick = { cell.date?.let { selectedDate = it } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 選択日の課題リスト
            val date = selectedDate
            if (date == null) {
                Text(
                    "日付を選択してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${DATE_HEADER_FORMAT.format(date)} の期限 (${selectedItems.size}件)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selectedItems.isEmpty()) {
                    Text(
                        "この日の期限はありません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    selectedItems.forEach { a ->
                        CalendarAssignmentRow(
                            a = a,
                            onOpen = {
                                if (a.url.isNotBlank()) {
                                    BrowserIntents.open(context, a.url)
                                }
                            },
                        )
                    }
                }
            }
            // 月内に期限がある課題が 1 件もない場合の案内
            if (state.filteredAssignments.none { it.deadline != null }) {
                Text(
                    "期限が設定された課題はありません（期限なしはカレンダーに表示されません）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    cell: CalendarCell,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = cell.date
    if (date == null) {
        // 月外のセルは高さだけ確保してグリッドの行高さを揃える。
        Box(
            modifier
                .padding(1.dp)
                .heightIn(min = DAY_CELL_MIN_HEIGHT),
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val containerColor = if (isSelected) colors.primaryContainer else colors.surface.copy(alpha = 0f)
    val contentColor = when {
        isSelected -> colors.onPrimaryContainer
        date.dayOfWeek == DayOfWeek.SUNDAY -> colors.error
        date.dayOfWeek == DayOfWeek.SATURDAY -> colors.primary
        else -> colors.onSurface
    }
    val description = dayCellDescription(date, cell.count, isToday, isSelected)

    Box(
        modifier = modifier
            .padding(1.dp)
            .heightIn(min = DAY_CELL_MIN_HEIGHT)
            .clip(DAY_CELL_SHAPE)
            .background(containerColor)
            .then(
                if (isToday) {
                    Modifier.border(1.dp, colors.primary, DAY_CELL_SHAPE)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                selected = isSelected
            }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                ),
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
            )
            if (cell.count > 0) {
                // 件数 pill（可変幅）
                Box(
                    modifier = Modifier
                        .heightIn(min = 16.dp)
                        .background(colors.primary, CircleShape)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = deadlineCountLabel(cell.count),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                        ),
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarAssignmentRow(a: AssignmentEntity, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    AssignmentCalendar.deadlineDateTime(a.deadline)?.format(TIME_FORMAT) ?: "--:--",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    a.courseName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(a.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                NotificationHelper.formatDeadline(a.deadline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 件数 pill のラベル。100 件以上は「99+」に丸める。 */
private fun deadlineCountLabel(count: Int): String =
    if (count > COUNT_LABEL_CAP) "${COUNT_LABEL_CAP}+" else count.toString()

/** 日付セルの読み上げ文言（年月日 + 期限件数 + 今日/選択の説明）。 */
private fun dayCellDescription(
    date: LocalDate,
    count: Int,
    isToday: Boolean,
    isSelected: Boolean,
): String = buildString {
    append(DATE_A11Y_FORMAT.format(date))
    append(if (count > 0) "、期限${count}件" else "、期限なし")
    if (isToday) append("、今日")
    if (isSelected) append("、選択中")
}
