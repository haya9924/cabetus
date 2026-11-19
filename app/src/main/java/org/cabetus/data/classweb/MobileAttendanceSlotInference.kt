package org.cabetus.data.classweb

import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.CapturedDate
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.ConfirmationWindow
import org.cabetus.data.local.TimetableCellEntity
import org.cabetus.domain.PeriodTime
import org.cabetus.domain.PeriodTimes
import java.time.LocalDate
import java.time.LocalTime

/**
 * 画面に曜日時限が表示されていない授業ブロックについて、時間割のコマを
 * **一意に推定できる場合だけ**返す純ロジック。
 *
 * 推定の根拠は次の 3 つだけに限定する。
 * - ページの**年あり**日付から求めた曜日
 *   （年が無い・実在しない日付・画面の曜日表記と矛盾する場合は推定しない）
 * - 正規化した科目名の**完全一致**（空白の畳み込みだけを行う）
 * - 確認済みの出席確認受付時間と [PeriodTimes] の授業時間の重なり
 *   （半開区間で判定し、端が接するだけでは重なりとしない）
 *
 * 次の場合はいずれも採用しない。直近の時限・先頭の候補・件数の多い候補を選ばない。
 * - 日付が不明（年なし・不正な日付・曜日表記の矛盾）
 * - 受付時間が無い・不正（時刻を根拠にできない）
 * - 受付時間が複数の時限に重なる（連続時限・複数時限）
 * - 同じ曜日に同名のコマが複数ある（同名複数）
 * - 同名コマの時限と、受付時間が重なった時限が一致しない（根拠の食い違い）
 */
object MobileAttendanceSlotInference {

    /** 推定結果。 */
    sealed interface Outcome {
        /** コマを一意に推定できた。[basis] は推定根拠の短い説明。 */
        data class Adopted(val cell: TimetableCellEntity, val basis: String) : Outcome

        /** 推定しない（保留）。[reason] は保留した理由。 */
        data class Held(val reason: Reason) : Outcome
    }

    /** 推定を保留した理由。 */
    enum class Reason {
        /** ページ日付の年が無い・実在しない・曜日表記と矛盾するため曜日を確定できない。 */
        DATE_UNKNOWN,

        /** 科目名を一意に確認できない。 */
        NAME_UNKNOWN,

        /** 受付時間を確認できない（出席の推定には時刻の根拠が必要）。 */
        WINDOW_UNKNOWN,

        /** 受付時間が複数の時限に重なる（連続時限など）。 */
        PERIOD_AMBIGUOUS,

        /** 同じ曜日・時限に同名のコマが無い。 */
        NO_CANDIDATE,

        /** 同じ曜日に同名のコマが複数ある。 */
        MULTIPLE_CANDIDATES,

        /** 同名コマの時限と、受付時間が重なった時限が一致しない。 */
        PERIOD_MISMATCH,
    }

    /** 時限番号の上限（[PeriodTimes] に無い時限は候補にしない）。 */
    const val MAX_PERIOD: Int = 20

