package org.cabetus.domain

/** 出席状況の分類。label は UI 表示用。 */
enum class AttendanceStatus(val label: String) {
    PRESENT("出席"),
    LATE("遅刻・早退"),
    ABSENT("欠席"),
    HOLIDAY("公休"),
    UNRECORDED("未記録"),
}

/** 出席状況の判定根拠。優先順位は宣言順（先頭ほど強い）。 */
enum class AttendanceSource {
    /** モバイルからの出席登録。最優先。 */
    MOBILE_REGISTRATION,

    /** ユーザーの手動修正。 */
    MANUAL,

    /** アプリの「出席にカウント」記録。 */
    LOCAL,

    /** 出欠PDF由来のマーク。 */
    PDF,

    /** CLASSで取得した記録。 */
    CLASS,

    /** どこにも記録がない。 */
    NONE,
}

/**
 * 出欠マーク・アプリ記録・手動修正から実効的な出席状況を求める純粋関数群。JVM テスト可能。
 * 優先順位: モバイル出席登録 > 手動修正 > アプリ記録（=出席） > PDFマーク。
 */
object AttendanceResolver {

    /** 出席の集計。rate は held=0 のとき null。 */
    data class Summary(val held: Int, val present: Int, val ratePercent: Int?)

    /** 解決結果。 */
    data class Resolved(val status: AttendanceStatus, val source: AttendanceSource)

    /** 出欠PDFのマーク文字を状態に変換する。 */
    fun fromMark(mark: String?): AttendanceStatus = when (mark?.trim()) {
        null, "" -> AttendanceStatus.UNRECORDED
        "〇", "○", "◯" -> AttendanceStatus.PRESENT
        "▽", "△", "到", "追", "再" -> AttendanceStatus.LATE
        "×", "✕" -> AttendanceStatus.ABSENT
        "公", "休" -> AttendanceStatus.HOLIDAY
        else -> AttendanceStatus.UNRECORDED
    }

    /**
     * 各種ソースから実効ステータスを解決する。
     * 優先順位: モバイル出席登録 > 手動修正 > アプリ記録 > PDFマーク。
     * @param mark PDF由来マーク（なければ null）
     * @param hasLocalCheck アプリの「出席にカウント」記録があるか
     * @param overrideStatus 手動修正（なければ null）
     * @param hasMobileConfirmation モバイルからの出席登録があるか（既定 false）。
     *   true なら他のすべてのソースより優先して「出席」を返す。
     */
    fun resolve(
        mark: String?,
        hasLocalCheck: Boolean,
        overrideStatus: AttendanceStatus?,
        hasMobileConfirmation: Boolean = false,
    ): Resolved {
        if (hasMobileConfirmation) {
            return Resolved(AttendanceStatus.PRESENT, AttendanceSource.MOBILE_REGISTRATION)
        }
        if (overrideStatus != null) return Resolved(overrideStatus, AttendanceSource.MANUAL)
        if (hasLocalCheck) return Resolved(AttendanceStatus.PRESENT, AttendanceSource.LOCAL)
        val fromMark = fromMark(mark)
        val source = if (mark.isNullOrBlank()) AttendanceSource.NONE else AttendanceSource.PDF
        return Resolved(fromMark, source)
    }

    /**
     * 出席率を集計する。実施回（分母）は 出席・遅刻早退・欠席 のみ（公休・未記録は除外）。
     * 出席回（分子）は PRESENT のみ（遅刻・早退は出席に数えない）。
     */
    fun summarize(statuses: List<AttendanceStatus>): Summary {
        val held = statuses.count {
            it == AttendanceStatus.PRESENT || it == AttendanceStatus.LATE || it == AttendanceStatus.ABSENT
        }
        val present = statuses.count { it == AttendanceStatus.PRESENT }
        val rate = if (held == 0) null else (present * 100.0 / held).toInt()
        return Summary(held, present, rate)
    }
}
