package org.cabetus.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.cabetus.domain.AttendanceGoalCalculator
import org.cabetus.domain.AttendanceGoalMonitor
import org.cabetus.domain.goalLabel
import org.cabetus.domain.HighlightSlot
import org.cabetus.domain.NextClassResolver
import org.cabetus.domain.PeriodTimes
import org.cabetus.ui.browser.BrowserIntents
import org.cabetus.ui.browser.BrowserUrlPolicy
import java.time.LocalDate
import java.time.LocalDateTime

private val DAY_LABELS = listOf("月", "火", "水", "木", "金", "土")

/** 時限欄の幅。 */
private val PERIOD_COLUMN_WIDTH = 30.dp

/** 凡例1行の高さ。 */
private val LEGEND_HEIGHT = 28.dp

/** 曜日ヘッダの高さ。 */
private val HEADER_HEIGHT = 32.dp

/** 凡例とヘッダの合計高さ。残りを7行で等分する。 */
private val LEGEND_AND_HEADER_HEIGHT = LEGEND_HEIGHT + HEADER_HEIGHT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    onOpenSyllabus: (String) -> Unit,
    viewModel: TimetableViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mobileConfirmations by viewModel.mobileConfirmationUpdates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val goals by viewModel.goalEvaluations.collectAsStateWithLifecycle()
    val lastFetch by viewModel.lastAttendanceFetch.collectAsStateWithLifecycle()
    val backgroundStatus by viewModel.backgroundStatus.collectAsStateWithLifecycle()
    val timetableImport by viewModel.timetableImportStatus.collectAsStateWithLifecycle()
    val mappingModel: org.cabetus.ui.browser.ClassAttendanceTourViewModel = hiltViewModel()
    val mappingChoices by mappingModel.mappingChoices.collectAsStateWithLifecycle()
    val mappingMessage by mappingModel.mappingMessage.collectAsStateWithLifecycle()
    var showMappings by remember { mutableStateOf(false) }
    LaunchedEffect(backgroundStatus.result) {
        backgroundStatus.result?.let { mappingModel.acceptBackgroundResult(it) }
    }
    val syncing = backgroundStatus.running
    var syncError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val timetableEmpty = state.grid.isEmpty()
    val openClass: () -> Unit = {
        scope.launch {
            if (!viewModel.openClassBrowser { BrowserIntents.open(context, BrowserUrlPolicy.CLASS_URL) }) {
                syncError = "CLASSを開けませんでした。更新が終わってから再試行してください。"
            }
        }
    }
    var goalError by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<CourseDetail?>(null) }
    var selectedCode by remember { mutableStateOf<String?>(null) }
    var detailReload by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(selectedCode, detailReload, mobileConfirmations) {
        val code = selectedCode
        detail = if (code != null) viewModel.loadDetail(code) else null
    }

    // 30秒ごとに現在時刻を更新し、時限境界をまたいでもハイライトが追従する
    val jst = remember { java.time.ZoneId.of("Asia/Tokyo") }
    val now by produceState(LocalDateTime.now(jst)) {
        while (true) {
            delay(30_000)
            value = LocalDateTime.now(jst)
        }
    }
    val today = now.dayOfWeek.value // 1=Mon..7=Sun
    // 授業中のコマ、なければ次のコマをハイライト対象にする
    val highlight = remember(state.grid, now) {
        NextClassResolver.resolveSlot(state.grid.keys, now)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text("時間割")
                    if (timetableEmpty) {
                        val message = when {
                            timetableImport.running -> "CLASSから時間割を取り込み中…"
                            else -> null
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                    if (backgroundStatus.message.isNotBlank()) {
                        Text(backgroundStatus.message, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                    syncError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    lastFetch?.let { time -> Text("科目の最終保存：" + java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")
                        .withZone(java.time.ZoneId.of("Asia/Tokyo")).format(java.time.Instant.ofEpochMilli(time)),
                        style = MaterialTheme.typography.labelSmall) }
                }
            },
            actions = {
                if (!syncing && mappingChoices.isNotEmpty()) TextButton(onClick = { showMappings = true }) { Text("科目対応") }
                val refreshTimetable = timetableEmpty && !syncing
                IconButton(
                    enabled = !timetableImport.running,
                    onClick = {
                        syncError = null
                        if (refreshTimetable) {
                            if (!viewModel.retryTimetableImport()) syncError = "CLASSの確認が終わってから再試行してください"
                        } else if (syncing) viewModel.cancelAttendanceSync() else scope.launch {
                            try { viewModel.startAttendanceSync() }
                            catch (_: IllegalArgumentException) { syncError = "設定で年度を保存してください" }
                            catch (e: IllegalStateException) { syncError = e.message }
                        }
                    },
                ) {
                    val label = when {
                        refreshTimetable -> "時間割を取得"
                        syncing -> "出欠更新を中止"
                        else -> "出欠の更新"
                    }
                    Icon(Icons.Filled.Refresh, contentDescription = label)
                }
                if (!syncing && backgroundStatus.authRequired) {
                    TextButton(onClick = openClass) { Text("ログイン") }
                }
                IconButton(onClick = openClass) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "CLASSを開く")
                }
            },
        )
    }) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 画面幅から時限欄を引いた残りを6曜日で等分する。スクロールなしで全体を収める。
            val dayWidth = (maxWidth - PERIOD_COLUMN_WIDTH) / 6
            // 凡例・ヘッダを引いた残りを7時限で等分する。
            val rowHeight = (maxHeight - LEGEND_AND_HEADER_HEIGHT) / 7
            Column(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LEGEND_HEIGHT)
                        .padding(horizontal = 8.dp),
                ) {
                    TimetableLegend(state.colorMode)
                }
                TimetableHeader(today = today, dayWidth = dayWidth)
                for (period in 1..7) {
                    TimetableRow(
                        period = period,
                        dayWidth = dayWidth,
                        rowHeight = rowHeight,
                        today = today,
                        state = state,
                        goals = goals,
                        highlight = highlight,
                        onSelect = { code -> selectedCode = code },
                    )
                }
            }
            if (timetableEmpty && timetableImport.failed && !timetableImport.running) {
                val message = if (timetableImport.authRequired) {
                    "CLASSにログインしてから学生時間割表を開いてください。"
                } else {
                    timetableImport.message.ifBlank { "時間割を取り込めませんでした。" }
                }
                androidx.compose.material3.Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = openClass) {
                            Text(if (timetableImport.authRequired) "CLASSにログイン" else "CLASSで時間割を開く")
                        }
                        Text(
                            "CLASSの「学生時間割表」を開き、取り込みプレビューから保存できます。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (showMappings) AlertDialog(
        onDismissRequest = { showMappings = false }, title = { Text("取得科目と時間割の対応") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("保存済み出欠を反映する科目を確認してください。時間割のコマ自体は変更しません。")
            mappingChoices.forEach { choice -> TextButton(onClick = { mappingModel.confirmMapping(choice) }) { Text(choice.label + "：対応を保存") } }
            mappingMessage?.let { Text(it) }
        } }, confirmButton = { TextButton(onClick = { showMappings = false }) { Text("閉じる") } },
    )

    if (selectedCode != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedCode = null },
            sheetState = sheetState,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            val code = selectedCode!!
            val evaluation = goals[code]
            Column(Modifier.padding(horizontal = 24.dp)) {
                if (state.academicYear > 0) {
                    AttendanceGoalEditor(
                        totalClasses = evaluation?.goal?.totalClasses,
                        mode = evaluation?.goal?.mode?.name,
                        value = evaluation?.goal?.value,
                        resultLabel = evaluation?.result.goalLabel(),
                        onSave = { total, mode, value -> scope.launch {
                            try { viewModel.saveGoal(code, total, mode, value); goalError = null }
                            catch (_: Exception) { goalError = "保存できませんでした。設定データをご確認ください。" }
                        } },
                        onRemove = { scope.launch {
                            try { viewModel.removeGoal(code); goalError = null }
                            catch (_: Exception) { goalError = "解除できませんでした。" }
                        } },
                    )
                } else Text("出席目標を設定するには、設定画面で年度を設定してください。")
                goalError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            CourseDetailSheet(
                detail = detail,
                onOpenSyllabus = { code -> selectedCode = null; onOpenSyllabus(code) },
                onOpenLetus = { url -> BrowserIntents.open(context, url) },
                loadRows = viewModel::loadAttendanceRows,
                setStatus = viewModel::setAttendanceStatus,
                onChanged = { detailReload++ },
                confirmationRevision = mobileConfirmations,

            )
            }
        }
    }
}

