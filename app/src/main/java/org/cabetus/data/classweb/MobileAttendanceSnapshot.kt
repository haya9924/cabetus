package org.cabetus.data.classweb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.CapturedDate
import org.cabetus.data.local.TimetableCellEntity
import org.cabetus.data.network.NetworkTransport

/**
 * CLASS モバイル出席登録の画面から読み取った「出席」表示のキャッシュ。
 *
 * 画面の値を**そのまま保存する**のではなく、時間割のコマと確実に紐付けられた分だけを
 * [confirmed] に残す。紐付けできなかった授業は件数だけを保持する（科目名は保存しない）。
 * 読み取った時刻・端末のネットワーク種別・対象日付を併せて保持し、
 * 表示時に古さ（[org.cabetus.domain.MobileAttendanceEvidence]）を判定して失効させる。
 *
 * 保持先はアプリの**プロセス内メモリのみ**（[MobileAttendanceRepository] の
 * [kotlinx.coroutines.flow.MutableStateFlow]）で、DataStore などへは保存しない。
 * 外部送信は行わない。出席の登録・送信は行わない（読み取り結果のキャッシュのみ）。
 */
@Serializable
data class MobileAttendanceSnapshot(
    /** 読み取った時刻（エポックミリ秒）。古さの判定に使う。 */
    val capturedAtEpochMs: Long,
    /** 画面の対象日付の年。画面に年が無ければ null（推測しない）。 */
    val year: Int? = null,
    val month: Int,
    val day: Int,
    /** 画面の日付に付いていた曜日（例: "金"）。無ければ null。 */
    val weekdayLabel: String? = null,
    /** 読み取り時の端末ネットワーク種別（[NetworkTransport.name]）。不明なら null。 */
    val transport: String? = null,
    /** 読み取り時に端末のネットワーク状態を取得できていたか。 */
    val networkKnown: Boolean = false,
    /** CLASS の画面に「学内ネットワークからのアクセス」が可視で表示されていたか。 */
    val campusNetworkLabelVisible: Boolean = false,
    /** 時間割のコマと紐付けられて「出席」を確認できた授業。 */
    val confirmed: List<ConfirmedAttendanceRecord> = emptyList(),
    /**
     * 同じく時間割と紐付けられ、**画面に「出席確認中」が表示されていた**未提出の授業。
     * 「出席」ラベルが無いだけでは未提出とみなさない（推測しない）。
     */
    val pending: List<PendingAttendanceRecord> = emptyList(),
    /** 「出席」表示はあったが、科目名・日付・時限を時間割と紐付けられなかった件数。 */
    val unboundCourseCount: Int = 0,
    /** 表記の重複・欠席表記などで判定できなかった件数。 */
    val ambiguousCourseCount: Int = 0,
    /** 未提出の表示はあったが、時間割と紐付けられなかった件数。 */
    val pendingUnboundCourseCount: Int = 0,
    /** 未提出の表示を、表記の重複などで判定できなかった件数。 */
    val pendingAmbiguousCourseCount: Int = 0,
    /** 画面で確認できた注意事項（画面固有の生テキストは含めない）。 */
    val warnings: List<String> = emptyList(),
) {
    val confirmedCount: Int get() = confirmed.size

    val pendingCount: Int get() = pending.size
}

/** 時間割のコマと紐付いた、1コマ分の出席確認。 */
@Serializable
data class ConfirmedAttendanceRecord(
    /** 既存時間割の科目コード（CLASS の正式コード、または取り込み時の仮コード）。 */
    val courseCode: String,
    val courseName: String,
    /** 1=月 … 7=日。 */
    val dayOfWeek: Int,
    val period: Int,
    val year: Int? = null,
    val month: Int,
    val day: Int,
    /**
     * 画面に曜日時限が無いため、ページ日付の曜日・科目名・受付時間から
     * コマを推定したレコードか。画面に曜日時限が明示されていた場合は false（既定）。
     */
    val inferred: Boolean = false,
    /** 推定の根拠（推定した場合だけ。既存レコードは null）。 */
    val inferenceBasis: String? = null,
)

/**
 * 時間割のコマと紐付いた、未提出（出席確認中）の授業。
 *
 * 受付時間は画面の「出席確認時間：HH:mm～HH:mm」をそのまま分に直して保存する。
 * 日付またぎの受付は想定しない（終了が開始以前の表記は保存しない）。
 */
