package org.cabetus.ui.gp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.cabetus.data.local.AttendanceOverrideDao
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.LocalAttendanceDao
import org.cabetus.data.local.MobileAttendanceConfirmationDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.domain.AttendanceGoalRecords
import org.cabetus.domain.AttendanceResolver
import org.cabetus.domain.EffectiveClassSessions

@HiltViewModel
class GpAttendanceImportViewModel @Inject constructor(
    sessions: EffectiveClassSessions,
    locals: LocalAttendanceDao,
    overrides: AttendanceOverrideDao,
    confirmations: MobileAttendanceConfirmationDao,
    timetable: TimetableDao,
    courses: ClassCourseDao,
    settings: SettingsRepository,
) : ViewModel() {
    data class Course(val code: String, val name: String)
    data class Choice(val code: String, val name: String, val present: Int, val held: Int, val hasUnknownPast: Boolean)
    data class Snapshot(val year: Int, val choices: List<Choice>)

    private val roster = combine(timetable.observeAll(), courses.observeAll(), settings.settings) { cells, metadata, config ->
        val names = metadata.associate { it.code to it.name }
        val active = cells.distinctBy { it.courseCode }.map { cell ->
            val name = names[cell.courseCode]?.takeIf { it.isNotBlank() && it != cell.courseCode }
                ?: cell.courseName.ifBlank { cell.courseCode }
            Course(cell.courseCode, name)
        }
        config.academicYear to active
    }

    val snapshot = combine(roster, sessions.all, locals.observeAll(), overrides.observeAll(), confirmations.observeAll()) {
            (year, active), allSessions, allLocals, allOverrides, allConfirmations ->
        if (year !in 2000..2100) return@combine Snapshot(year, emptyList())
        val zone = ZoneId.of("Asia/Tokyo")
        val from = LocalDate.of(year, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dates = from until end
        val datedSessions = allSessions.filter { it.date in dates }.groupBy { it.courseCode }
        val datedLocals = allLocals.filter { it.date in dates }.groupBy { it.courseCode }
        val datedOverrides = allOverrides.filter { it.date in dates }.groupBy { it.courseCode }
        val datedConfirmations = allConfirmations.filter { it.date in dates }.groupBy { it.courseCode }
        val now = System.currentTimeMillis()
        Snapshot(year, active.mapNotNull { course ->
            val records = AttendanceGoalRecords.resolve(
                course.code, datedSessions[course.code].orEmpty(), datedLocals[course.code].orEmpty(),
                datedOverrides[course.code].orEmpty(), now, datedConfirmations[course.code].orEmpty(),
            )
            val summary = AttendanceResolver.summarize(records.statuses)
            if (summary.held == 0) null
            else Choice(course.code, course.name, summary.present, summary.held, records.hasUnknownPast)
        }.sortedWith(compareBy({ it.name }, { it.code })))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
}

@Composable
fun GpAttendanceImport(onApply: (present: Int, completed: Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }) {
        Icon(Icons.Filled.FileDownload, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("出席記録から引用")
    }
    if (show) {
        GpAttendanceImportDialog(
            onDismiss = { show = false },
            onApply = { present, held ->
                onApply(present, held)
                show = false
            },
        )
    }
}

@Composable
private fun GpAttendanceImportDialog(
    onDismiss: () -> Unit,
    onApply: (present: Int, completed: Int) -> Unit,
    viewModel: GpAttendanceImportViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val data = snapshot
    var selection by remember(data?.year) { mutableStateOf<String?>(null) }
    val selected = data?.choices?.firstOrNull { it.code == selection }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("出席記録から引用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when {
                    data == null -> "出席記録を読み込み中…"
                    data.year !in 2000..2100 -> "設定年度が未登録です"
                    else -> "${data.year}年度 · 時間割の科目"
                })
                if (data != null && data.choices.isEmpty()) {
                    Text("引用できる確定済みの出席記録がありません。回数は手入力できます。")
                }
                if (data != null && data.choices.isNotEmpty()) {
                    Text("終了済みの確定記録だけを引用します。未記録・公休は含まれません。引用後、実際の回数に合わせて修正してください。")
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(data.choices, key = { it.code }) { course ->
                            FilterChip(
                                selected = selection == course.code,
                                onClick = { selection = course.code },
                                label = {
                                    Text("${course.name}（${course.code}）\n出席 ${course.present}回 ／ 確定 ${course.held}回")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (selected?.hasUnknownPast == true) {
                        Text("この科目には終了済みの未記録回があります。引用後に終了済み回数と出席回数を確認してください。", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let { onApply(it.present, it.held) } }) {
                Text("回数を引用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
