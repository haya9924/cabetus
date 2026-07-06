package org.cabetus.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cabetus.domain.PeriodTimes
import java.time.LocalDate
import java.time.LocalTime

private val DAY_LABELS = listOf("月", "火", "水", "木", "金", "土")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    onOpenSyllabus: (String) -> Unit,
    viewModel: TimetableViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<CourseDetail?>(null) }
    var selectedCode by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(selectedCode) {
        val code = selectedCode
        detail = if (code != null) viewModel.loadDetail(code) else null
    }

    val today = LocalDate.now().dayOfWeek.value // 1=Mon..7=Sun
    val now = LocalTime.now()
    val currentPeriod = (1..7).firstOrNull { p ->
        PeriodTimes.of(p)?.let { now >= it.start && now <= it.end } == true
    }

    Scaffold(topBar = { TopAppBar(title = { Text("時間割") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            // ヘッダ行
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(28.dp)) {}
                DAY_LABELS.forEachIndexed { idx, label ->
                    val isToday = (idx + 1) == today
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // 各時限
            for (period in 1..7) {
                Row(Modifier.fillMaxWidth().height(84.dp)) {
                    // 時限番号 + 時刻
                    Column(
                        modifier = Modifier.width(28.dp).padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("$period", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        PeriodTimes.of(period)?.let {
                            Text("%02d".format(it.start.hour), fontSize = 8.sp)
                            Text(":%02d".format(it.start.minute), fontSize = 8.sp)
                        }
                    }
                    for (dayIdx in 1..6) {
                        val cells = state.grid[dayIdx to period].orEmpty()
                        val isNow = dayIdx == today && period == currentPeriod
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .padding(2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isNow) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .border(
                                    if (isNow) 2.dp else 0.dp,
                                    if (isNow) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable(enabled = cells.isNotEmpty()) {
                                    cells.firstOrNull()?.let { selectedCode = it.courseCode }
                                }
                                .padding(3.dp),
                        ) {
                            Column {
                                cells.take(2).forEach { c ->
                                    Text(
                                        c.name,
                                        fontSize = 9.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = 10.sp,
                                    )
                                    Text(
                                        if (c.isOnline) "オンライン" else (c.room ?: ""),
                                        fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedCode != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedCode = null },
            sheetState = sheetState,
        ) {
            CourseDetailSheet(
                detail = detail,
                onOpenSyllabus = { code -> selectedCode = null; onOpenSyllabus(code) },
            )
        }
    }
}