@Serializable
data class PendingAttendanceRecord(
    /** 既存時間割の科目コード（CLASS の正式コード、または取り込み時の仮コード）。 */
    val courseCode: String,
    val courseName: String,
    /** 1=月 … 7=日。 */
    val dayOfWeek: Int,
    val period: Int,
    val year: Int? = null,
    val month: Int,
    val day: Int,
    /** 出席確認の受付開始（0:00 からの分）。 */
    val windowStartMinutes: Int,
    /** 出席確認の受付終了（0:00 からの分）。 */
    val windowEndMinutes: Int,
    /**
     * 画面に曜日時限が無いため、ページ日付の曜日・科目名・受付時間から
     * コマを推定したレコードか。画面に曜日時限が明示されていた場合は false（既定）。
     */
    val inferred: Boolean = false,
    /** 推定の根拠（推定した場合だけ。既存レコードは null）。 */
    val inferenceBasis: String? = null,
)

/** 保存済みキャッシュ文字列と [MobileAttendanceSnapshot] の相互変換。 */
object MobileAttendanceCodec {

    /** 設定に保存する文字列の上限。異常に大きい値は読まない。 */
    const val MAX_ENCODED_LENGTH: Int = 64 * 1024

    /** 紐付け済みのコマ数の上限。 */
    const val MAX_CONFIRMED: Int = ClassMobileAttendanceDomExtractor.MAX_COURSES

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: MobileAttendanceSnapshot): String =
        json.encodeToString(MobileAttendanceSnapshot.serializer(), snapshot)

    /**
     * 保存文字列を復元する。形式不正・範囲外の値は null を返し、
     * 壊れたキャッシュを表示に使わない。
     */
    fun decode(text: String?): MobileAttendanceSnapshot? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_ENCODED_LENGTH) return null
        val snapshot = runCatching {
            json.decodeFromString(MobileAttendanceSnapshot.serializer(), raw)
        }.getOrNull() ?: return null
        return snapshot.takeIf { isValid(it) }
    }

    private fun isValid(snapshot: MobileAttendanceSnapshot): Boolean {
        if (snapshot.capturedAtEpochMs <= 0L) return false
        if (snapshot.month !in 1..12) return false
        if (snapshot.day !in 1..31) return false
        if (snapshot.year != null && snapshot.year !in 2000..2100) return false
        if (snapshot.confirmed.size > MAX_CONFIRMED) return false
        if (snapshot.pending.size > MAX_CONFIRMED) return false
        if (snapshot.unboundCourseCount !in 0..MAX_CONFIRMED * 10) return false
        if (snapshot.ambiguousCourseCount !in 0..MAX_CONFIRMED * 10) return false
        if (snapshot.pendingUnboundCourseCount !in 0..MAX_CONFIRMED * 10) return false
        if (snapshot.pendingAmbiguousCourseCount !in 0..MAX_CONFIRMED * 10) return false
        if (snapshot.transport != null &&
            NetworkTransport.entries.none { it.name == snapshot.transport }
        ) {
            return false
        }
        if (snapshot.pending.any { record ->
                record.windowStartMinutes !in 0..(24 * 60 - 1) ||
                    record.windowEndMinutes !in 1..(24 * 60) ||
                    record.windowStartMinutes >= record.windowEndMinutes
            }
        ) {
            return false
        }
        if (!snapshot.confirmed.all { record -> record.isValid() }) return false
        return snapshot.pending.all { record -> record.isValid() }
    }

    private fun ConfirmedAttendanceRecord.isValid(): Boolean =
        courseCode.isNotBlank() &&
            courseCode.length <= 200 &&
            courseName.isNotBlank() &&
            courseName.length <= 200 &&
            dayOfWeek in 1..7 &&
            period in 1..20 &&
            month in 1..12 &&
            day in 1..31 &&
            isValidInference(inferred, inferenceBasis)

    private fun PendingAttendanceRecord.isValid(): Boolean =
        courseCode.isNotBlank() &&
            courseCode.length <= 200 &&
            courseName.isNotBlank() &&
            courseName.length <= 200 &&
            dayOfWeek in 1..7 &&
            period in 1..20 &&
            month in 1..12 &&
            day in 1..31 &&
            isValidInference(inferred, inferenceBasis)

    /** 推定フラグと根拠の整合。根拠は推定した場合だけ持ち、長すぎる値は読まない。 */
    private fun isValidInference(inferred: Boolean, inferenceBasis: String?): Boolean {
        if (!inferred && inferenceBasis != null) return false
        if (inferenceBasis != null && inferenceBasis.length > MAX_INFERENCE_BASIS_LENGTH) return false
        return true
    }

    /** 推定根拠として保存してよい文字数の上限。 */
    const val MAX_INFERENCE_BASIS_LENGTH: Int = 300
}

