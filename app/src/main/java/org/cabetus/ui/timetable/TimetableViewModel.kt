package org.cabetus.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.local.AttendanceOverrideDao
import org.cabetus.data.local.AttendanceOverrideEntity
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.ClassSessionDao
import org.cabetus.data.local.LocalAttendanceDao
import org.cabetus.data.local.MoodleCourseDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.network.NetworkMonitor
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.domain.AttendanceResolver
import org.cabetus.domain.AttendanceSource
import org.cabetus.domain.AttendanceStatus
import org.cabetus.domain.LetusClassLinkResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 時間割グリッドの1コマ表示データ。 */
data class TimetableCellUi(
    val courseCode: String,
    val name: String,
    val room: String?,
    val isOnline: Boolean,
    val isMultiSession: Boolean,
    val effectiveRatePercent: Int? = null,
    val manualColorArgb: Int? = null,
)

data class TimetableUiState(
    val grid: Map<Pair<Int, Int>, List<TimetableCellUi>> = emptyMap(),
    val academicYear: Int = 0,
    val colorMode: org.cabetus.data.settings.TimetableColorMode = org.cabetus.data.settings.TimetableColorMode.ATTENDANCE_RATE,
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
    /** この授業の LETUS コースページ URL。特定できないときは null（トップへは逃がさない）。 */
    val letusCourseUrl: String? = null,
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
    private val effectiveSessions: org.cabetus.domain.EffectiveClassSessions,
    private val webAttendance: org.cabetus.data.local.ClassWebAttendanceDao,
    private val localAttendanceDao: LocalAttendanceDao,
    private val attendanceOverrideDao: AttendanceOverrideDao,
    private val mobileConfirmations: org.cabetus.data.local.MobileAttendanceConfirmationDao,
    private val settingsRepository: SettingsRepository,
    private val goalMonitor: org.cabetus.domain.AttendanceGoalMonitor,
    private val syncScheduler: org.cabetus.work.ClassAttendanceSyncScheduler,
    private val backgroundTour: org.cabetus.ui.browser.ClassAttendanceBackgroundController,
    private val moodleCourseDao: MoodleCourseDao,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    val mobileConfirmationUpdates = mobileConfirmations.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncWork = syncScheduler.work.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val backgroundStatus = backgroundTour.status
    val scheduleStatus = backgroundTour.scheduleStatus
    val timetableImportStatus = backgroundTour.timetableStatus
    val scheduleCourseMappings = settingsRepository.classAttendanceMappings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    suspend fun autoRefreshScheduleNotices() {
        // Also retry when a busy browser or attendance tour releases the shared slot.
        // The controller applies the same backoff as Home, so this cannot create a refresh loop.
        val backgroundReady = combine(
            networkMonitor.status,
            backgroundTour.browserSession,
            backgroundTour.status,
            backgroundTour.mobileStatus,
            backgroundTour.timetableStatus,
        ) { network, _, attendance, mobile, timetable ->
            network.isKnown && network.isConnected && !attendance.running && !mobile.running &&
                !timetable.running
        }
        // 初回の時間割取り込みが終わるまでは、変更通知の自動読取でブラウザ枠を先取りしない。
        combine(
            backgroundReady,
            backgroundTour.scheduleStatus,
            timetableDao.observeAll(),
        ) { ready, schedule, cells -> ready && !schedule.running && cells.isNotEmpty() }
            .collect { canRefresh ->
                if (canRefresh) backgroundTour.tryStartScheduleNotices()
            }
    }

    fun refreshScheduleNotices(): Boolean = backgroundTour.tryStartScheduleNotices(manual = true)

    suspend fun openForegroundBrowser(launch: () -> Boolean): Boolean = backgroundTour.openForeground(launch)

    fun retryTimetableImport(): Boolean = backgroundTour.tryStartInitialTimetable()

    suspend fun startAttendanceSync() {
        require(settingsRepository.current().academicYear in 2000..2100)
        backgroundTour.start()
    }
    fun cancelAttendanceSync() = backgroundTour.cancel()

    val goalEvaluations = goalMonitor.evaluations
    val lastAttendanceFetch = webAttendance.getLatestFetchedAt().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    suspend fun saveGoal(code: String, total: Int, mode: String, value: Int) {
        val year = settingsRepository.current().academicYear
        settingsRepository.saveAttendanceGoal(year, code, org.cabetus.domain.AttendanceGoal(
            total, org.cabetus.domain.AttendanceGoalMode.valueOf(mode), value,
        ))
    }

    suspend fun removeGoal(code: String) {
        settingsRepository.removeAttendanceGoal(settingsRepository.current().academicYear, code)
    }

    private data class AttendanceData(
        val sessions: List<org.cabetus.data.local.ClassSessionEntity>,
        val locals: List<org.cabetus.data.local.LocalAttendanceEntity>,
        val overrides: List<AttendanceOverrideEntity>, val now: Long,
        val confirmations: List<org.cabetus.data.local.MobileAttendanceConfirmationEntity>,
    )
    private val attendanceData = combine(
        effectiveSessions.all, localAttendanceDao.observeAll(), attendanceOverrideDao.observeAll(),
        kotlinx.coroutines.flow.flow { while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(30_000) } },
        mobileConfirmations.observeAll(),
    ) { sessions, locals, overrides, now, confirmations -> AttendanceData(sessions, locals, overrides, now, confirmations) }

    private val baseState = combine(
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
    }
    val uiState: StateFlow<TimetableUiState> = combine(baseState, attendanceData, settingsRepository.timetableCourseColors, settingsRepository.settings) { base, data, colors, settings ->
        val rates = base.grid.values.flatten().map { it.courseCode }.distinct().associateWith { code ->
            org.cabetus.domain.TimetableAttendanceRateCalculator.calculate(code, base.academicYear,
                data.sessions, data.locals, data.overrides, data.now, data.confirmations)
        }
        base.copy(colorMode = settings.timetableColorMode, grid = base.grid.mapValues { (_, cells) ->
            cells.map { it.copy(effectiveRatePercent = rates[it.courseCode], manualColorArgb = colors["${base.academicYear}:${it.courseCode}"]) }
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimetableUiState())

    suspend fun loadDetail(code: String): CourseDetail {
        val course = classCourseDao.getByCode(code)
        val cell = timetableDao.getAll().firstOrNull { it.courseCode == code }
        val rows = mergedRows(code)
        val summary = AttendanceResolver.summarize(rows.map { it.status })
        val settings = settingsRepository.current()
        val displayName = course?.name?.takeIf { it.isNotBlank() && it != code }
            ?: cell?.courseName?.takeIf { it.isNotBlank() } ?: code
        return CourseDetail(
            code = code,
            name = displayName,
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
            letusCourseUrl = resolveLetusCourse(code, displayName)?.url,
        )
    }

    /**
     * この科目の LETUS コースページを、ホームの実施中授業と同じ規則で特定する。
     *
     * 仮コード（CLASSWEB-）の科目は、出席対応から年度内で値が一致する正式コードが
     * ちょうど 1 件のときだけその正式コードで照合する（複数ならローカルコードのまま）。
     * 明示マッピングはローカルコード（年度込みキー）で引き、リゾルバには解決後の
     * コードの下で渡す。コースを特定できた場合だけ [Result.Course] を返し、
     * 曖昧・不一致などのトップページへのフォールバックは null（ボタンを出さない）。
     */
    suspend fun resolveLetusCourse(code: String, name: String): LetusClassLinkResolver.Result.Course? {
        val year = settingsRepository.current().academicYear
        val officialAlias = settingsRepository.classAttendanceMappings.first()
            .filter { (key, value) -> key.startsWith("$year:") && value == code }
            .keys
            .map { it.removePrefix("$year:") }
            .distinct()
            .singleOrNull()
        val resolvedCode = officialAlias ?: code
        val explicitUrl = settingsRepository.classLetusMappings.first()["$year:$code"]
        val result = LetusClassLinkResolver.resolve(
            courseCode = resolvedCode,
            courseName = name,
            explicitUrlByCourseCode = explicitUrl?.let { mapOf(resolvedCode to it) } ?: emptyMap(),
            moodleCourses = moodleCourseDao.getAll().map {
                LetusClassLinkResolver.MoodleCourseRef(id = it.id, name = it.name, url = it.url)
            },
        )
        return result as? LetusClassLinkResolver.Result.Course
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
        val sessions = effectiveSessions.forCourse(code)
        val locals = localAttendanceDao.getForCourse(code)
        val overrides = attendanceOverrideDao.getForCourse(code)
        val mobileByKey = mobileConfirmations.getForCourse(code).associateBy { it.date to it.period }

        val sessionByKey = sessions.associateBy { it.date to it.period }
        val localKeys = locals.map { it.date to it.period }.toSet()
        val overrideByKey = overrides.associateBy { it.date to it.period }

        val allKeys = (sessionByKey.keys + localKeys + overrideByKey.keys + mobileByKey.keys)
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
                hasMobileConfirmation = key in mobileByKey,
            )
            AttendanceRowUi(
                date = date,
                period = period,
                sessionNo = session?.sessionNo,
                status = resolved.status,
                source = if (sessionByKey[key]?.id == -1L && resolved.source in listOf(AttendanceSource.PDF, AttendanceSource.NONE)) AttendanceSource.CLASS else resolved.source,
                rawMark = session?.mark,
            )
        }
    }
}
