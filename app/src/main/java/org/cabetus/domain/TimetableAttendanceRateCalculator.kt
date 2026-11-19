package org.cabetus.domain

import org.cabetus.data.local.*
import java.time.LocalDate
import java.time.ZoneId

/** 年度内の終了済み・記録済み分の率。未記録は分母にも分子にも含めず、実施0回なら不明。 */
object TimetableAttendanceRateCalculator {
    fun calculate(code: String, year: Int, sessions: List<ClassSessionEntity>,
                  locals: List<LocalAttendanceEntity>, overrides: List<AttendanceOverrideEntity>, nowMillis: Long,
                  mobileConfirmations: List<MobileAttendanceConfirmationEntity> = emptyList()): Int? {
        if (year !in 1..9998) return null
        val zone = ZoneId.of("Asia/Tokyo")
        val start = LocalDate.of(year, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val records = AttendanceGoalRecords.resolve(code,
            sessions.filter { it.date in start until end },
            locals.filter { it.date in start until end },
            overrides.filter { it.date in start until end }, nowMillis,
            mobileConfirmations.filter { it.date in start until end })
        return AttendanceResolver.summarize(records.statuses).ratePercent
    }
}