/**
 * 画面から読み取った [ClassMobileAttendanceDomExtractor.Observation] を、
 * 既存の時間割のコマへ**確実に紐付けられる分だけ**変換する純ロジック。
 *
 * 紐付けの条件（すべて満たすときだけ採用する）:
 * - 同じ授業ブロックに完全一致の「出席」ラベルがあり、ブロックの日付がページ日付と矛盾しない
 *   （ブロックに日付が無い場合はページ日付に従う。ページ日付が無い場合は採用しない）
 * - 科目名・曜日時限を一意に確認できる（候補の重複・欠席などの表記が無い）
 * - 曜日時限が単一の時限で、画面日付の曜日表記とも矛盾しない
 * - 既存時間割の同じ曜日・時限に、科目名が完全一致するコマがちょうど 1 つだけある
 * - 同じ時間割のコマに対応する画面ブロック（「出席」「出席確認中」）が 1 つだけである
 *
 * 同じ時間割のコマに複数の画面ブロックが対応した場合は、どちらも採用しない。
 * 「出席」を「出席確認中」より優先したり、先に読めた 1 件だけを採用したりしない
 * （対応が一意に決まらないため、両方を除外して ambiguous として数える）。
 *
 * 上記を満たさない授業は推測で補わず、件数だけを数えて [Result.unboundCount] /
 * [Result.ambiguousCount] に残す。科目コードは画面の英数字から推測しない。
 *
 * 画面に曜日時限が表示されていない場合（[ClassMobileAttendanceDomExtractor.ObservedCourse.slot]
 * が null）だけは、[MobileAttendanceSlotInference] で**一意に推定できたときだけ**採用する。
 * 推定の根拠は、ページの年あり日付から求めた曜日・正規化した科目名の完全一致・
 * 確認済みの出席確認受付時間と [org.cabetus.domain.PeriodTimes] の授業時間の重なりに限る。
 * 日付が不明（年なし・不正・曜日表記と矛盾）、受付時間が無い、同名複数、連続時限・候補複数、
 * 同名コマの時限と受付時間の食い違いのいずれかなら保留し、直近・先頭の候補は選ばない。
 * 推定したレコードには [ConfirmedAttendanceRecord.inferred] /
 * [PendingAttendanceRecord.inferred] と推定根拠を残す（既存レコードは false / null で互換）。
 */
object MobileAttendanceBinder {

    data class Result(
        val confirmed: List<ConfirmedAttendanceRecord>,
        val pending: List<PendingAttendanceRecord>,
        /** 出席表示はあったが、時間割と紐付けられなかった件数。 */
        val unboundCount: Int,
        /** 表記の重複・欠席表記・同一コマへの複数対応などで判定できなかった件数。 */
        val ambiguousCount: Int,
        /** 未提出表示はあったが、時間割と紐付けられなかった件数。 */
        val pendingUnboundCount: Int,
        /** 未提出表示を、表記の重複・同一コマへの複数対応などで判定できなかった件数。 */
        val pendingAmbiguousCount: Int,
        /** 画面から判定に使えた出席表示の件数。 */
        val observedSuccessCount: Int,
        val warnings: List<String>,
    )

