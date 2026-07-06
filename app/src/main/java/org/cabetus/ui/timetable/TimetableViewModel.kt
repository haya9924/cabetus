package org.cabetus.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.ClassSessionDao
import org.cabetus.data.local.LocalAttendanceDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 時間割グリッドの1コマ表示データ。 */
data class TimetableCellUi(
    val courseCode: String,
    val name: String,
    val room: String?,
    val isOnline: Boolean,
    val isMultiSession: Boolean,
)

data class TimetableUiState(
    val grid: Map<Pair<Int, Int>, List<TimetableCellUi>> = emptyMap(),
    val academicYear: Int = 0,
)

/** セルタップ時の詳細（出席状況込み）。 */
data class CourseDetail(
    val code: String,
    val name: String,
    val instructor: String,
    val room: String?,
    val isOnline: Boolean,
    val credits: Double?,
    val attendanceRate: Double?,
    val heldCount: Int,
    val presentCount: Int,
    val localCount: Int,
    val academicYear: Int,
)

@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val timetableDao: TimetableDao,
    private val classCourseDao: ClassCourseDao,
    private val classSessionDao: ClassSessionDao,
    private val localAttendanceDao: LocalAttendanceDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<TimetableUiState> = combine(
        timetableDao.observeAll(),
        classCourseDao.observeAll(),
        settingsRepository.settings,
    ) { cells, courses, settings ->
        val nameByCode = courses.associate { it.code to it.name }
        val grid = cells.groupBy { it.dayOfWeek to it.period }
            .mapValues { (_, list) ->
                list.map { c ->
                    TimetableCellUi(
                        courseCode = c.courseCode,
                        name = nameByCode[c.courseCode]?.takeIf { it.isNotBlank() && it != c.courseCode }
                            ?: c.courseName.ifBlank { c.courseCode },
                        room = c.room,
                        isOnline = c.isOnline,
                        isMultiSession = c.isMultiSession,
                    )
                }
            }
        TimetableUiState(grid = grid, academicYear = settings.academicYear)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimetableUiState())

    suspend fun loadDetail(code: String): CourseDetail {
        val course = classCourseDao.getByCode(code)
        val cell = timetableDao.getAll().firstOrNull { it.courseCode == code }
        val sessions = classSessionDao.getForCourse(code)
        val held = sessions.count { it.mark != null }
        val present = sessions.count { it.mark == "〇" || it.mark == "○" || it.mark == "◯" }
        val local = localAttendanceDao.countForCourse(code)
        val settings = settingsRepository.current()
        return CourseDetail(
            code = code,
            name = course?.name?.takeIf { it.isNotBlank() } ?: cell?.courseName ?: code,
            instructor = course?.instructor ?: cell?.instructor ?: "",
            room = cell?.room,
            isOnline = cell?.isOnline ?: false,
            credits = course?.credits,
            attendanceRate = course?.attendanceRate,
            heldCount = held,
            presentCount = present,
            localCount = local,
            academicYear = settings.academicYear,
        )
    }
}