/** 凡例（今日・欠席が少ない授業）。コンパクトに1行で收める。 */
@Composable
private fun TimetableLegend(mode: org.cabetus.data.settings.TimetableColorMode) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LegendItem(
            color = MaterialTheme.colorScheme.primaryContainer,
            borderColor = MaterialTheme.colorScheme.primary,
            label = "今日",
        )
        LegendItem(
            color = MaterialTheme.colorScheme.errorContainer,
            borderColor = Color.Transparent,
            label = "欠席あと2回以下",
        )
        Text(
            if (mode == org.cabetus.data.settings.TimetableColorMode.ATTENDANCE_RATE) "緑＝記録分の率" else "手動配色",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendItem(color: Color, borderColor: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
                .border(1.dp, borderColor, RoundedCornerShape(3.dp)),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 曜日ヘッダ行。時限欄と同じ幅のダミーを先頭に置いて本文と桁を揃える。高さは固定。 */
@Composable
private fun TimetableHeader(today: Int, dayWidth: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(PERIOD_COLUMN_WIDTH))
        DAY_LABELS.forEachIndexed { idx, label ->
            val isToday = (idx + 1) == today
            Box(
                modifier = Modifier
                    .width(dayWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 1.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TimetableRow(
    period: Int,
    dayWidth: Dp,
    rowHeight: Dp,
    today: Int,
    state: TimetableUiState,
    goals: Map<String, AttendanceGoalMonitor.Evaluation>,
    highlight: HighlightSlot?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
    ) {
        PeriodLabel(period = period, rowHeight = rowHeight)
        for (dayIdx in 1..6) {
            val cells = state.grid[dayIdx to period].orEmpty()
            val isHighlightSlot = highlight?.dayOfWeek == dayIdx && highlight.period == period
            val isOngoing = isHighlightSlot && highlight?.isOngoing == true
            val isNext = isHighlightSlot && highlight?.isOngoing == false
            val risky = cells.any { cellIsRisky(it, goals) }
            TimetableCell(
                cells = cells,
                dayWidth = dayWidth,
                rowHeight = rowHeight,
                colorMode = state.colorMode,
                isToday = dayIdx == today,
                isOngoing = isOngoing,
                isNext = isNext,
                risky = risky,
                goals = goals,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun PeriodLabel(period: Int, rowHeight: Dp) {
    Column(
        modifier = Modifier
            .width(PERIOD_COLUMN_WIDTH)
            .height(rowHeight)
            .padding(horizontal = 1.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$period",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        PeriodTimes.of(period)?.let { time ->
            Text(
                text = "%02d:%02d".format(time.start.hour, time.start.minute),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = "%02d:%02d".format(time.end.hour, time.end.minute),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TimetableCell(
    cells: List<TimetableCellUi>,
    dayWidth: Dp,
    rowHeight: Dp,
    colorMode: org.cabetus.data.settings.TimetableColorMode,
    isToday: Boolean,
    isOngoing: Boolean,
    isNext: Boolean,
    risky: Boolean,
    goals: Map<String, AttendanceGoalMonitor.Evaluation>,
    onSelect: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = timetableCellColors(scheme, colorMode, cells.firstOrNull(), risky)
    val borderColor = when {
        isOngoing -> scheme.primary
        isNext -> scheme.secondary
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .width(dayWidth)
            .height(rowHeight)
            .padding(1.dp)
            .clip(shape)
            .background(container)
            .then(
                if (isOngoing || isNext) Modifier.border(1.5.dp, borderColor, shape) else Modifier,
            )
            .clickable(enabled = cells.isNotEmpty()) {
                cells.firstOrNull()?.let { onSelect(it.courseCode) }
            }
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (cells.isEmpty()) return@Column
            val first = cells.first()
            // 現在/次の短ラベルは通常フローの先頭行として空間を確保する。
            HighlightChip(isOngoing = isOngoing, isNext = isNext, content = content)
            // 入りきる行数は内側の BoxWithConstraints で判定する。
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val available = maxHeight
                val nameLine = 12.dp
                val metaLine = 10.dp
                val room = if (first.isOnline) "オンライン" else first.room.orEmpty()
                val remaining = remainingAbsences(first, goals)
                val metaCount = (if (room.isNotBlank()) 1 else 0) +
                    (if (remaining != null) 1 else 0) +
                    (if (cells.size > 1) 1 else 0)
                val nameMaxLines = ((available - metaLine * metaCount) / nameLine)
                    .toInt()
                    .coerceIn(1, 4)
                var used = nameLine * nameMaxLines
                Column {
                Text(
                    text = first.name,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                    maxLines = nameMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (room.isNotBlank() && available >= used + metaLine) {
                    Text(
                        room,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                        ),
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    used += metaLine
                }
                if (remaining != null && available >= used + metaLine) {
                    Text(
                        if (remaining < 0) "不可" else "欠席あと${remaining}回",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                        ),
                        color = if (risky) scheme.onErrorContainer else scheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    used += metaLine
                }
                if (cells.size > 1 && available >= used + metaLine) {
                    Text(
                        "ほか${cells.size - 1}科目",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                        ),
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
}

/** 現在/次のコマに付ける小さな点と短ラベル。 */
@Composable
private fun HighlightChip(isOngoing: Boolean, isNext: Boolean, content: Color) {
    if (!isOngoing && !isNext) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(content),
        )
        Text(
            text = if (isOngoing) "今" else "次",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
        )
    }
}

private fun remainingAbsences(
    slot: TimetableCellUi,
    goals: Map<String, AttendanceGoalMonitor.Evaluation>,
): Int? {
    val result = goals[slot.courseCode]?.result as? AttendanceGoalCalculator.Result.Computed
        ?: return null
    val remaining = result.remainingAbsences
    return if (remaining <= 2) remaining else null
}

private fun cellIsRisky(
    slot: TimetableCellUi,
    goals: Map<String, AttendanceGoalMonitor.Evaluation>,
): Boolean = remainingAbsences(slot, goals) != null
