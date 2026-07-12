package org.cabetus.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.local.AttendanceOverrideDao
import org.cabetus.data.local.AttendanceOverrideEntity
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.ClassSessionDao
import org.cabetus.data.local.LocalAttendanceDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.domain.AttendanceResolver
import org.cabetus.domain.AttendanceSource
import org.cabetus.domain.AttendanceStatus
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
    /** 手動修正・アプリ記録込みの実効出席率（%）。実施回0なら null。 */
    val effectiveRatePercent: Int?,
    /** 出欠PDF由来の公式出席率（%）。併記用。 */
    val officialRatePercent: Int?,
    /** 実施回（出席・遅刻早退・欠席の合計）。 */
    val heldCount: Int,
    /** 出席回。 */
    val presentCount: Int,
    /** 手動修正が1件以上あるか。 */
    val hasAdjustments: Boolean,
    val academicYear: Int,
)

/** 出席詳細ダイアログの1行（1回分の授業）。 */
data class AttendanceRowUi(
    val date: Long,
    val period: Int,
    val sessionNo: Int?,
    val status: AttendanceStatus,
    val source: AttendanceSource,
    val rawMark: String?,
)

@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val timetableDao: TimetableDao,
    private val classCourseDao: ClassCourseDao,
    private val classSessionDao: ClassSessionDao,
    private val localAttendanceDao: LocalAttendanceDao,
    private val attendanceOverrideDao: AttendanceOverrideDao,
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
        val rows = mergedRows(code)
        val summary = AttendanceResolver.summarize(rows.map { it.status })
        val settings = settingsRepository.current()
        return CourseDetail(
            code = code,
            name = course?.name?.takeIf { it.isNotBlank() } ?: cell?.courseName ?: code,
            instructor = course?.instructor ?: cell?.instructor ?: "",
            room = cell?.room,
            isOnline = cell?.isOnline ?: false,
            credits = course?.credits,
            effectiveRatePercent = summary.ratePercent,
            officialRatePercent = course?.attendanceRate?.toInt(),
            heldCount = summary.held,
            presentCount = summary.present,
            hasAdjustments = rows.any { it.source == AttendanceSource.MANUAL },
            academicYear = settings.academicYear,
        )
    }

    /** 出席詳細ダイアログ用に、1回ごとの出席状況を日付昇順で返す。 */
    suspend fun loadAttendanceRows(code: String): List<AttendanceRowUi> = mergedRows(code)

    /**
     * 手動で出席状況を修正する。status が null なら手動修正を取り消し（PDF/アプリ記録の値に戻る）。
     */
    suspend fun setAttendanceStatus(
        code: String,
        date: Long,
        period: Int,
        status: AttendanceStatus?,
    ) {
        if (status == null) {
            attendanceOverrideDao.delete(code, date, period)
        } else {
            attendanceOverrideDao.upsert(
                AttendanceOverrideEntity(
                    courseCode = code,
                    date = date,
                    period = period,
                    status = status.name,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** class_sessions・local_attendance・overrides を (date, period) でマージする。 */
    private suspend fun mergedRows(code: String): List<AttendanceRowUi> {
        val sessions = classSessionDao.getForCourse(code)
        val locals = localAttendanceDao.getForCourse(code)
        val overrides = attendanceOverrideDao.getForCourse(code)

        val sessionByKey = sessions.associateBy { it.date to it.period }
        val localKeys = locals.map { it.date to it.period }.toSet()
        val overrideByKey = overrides.associateBy { it.date to it.period }

        val allKeys = (sessionByKey.keys + localKeys + overrideByKey.keys)
            .sortedWith(compareBy({ it.first }, { it.second }))

        return allKeys.map { key ->
            val (date, period) = key
            val session = sessionByKey[key]
            val overrideStatus = overrideByKey[key]?.let {
                runCatching { AttendanceStatus.valueOf(it.status) }.getOrNull()
            }
            val resolved = AttendanceResolver.resolve(
                mark = session?.mark,
                hasLocalCheck = key in localKeys,
                overrideStatus = overrideStatus,
            )
            AttendanceRowUi(
                date = date,
                period = period,
                sessionNo = session?.sessionNo,
                status = resolved.status,
                source = resolved.source,
                rawMark = session?.mark,
            )
        }
    }
}
