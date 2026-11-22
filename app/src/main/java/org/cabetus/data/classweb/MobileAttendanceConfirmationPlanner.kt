package org.cabetus.data.classweb

import org.cabetus.data.local.MobileAttendanceConfirmationEntity
import org.cabetus.domain.PeriodTimes
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MobileAttendanceSnapshot] の「出席」確認済みレコードを、時間割の出席確認として保存できる
 * 行 [MobileAttendanceConfirmationEntity] に変換する純ロジック。
 *
 * 保存してよいのは、日付・曜日・時限を推測なしで確定できるレコードだけ:
 * - 画面の年とレコードの年がどちらも明示され、同じ有効な日付を指す
 * - レコードの曜日が実際のカレンダーの曜日と一致する
 * - 画面に曜日表記があれば、月火水木金土日として解釈でき、実際の曜日と一致する
 * - 時限が [PeriodTimes] に定義されている
 * - 科目コードが空でない
 * - 推定レコードは推定根拠が空でない
 *
 * 未提出（[MobileAttendanceSnapshot.pending]）は出席確認として保存しない。
 * 同じ科目コード・日付・時限の重複は 1 行にまとめる（先に現れた行を残す）。
 * 出席確認時刻は [MobileAttendanceSnapshot.capturedAtEpochMs] を使う。
 */
object MobileAttendanceConfirmationPlanner {

    private val JST: ZoneId = ZoneId.of("Asia/Tokyo")

    private val WEEKDAY_LABELS = listOf("月", "火", "水", "木", "金", "土", "日")

    fun plan(snapshot: MobileAttendanceSnapshot): List<MobileAttendanceConfirmationEntity> {
        val snapshotYear = snapshot.year ?: return emptyList()
        val snapshotDate = validDate(snapshotYear, snapshot.month, snapshot.day) ?: return emptyList()
        snapshot.weekdayLabel?.let { label ->
            val index = WEEKDAY_LABELS.indexOf(label)
            if (index < 0 || index + 1 != snapshotDate.dayOfWeek.value) return emptyList()
        }

        val seen = mutableSetOf<Triple<String, Long, Int>>()
        return snapshot.confirmed.mapNotNull { record ->
            val year = record.year ?: return@mapNotNull null
            if (year != snapshotYear) return@mapNotNull null
            val date = validDate(year, record.month, record.day) ?: return@mapNotNull null
            if (date != snapshotDate) return@mapNotNull null
            if (record.dayOfWeek != date.dayOfWeek.value) return@mapNotNull null
            if (PeriodTimes.of(record.period) == null) return@mapNotNull null
            if (record.courseCode.isBlank()) return@mapNotNull null
            if (record.inferred && record.inferenceBasis.isNullOrBlank()) return@mapNotNull null

            val dateEpochMs = date.atStartOfDay(JST).toInstant().toEpochMilli()
            val key = Triple(record.courseCode, dateEpochMs, record.period)
            if (!seen.add(key)) return@mapNotNull null

            MobileAttendanceConfirmationEntity(
                courseCode = record.courseCode,
                date = dateEpochMs,
                period = record.period,
                confirmedAt = snapshot.capturedAtEpochMs,
                inferred = record.inferred,
                inferenceBasis = if (record.inferred) record.inferenceBasis else null,
            )
        }
    }

    private fun validDate(year: Int, month: Int, day: Int): LocalDate? = runCatching {
        LocalDate.of(year, month, day)
    }.getOrNull()
}