    fun bind(
        observation: ClassMobileAttendanceDomExtractor.Observation,
        cells: List<TimetableCellEntity>,
        nameByCode: Map<String, String> = emptyMap(),
    ): Result {
        val pageDate = observation.pageDate
        val weekdayFromPageDate = pageDate?.weekdayLabel?.let { weekdayIndex(it) }

        // 画面ブロック 1 つ分の候補。どの時間割のコマに対応したかは SlotKey で持つ。
        // 同じコマに複数のブロックが対応した場合は、このあと全て除外して ambiguous にする。
        val confirmedCandidates = mutableListOf<ConfirmedAttendanceRecord>()
        val pendingCandidates = mutableListOf<PendingAttendanceRecord>()
        var unbound = 0
        var ambiguous = 0
        var pendingUnbound = 0
        var pendingAmbiguous = 0
        var observedSuccess = 0
        var inferredConfirmed = 0
        var inferredPending = 0
        val warnings = mutableListOf<String>()

        observation.courses.forEach { course ->
            if (course.successLabelVisible) {
                observedSuccess++

                val slot = course.slot
                val name = course.courseName?.let { normalizeName(it) }?.takeIf { it.isNotEmpty() }
                if (course.ambiguous || name == null) {
                    ambiguous++
                    return@forEach
                }
                if (slot == null) {
                    // 画面に曜日時限が無い場合だけ、ページ日付の曜日・科目名・受付時間から推定する。
                    // 受付時間が無い場合は時刻による推定をしない（推定の根拠が無い）。
                    val page = pageDate
                    // ブロックの日付が無いだけでは拒否しない（日付はページ日付に従う）。
                    // ページ日付が無い、またはブロックの日付がページ日付と食い違う場合は推定しない。
                    if (page == null || !dateMatchesPage(course.date, page)) {
                        unbound++
                        return@forEach
                    }
                    when (val outcome = MobileAttendanceSlotInference.infer(
                        pageDate = page,
                        courseName = name,
                        window = course.confirmationWindow,
                        cells = cells,
                        nameByCode = nameByCode,
                    )) {
                        is MobileAttendanceSlotInference.Outcome.Adopted -> {
                            val cell = outcome.cell
                            confirmedCandidates += ConfirmedAttendanceRecord(
                                courseCode = cell.courseCode,
                                courseName = cellName(cell, nameByCode),
                                dayOfWeek = cell.dayOfWeek,
                                period = cell.period,
                                year = page.year,
                                month = page.month,
                                day = page.day,
                                inferred = true,
                                inferenceBasis = outcome.basis,
                            )
                            inferredConfirmed++
                        }
                        is MobileAttendanceSlotInference.Outcome.Held -> {
                            if (outcome.reason.countsAsAmbiguous()) ambiguous++ else unbound++
                        }
                    }
                    return@forEach
                }
                if (!course.isAttendanceConfirmed(pageDate)) {
                    unbound++
                    return@forEach
                }
                // 画面日付の曜日表記と、同じ授業ブロックの曜日時限が食い違う場合は紐付けない。
                if (weekdayFromPageDate != null && weekdayFromPageDate != slot.dayOfWeek) {
                    ambiguous++
                    return@forEach
                }
                val period = slot.singlePeriod
                if (period == null) {
                    ambiguous++
                    return@forEach
                }

                val matches = cells.filter { cell ->
                    cell.dayOfWeek == slot.dayOfWeek &&
                        cell.period == period &&
                        normalizeName(cellName(cell, nameByCode)) == name
                }.distinctBy { it.courseCode }

                when (matches.size) {
                    1 -> {
                        val cell = matches.single()
                        confirmedCandidates += ConfirmedAttendanceRecord(
                            courseCode = cell.courseCode,
                            courseName = cellName(cell, nameByCode),
                            dayOfWeek = cell.dayOfWeek,
                            period = cell.period,
                            year = pageDate?.year,
                            month = pageDate!!.month,
                            day = pageDate.day,
                        )
                    }
                    0 -> unbound++
                    else -> ambiguous++
                }
                return@forEach
            }

            val name = course.courseName?.let { normalizeName(it) }?.takeIf { it.isNotEmpty() }
            if (course.ambiguous || name == null) {
                pendingAmbiguous++
                return@forEach
            }
            val slot = course.slot
            if (slot == null) {
                // 未提出も、明示 slot が無い場合は同じ根拠（ページ日付の曜日・科目名・受付時間）で
                // 一意に推定できたときだけ採用する。「出席確認中」と受付時間は必須。
                val page = pageDate
                val window = course.confirmationWindow
                if (page == null || !dateMatchesPage(course.date, page) ||
                    !course.confirmationOpen || window == null || !window.isValid
                ) {
                    pendingAmbiguous++
                    return@forEach
                }
                when (val outcome = MobileAttendanceSlotInference.infer(
                    pageDate = page,
                    courseName = name,
                    window = window,
                    cells = cells,
                    nameByCode = nameByCode,
                )) {
                    is MobileAttendanceSlotInference.Outcome.Adopted -> {
                        val cell = outcome.cell
                        pendingCandidates += PendingAttendanceRecord(
                            courseCode = cell.courseCode,
                            courseName = cellName(cell, nameByCode),
                            dayOfWeek = cell.dayOfWeek,
                            period = cell.period,
                            year = page.year,
                            month = page.month,
                            day = page.day,
                            windowStartMinutes = window.start.hour * 60 + window.start.minute,
                            windowEndMinutes = window.end.hour * 60 + window.end.minute,
                            inferred = true,
                            inferenceBasis = outcome.basis,
                        )
                        inferredPending++
                    }
                    is MobileAttendanceSlotInference.Outcome.Held -> {
                        if (outcome.reason.countsAsAmbiguous()) pendingAmbiguous++
                        else pendingUnbound++
                    }
                }
                return@forEach
            }
            if (!course.isAttendancePending(pageDate)) return@forEach
            if (weekdayFromPageDate != null && weekdayFromPageDate != slot.dayOfWeek) {
                pendingAmbiguous++
                return@forEach
            }
            val period = slot.singlePeriod
            val window = course.confirmationWindow
            if (period == null || window == null || !window.isValid) {
                pendingAmbiguous++
                return@forEach
            }

            val matches = cells.filter { cell ->
                cell.dayOfWeek == slot.dayOfWeek &&
                    cell.period == period &&
                    normalizeName(cellName(cell, nameByCode)) == name
            }.distinctBy { it.courseCode }

            when (matches.size) {
                1 -> {
                    val cell = matches.single()
                    pendingCandidates += PendingAttendanceRecord(
                        courseCode = cell.courseCode,
                        courseName = cellName(cell, nameByCode),
                        dayOfWeek = cell.dayOfWeek,
                        period = cell.period,
                        year = pageDate?.year,
                        month = pageDate!!.month,
                        day = pageDate.day,
                        windowStartMinutes = window.start.hour * 60 + window.start.minute,
                        windowEndMinutes = window.end.hour * 60 + window.end.minute,
                    )
                }
                0 -> pendingUnbound++
                else -> pendingAmbiguous++
            }
        }

        // 同じ時間割のコマ（科目コード・曜日・時限）に対応した画面ブロックが複数ある場合は、
        // 「出席」を「出席確認中」より優先したり、先に読めた 1 件だけを採用したりしない。
        // 対応が一意に決まらないため、そのコマの候補は confirmed / pending の両方から除外する。
        val boundCounts = mutableMapOf<SlotKey, Int>()
        confirmedCandidates.forEach { record -> boundCounts.merge(record.slotKey(), 1, Int::plus) }
        pendingCandidates.forEach { record -> boundCounts.merge(record.slotKey(), 1, Int::plus) }
        val conflictedSlots = boundCounts.filterValues { count -> count > 1 }.keys

        val droppedConfirmed = confirmedCandidates.count { record -> record.slotKey() in conflictedSlots }
        val droppedPending = pendingCandidates.count { record -> record.slotKey() in conflictedSlots }
        ambiguous += droppedConfirmed
        pendingAmbiguous += droppedPending

        val confirmed = confirmedCandidates.filterNot { record -> record.slotKey() in conflictedSlots }
        val pending = pendingCandidates.filterNot { record -> record.slotKey() in conflictedSlots }

        if (unbound > 0) {
            warnings +=
                "「出席」表示はありましたが、科目名・日付・時限を時間割と確実に紐付けられない授業が" +
                    "${unbound}件あります。紐付けできない授業は反映しません。"
        }
        if (ambiguous > 0) {
            warnings +=
                "表記の重複や欠席などの表記があり、出席かどうかを判定できなかった授業が" +
                    "${ambiguous}件あります。反映しません。"
        }
        if (confirmed.isEmpty() && observedSuccess > 0) {
            warnings +=
                "時間割と紐付いた出席を確認できませんでした。時間割の科目名と CLASS の科目名が" +
                    "一致しているか確認してください。"
        }
        if (pendingUnbound > 0) {
            warnings +=
                "「出席確認中」の授業のうち、科目名・日付・時限を時間割と確実に紐付けられない授業が" +
                    "${pendingUnbound}件あります。締切は表示しません。"
        }
        if (pendingAmbiguous > 0) {
            warnings +=
                "「出席確認中」の授業のうち、表記の重複などで未提出と判定できない授業が" +
                    "${pendingAmbiguous}件あります。締切は表示しません。"
        }
        if (conflictedSlots.isNotEmpty()) {
            warnings +=
                "同じ時間割のコマに対応する「出席」または「出席確認中」の表示が複数あったため、" +
                    "出席かどうかを判定できなかった授業が${droppedConfirmed + droppedPending}件あります。" +
                    "反映しません。"
        }
        if (inferredConfirmed + inferredPending > 0) {
            warnings +=
                "画面に曜日時限が無い授業のうち、${inferredConfirmed + inferredPending}件は" +
                    "ページ日付の曜日・科目名の完全一致・出席確認時間と時限時刻の重なりから" +
                    "コマを一意に推定しました（推定フラグ付き）。推定できない授業は反映しません。"
        }

        return Result(
            confirmed = confirmed,
            pending = pending,
            unboundCount = unbound,
            ambiguousCount = ambiguous,
            pendingUnboundCount = pendingUnbound,
            pendingAmbiguousCount = pendingAmbiguous,
            observedSuccessCount = observedSuccess,
            warnings = warnings,
        )
    }

