package org.cabetus.domain

import org.cabetus.data.local.AttendanceOverrideEntity
import org.cabetus.data.local.ClassSessionEntity
import org.cabetus.data.local.LocalAttendanceEntity
import org.cabetus.data.local.MobileAttendanceConfirmationEntity
import java.time.Instant
import java.time.ZoneId

/** 出欠の既存優先順位を保ち、未記録の過去回を明示する。 */
object AttendanceGoalRecords {
    data class Records(val statuses: List<AttendanceStatus>, val hasUnknownPast: Boolean)

    fun resolve(
        code: String,
        sessions: List<ClassSessionEntity>,
        locals: List<LocalAttendanceEntity>,
        overrides: List<AttendanceOverrideEntity>,
        nowMillis: Long,
        mobileConfirmations: List<MobileAttendanceConfirmationEntity> = emptyList(),
    ): Records {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDateTime()
        val sessionMap = sessions.filter { it.courseCode == code }.associateBy { it.date to it.period }
        val localKeys = locals.filter { it.courseCode == code }.map { it.date to it.period }.toSet()
        val overrideMap = overrides.filter { it.courseCode == code }.associateBy { it.date to it.period }
        val mobileKeys = mobileConfirmations.filter { it.courseCode == code }
            .map { it.date to it.period }.toSet()
        var unknown = false
        val statuses = (sessionMap.keys + localKeys + overrideMap.keys + mobileKeys).mapNotNull { key ->
            val date = Instant.ofEpochMilli(key.first).atZone(zone).toLocalDate()
            val end = PeriodTimes.of(key.second)?.end
            val past = if (end != null) !date.atTime(end).isAfter(now) else date.isBefore(now.toLocalDate())
            // 未来・授業中の回はまだ実施回として消費しない。
            if (!past) return@mapNotNull null
            val status = AttendanceResolver.resolve(
                sessionMap[key]?.mark,
                key in localKeys,
                overrideMap[key]?.status?.let { runCatching { AttendanceStatus.valueOf(it) }.getOrNull() },
                key in mobileKeys,
            ).status
            if (status == AttendanceStatus.UNRECORDED) unknown = true
            status
        }
        return Records(statuses, unknown)
    }
}
