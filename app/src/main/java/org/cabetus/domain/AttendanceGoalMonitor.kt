package org.cabetus.domain

import org.cabetus.data.local.*
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.notification.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** UI描画とは独立して、アプリ稼働中の出欠/目標更新と時限終了を監視する。 */
@Singleton
class AttendanceGoalMonitor @Inject constructor(
    private val sessions: EffectiveClassSessions,
    private val locals: LocalAttendanceDao,
    private val overrides: AttendanceOverrideDao,
    private val confirmations: MobileAttendanceConfirmationDao,
    private val courses: ClassCourseDao,
    private val timetable: TimetableDao,
    private val settings: SettingsRepository,
    private val notifications: NotificationHelper,
) {
    data class Evaluation(val goal: AttendanceGoal, val result: AttendanceGoalCalculator.Result, val academicYear: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = flow {
        while (true) { emit(System.currentTimeMillis()); delay(30_000) }
    }
    private data class Inputs(
        val sessions: List<ClassSessionEntity>, val locals: List<LocalAttendanceEntity>,
        val overrides: List<AttendanceOverrideEntity>, val now: Long,
        val confirmations: List<MobileAttendanceConfirmationEntity>,
    )
    private val inputs = combine(sessions.all, locals.observeAll(), overrides.observeAll(), clock, confirmations.observeAll()) { s, l, o, n, m -> Inputs(s,l,o,n,m) }
    private val evaluationFlow = combine(inputs, settings.attendanceGoals, settings.settings) { data, goals, config ->
        goals.mapNotNull { (key, goal) ->
            val prefix = "${config.academicYear}:"
            if (config.academicYear <= 0 || !key.startsWith(prefix)) return@mapNotNull null
            val code = key.removePrefix(prefix)
            val records = AttendanceGoalRecords.resolve(code, data.sessions, data.locals, data.overrides, data.now, data.confirmations)
            // 出欠テーブルに年度列がないため、年度外記録を混ぜた計算は抑止。
            val zone = java.time.ZoneId.of("Asia/Tokyo")
            val start = java.time.LocalDate.of(config.academicYear, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = java.time.LocalDate.of(config.academicYear + 1, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val dates = data.sessions.filter { it.courseCode == code }.map { it.date } +
                data.locals.filter { it.courseCode == code }.map { it.date } + data.overrides.filter { it.courseCode == code }.map { it.date } +
                data.confirmations.filter { it.courseCode == code }.map { it.date }
            val result = if (records.statuses.isEmpty() || dates.any { it < start || it >= end }) {
                AttendanceGoalCalculator.Result.NotComputed(AttendanceGoalCalculator.NotComputedReason.PAST_PENDING_INPUT)
            } else AttendanceGoalCalculator.calculate(goal, records.statuses.map {
                AttendanceRecord(it, records.hasUnknownPast)
            })
            code to Evaluation(goal, result, config.academicYear)
        }.toMap()
    }
    val evaluations: StateFlow<Map<String, Evaluation>> = evaluationFlow.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    suspend fun checkNow() {
        val values = evaluationFlow.first()
        val active = timetable.getAll().associate { it.courseCode to it.courseName }
        val year = settings.current().academicYear
        values.forEach { (code, evaluation) ->
            val result = evaluation.result as? AttendanceGoalCalculator.Result.Computed
            if (code in active && evaluation.academicYear == year && result != null && result.remainingAbsences <= 2) {
                notifications.notifyAttendanceRisk(year, code, active[code] ?: code, result.remainingAbsences)
            }
        }
    }

    init {
        scope.launch {
            combine(evaluations, settings.settings, courses.observeAll(), timetable.observeAll()) { values, config, cs, cells ->
                val active = cells.map { it.courseCode }.toSet()
                Triple(values.filterKeys { it in active }, config.academicYear,
                    cs.associate { it.code to it.name } + cells.associate { it.courseCode to it.courseName })
            }.collect { (values, year, names) ->
                values.forEach { (code, evaluation) ->
                    val result = evaluation.result as? AttendanceGoalCalculator.Result.Computed
                    if (evaluation.academicYear == year && result != null && result.remainingAbsences <= 2) {
                        runCatching { notifications.notifyAttendanceRisk(evaluation.academicYear, code, names[code] ?: code, result.remainingAbsences) }
                    }
                }
            }
        }
    }
}

data class AttendanceRiskRow(
    val code: String,
    val name: String,
    val remainingAbsences: Int,
)

private fun AttendanceRiskRow.remainingLabel(): String =
    if (remainingAbsences < 0) "目標達成不可" else "欠席あと${remainingAbsences}回"

/**
 * ホームに出す「出席目標に注意」の 1 行目。対象が無ければ null（カード自体を出さない）。
 * 科目名は最大 1 件だけ、残りは件数にまとめてホームの縦の占有を増やさない。
 */
fun attendanceRiskSummary(rows: List<AttendanceRiskRow>): String? {
    if (rows.isEmpty()) return null
    val head = rows.first()
    val detail = "${head.name}:記録済み分では${head.remainingLabel()}"
    return if (rows.size > 1) "出席目標に注意 ${rows.size}科目 $detail ほか${rows.size - 1}科目"
    else "出席目標に注意 1科目 $detail"
}

/**
 * 出席目標の危険科目を抽出する純粋ロジック。
 *
 * - 目標の年度が設定年度と一致するものだけ（[academicYear] <= 0 は対象外）
 * - 時間割に存在する（active な）科目コードだけ
 * - [AttendanceGoalCalculator.Result.Computed] かつ [AttendanceGoalCalculator.Result.Computed.isRisk]
 * - 同じ科目コードは 1 件だけ
 * - 残り欠席可能回数の昇順、同数なら科目コード順
 * - 科目名は時間割名を使い、空なら科目コード
 */
fun attendanceRiskRows(
    evaluations: Map<String, AttendanceGoalMonitor.Evaluation>,
    timetable: List<TimetableCellEntity>,
    academicYear: Int,
): List<AttendanceRiskRow> {
    if (academicYear <= 0) return emptyList()
    val active = timetable
        .filter { it.courseCode.isNotBlank() }
        .associate { it.courseCode to it.courseName.trim() }
    return evaluations
        .filter { (code, evaluation) ->
            code in active && evaluation.academicYear == academicYear &&
                (evaluation.result as? AttendanceGoalCalculator.Result.Computed)?.isRisk == true
        }
        .map { (code, evaluation) ->
            AttendanceRiskRow(
                code = code,
                name = active[code]?.takeIf { it.isNotEmpty() } ?: code,
                remainingAbsences = (evaluation.result as AttendanceGoalCalculator.Result.Computed).remainingAbsences,
            )
        }
        .distinctBy { it.code }
        .sortedWith(compareBy({ it.remainingAbsences }, { it.code }))
}

fun AttendanceGoalCalculator.Result?.goalLabel(): String = when (this) {
    null -> "目標未設定"
    is AttendanceGoalCalculator.Result.Computed -> if (remainingAbsences < 0) "記録済み分で目標達成不可（欠席許容回数を超過）" else "記録済み分では欠席可能 あと${remainingAbsences}回（必要出席 ${required}回）"
    is AttendanceGoalCalculator.Result.NotComputed -> when (reason) {
        AttendanceGoalCalculator.NotComputedReason.INVALID_GOAL -> "未計算：目標設定を確認してください"
        AttendanceGoalCalculator.NotComputedReason.RECORD_COUNT_EXCEEDS_TOTAL -> "未計算：記録数が総授業数を超えています"
        AttendanceGoalCalculator.NotComputedReason.PAST_PENDING_INPUT -> "未計算：出欠記録なし・過去の未記録・年度外の記録を確認してください"
    }
}