    /** 推定を保留した理由のうち、判定不能として数えるもの。 */
    private fun MobileAttendanceSlotInference.Reason.countsAsAmbiguous(): Boolean = when (this) {
        MobileAttendanceSlotInference.Reason.DATE_UNKNOWN,
        MobileAttendanceSlotInference.Reason.NAME_UNKNOWN,
        MobileAttendanceSlotInference.Reason.PERIOD_AMBIGUOUS,
        MobileAttendanceSlotInference.Reason.MULTIPLE_CANDIDATES,
        MobileAttendanceSlotInference.Reason.PERIOD_MISMATCH,
        -> true

        MobileAttendanceSlotInference.Reason.WINDOW_UNKNOWN,
        MobileAttendanceSlotInference.Reason.NO_CANDIDATE,
        -> false
    }

    /**
     * 授業ブロックの日付がページ日付と矛盾しないか。
     * ブロックに日付が無い場合はページ日付に従う（矛盾ではない）。
     * ブロックの日付がページ日付と食い違う場合は false（別の日の表示を混ぜない）。
     */
    private fun dateMatchesPage(courseDate: CapturedDate?, page: CapturedDate): Boolean {
        if (courseDate == null) return true
        return courseDate.sameDateAs(page)
    }

    /** 時間割のコマを一意に表すキー。同じキーに複数の画面ブロックが対応したら採用しない。 */
    private data class SlotKey(
        val courseCode: String,
        val dayOfWeek: Int,
        val period: Int,
    )

    private fun ConfirmedAttendanceRecord.slotKey(): SlotKey =
        SlotKey(courseCode = courseCode, dayOfWeek = dayOfWeek, period = period)

    private fun PendingAttendanceRecord.slotKey(): SlotKey =
        SlotKey(courseCode = courseCode, dayOfWeek = dayOfWeek, period = period)

    fun cellName(cell: TimetableCellEntity, nameByCode: Map<String, String>): String =
        nameByCode[cell.courseCode]?.takeIf { it.isNotBlank() }
            ?: cell.courseName.ifBlank { cell.courseCode }

    /** 科目名の比較用。空白（全角含む）を 1 つに畳み、前後を除くだけにする。 */
    fun normalizeName(value: String?): String =
        (value ?: "")
            .replace('\u00a0', ' ')
            .replace('\u3000', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun weekdayIndex(label: String): Int? = when (label) {
        "月" -> 1
        "火" -> 2
        "水" -> 3
        "木" -> 4
        "金" -> 5
        "土" -> 6
        "日" -> 7
        else -> null
    }
}
