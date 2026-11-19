package org.cabetus.ui.assignments

import org.cabetus.data.local.AssignmentEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * 課題タブの月カレンダー用の純粋ロジック。
 *
 * - 期限なし（deadline = null）の課題は日付に載せない。
 * - 期限の epoch ミリ秒は Asia/Tokyo で LocalDate に変換する。
 * - セル数は常に 6 週（42 セル）。月をまたぐ前後の日は date = null の空白セル。
 */
object AssignmentCalendar {

    /** 期限の日付判定に使うタイムゾーン。 */
    val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

    /** グリッドの週数（6 週 = 最大 42 日）。 */
    const val WEEKS: Int = 6

    /** 1 か月分のセル数。 */
    const val CELL_COUNT: Int = WEEKS * 7

    /** 月曜始まりの曜日ヘッダ。 */
    val WEEKDAY_LABELS: List<String> = listOf("月", "火", "水", "木", "金", "土", "日")

    /** 今日（Asia/Tokyo）。テストから差し替えられるよう引数化している。 */
    fun today(zone: ZoneId = ZONE): LocalDate = LocalDate.now(zone)

    /** 今月（Asia/Tokyo）。 */
    fun currentMonth(zone: ZoneId = ZONE): YearMonth = YearMonth.now(zone)

    /** 期限 epoch ミリ秒を Asia/Tokyo の日付へ変換する。null は null。 */
    fun deadlineDate(epochMillis: Long?, zone: ZoneId = ZONE): LocalDate? =
        epochMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /** 期限 epoch ミリ秒を Asia/Tokyo の日時へ変換する（時刻表示用）。null は null。 */
    fun deadlineDateTime(epochMillis: Long?, zone: ZoneId = ZONE): LocalDateTime? =
        epochMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }

    /**
     * 期限がある課題だけを日付ごとにまとめる。期限なしは含めない。
     * 各日の並びは期限の昇順（同時刻は入力順を維持）。
     */
    fun groupByDeadlineDate(
        assignments: List<AssignmentEntity>,
        zone: ZoneId = ZONE,
    ): Map<LocalDate, List<AssignmentEntity>> =
        assignments
            .mapNotNull { a -> deadlineDate(a.deadline, zone)?.let { date -> date to a } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, items) -> items.sortedBy { it.deadline } }

    /** 日付ごとの期限件数。 */
    fun countByDeadlineDate(
        assignments: List<AssignmentEntity>,
        zone: ZoneId = ZONE,
    ): Map<LocalDate, Int> =
        groupByDeadlineDate(assignments, zone).mapValues { it.value.size }

    /**
     * 月グリッドを 6 週（42 セル）で構築する。
     * 月外のセルは date = null / count = 0。
     */
    fun buildMonthCells(
        month: YearMonth,
        counts: Map<LocalDate, Int> = emptyMap(),
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): List<CalendarCell> {
        val first = month.atDay(1)
        val offset = Math.floorMod(
            first.dayOfWeek.value - firstDayOfWeek.value,
            DAYS_IN_WEEK,
        )
        val gridStart = first.minusDays(offset.toLong())
        return (0 until CELL_COUNT).map { index ->
            val date = gridStart.plusDays(index.toLong())
            if (YearMonth.from(date) == month) {
                CalendarCell(date = date, inMonth = true, count = counts[date] ?: 0)
            } else {
                CalendarCell(date = null, inMonth = false, count = 0)
            }
        }
    }

    private const val DAYS_IN_WEEK = 7
}

/** 月カレンダーの 1 セル。date = null は月外の空白セル。 */
data class CalendarCell(
    val date: LocalDate?,
    val inMonth: Boolean,
    val count: Int,
)