    /**
     * 明示的な曜日時限が無い 1 授業ブロックから、時間割のコマを一意に推定する。
     *
     * [pageDate] は画面の対象日付、[courseName] は画面の科目名、
     * [window] は画面で確認済みの出席確認受付時間、[cells] は既存の時間割のコマ。
     * 科目名の比較には [MobileAttendanceBinder.normalizeName] と
     * [MobileAttendanceBinder.cellName]（科目コード→科目名の解決）を使う。
     */
    fun infer(
        pageDate: CapturedDate?,
        courseName: String?,
        window: ConfirmationWindow?,
        cells: List<TimetableCellEntity>,
        nameByCode: Map<String, String> = emptyMap(),
    ): Outcome {
        val name = MobileAttendanceBinder.normalizeName(courseName)
        if (name.isEmpty()) return Outcome.Held(Reason.NAME_UNKNOWN)

        val page = pageDate ?: return Outcome.Held(Reason.DATE_UNKNOWN)
        val year = page.year ?: return Outcome.Held(Reason.DATE_UNKNOWN)
        val date = runCatching { LocalDate.of(year, page.month, page.day) }.getOrNull()
            ?: return Outcome.Held(Reason.DATE_UNKNOWN)
        val weekday = date.dayOfWeek.value
        // 画面に曜日表記がある場合は、年あり日付から求めた曜日と一致することを確認する。
        page.weekdayLabel?.let { label ->
            val labelWeekday = weekdayOf(label) ?: return Outcome.Held(Reason.DATE_UNKNOWN)
            if (labelWeekday != weekday) return Outcome.Held(Reason.DATE_UNKNOWN)
        }

        if (window == null || !window.isValid) return Outcome.Held(Reason.WINDOW_UNKNOWN)

        // 受付時間と重なる時限。複数（連続時限など）なら 1 つに決めない。
        val windowPeriods = overlappingPeriods(window)
        if (windowPeriods.size != 1) return Outcome.Held(Reason.PERIOD_AMBIGUOUS)
        val period = windowPeriods.single()
        val periodTime = PeriodTimes.of(period) ?: return Outcome.Held(Reason.PERIOD_AMBIGUOUS)

        // 同じ曜日で、正規化した科目名が完全一致するコマだけを候補にする。
        val named = cells.filter { cell ->
            cell.dayOfWeek == weekday &&
                MobileAttendanceBinder.normalizeName(
                    MobileAttendanceBinder.cellName(cell, nameByCode),
                ) == name
        }
        if (named.isEmpty()) return Outcome.Held(Reason.NO_CANDIDATE)
        // 同名複数（同じ曜日の別時限・別科目コード・重複行）はどれも選ばない。
        val distinctCourses = named.distinctBy { Triple(it.courseCode, it.dayOfWeek, it.period) }
        if (distinctCourses.size != 1) return Outcome.Held(Reason.MULTIPLE_CANDIDATES)
        val cell = distinctCourses.single()
        // 同名コマの時限と、受付時間から求めた時限が食い違う場合は推定しない。
        if (cell.period != period) return Outcome.Held(Reason.PERIOD_MISMATCH)

        val basis = buildString {
            append("推定: 画面に曜日時限が無いため、")
            append("${page.displayLabel()}（${weekdayLabel(weekday)}）の曜日、")
            append("科目名「${MobileAttendanceBinder.cellName(cell, nameByCode)}」の完全一致、")
            append("出席確認時間 ${window.displayLabel()} と ")
            append("${period}限 ${formatTime(periodTime.start)}～${formatTime(periodTime.end)} の重なり")
            append("から特定")
        }
        return Outcome.Adopted(cell = cell, basis = basis)
    }

    /**
     * 受付時間と重なる時限の一覧。[PeriodTimes] に定義された時限だけを対象にし、
     * 半開区間（開始以上・終了未満）で重なりを判定する。端が接するだけは重なりとしない。
     */
    fun overlappingPeriods(window: ConfirmationWindow?): List<Int> {
        if (window == null || !window.isValid) return emptyList()
        return (1..MAX_PERIOD).mapNotNull { period ->
            val time = PeriodTimes.of(period) ?: return@mapNotNull null
            if (overlaps(window, time)) period else null
        }
    }

    private fun overlaps(window: ConfirmationWindow, time: PeriodTime): Boolean =
        window.start < time.end && window.end > time.start

    private fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    private fun weekdayOf(label: String): Int? = when (label) {
        "月" -> 1
        "火" -> 2
        "水" -> 3
        "木" -> 4
        "金" -> 5
        "土" -> 6
        "日" -> 7
        else -> null
    }

    private fun weekdayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "月"
        2 -> "火"
        3 -> "水"
        4 -> "木"
        5 -> "金"
        6 -> "土"
        7 -> "日"
        else -> "?"
    }
}
