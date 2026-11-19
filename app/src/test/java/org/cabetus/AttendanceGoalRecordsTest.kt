package org.cabetus

import org.cabetus.domain.AttendanceGoalRecords
import org.cabetus.domain.AttendanceStatus
import org.cabetus.domain.TimetableAttendanceRateCalculator
import org.cabetus.data.local.AttendanceOverrideEntity
import org.cabetus.data.local.ClassSessionEntity
import org.cabetus.data.local.MobileAttendanceConfirmationEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AttendanceGoalRecordsTest {
    private val date = LocalDate.of(2026, 6, 1)
    private val zone = ZoneId.of("Asia/Tokyo")
    private val epoch = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun mobile(day: LocalDate = date, period: Int = 1, code: String = "C") =
        MobileAttendanceConfirmationEntity(code, day.atStartOfDay(zone).toInstant().toEpochMilli(), period, 0, false, null)
    private fun session(mark: String?, day: LocalDate = date, period: Int = 1, code: String = "C") =
        ClassSessionEntity(0, code, 1, day.atStartOfDay(zone).toInstant().toEpochMilli(), period, mark)

    @Test fun futureAbsenceDoesNotConsumeAllowance() {
        val record = AttendanceOverrideEntity("C", epoch, 1, "ABSENT", 0)
        val before = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val after = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(AttendanceGoalRecords.resolve("C", emptyList(), emptyList(), listOf(record), before).statuses.isEmpty())
        assertEquals(listOf(AttendanceStatus.ABSENT), AttendanceGoalRecords.resolve("C", emptyList(), emptyList(), listOf(record), after).statuses)
    }
    @Test fun unknownPastIsNotAbsence() {
        val record = AttendanceOverrideEntity("C", epoch, 1, "UNRECORDED", 0)
        val now = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val result = AttendanceGoalRecords.resolve("C", emptyList(), emptyList(), listOf(record), now)
        assertTrue(result.hasUnknownPast)
        assertEquals(listOf(AttendanceStatus.UNRECORDED), result.statuses)
    }
    @Test fun mobileBeatsManualAndClassMarks() {
        val result = AttendanceGoalRecords.resolve("C",
            listOf(session("×")), emptyList(),
            listOf(AttendanceOverrideEntity("C", epoch, 1, "ABSENT", 0)),
            at(date, 12), listOf(mobile()))
        assertEquals(listOf(AttendanceStatus.PRESENT), result.statuses)
        assertFalse(result.hasUnknownPast)
    }
    @Test fun mobileOnlyIsCountedOnce() {
        val result = AttendanceGoalRecords.resolve("C", emptyList(), emptyList(), emptyList(),
            at(date, 12), listOf(mobile()))
        assertEquals(listOf(AttendanceStatus.PRESENT), result.statuses)
    }
    @Test fun otherCourseMobileIsExcluded() {
        val result = AttendanceGoalRecords.resolve("C", emptyList(), emptyList(), emptyList(),
            at(date, 12), listOf(mobile(code = "D")))
        assertTrue(result.statuses.isEmpty())
    }
    @Test fun currentPeriodMobileIsExcluded() {
        val result = AttendanceGoalRecords.resolve("C", emptyList(), emptyList(), emptyList(),
            at(date, 9), listOf(mobile()))
        assertTrue(result.statuses.isEmpty())
    }
    @Test fun rateCountsMobileOnceAndOverridesOtherSources() {
        val rows = listOf(AttendanceOverrideEntity("C", epoch, 1, "ABSENT", 0))
        assertEquals(100, TimetableAttendanceRateCalculator.calculate("C", 2026,
            listOf(session("×")), emptyList(), rows, at(date, 12), listOf(mobile())))
        assertEquals(100, TimetableAttendanceRateCalculator.calculate("C", 2026,
            emptyList(), emptyList(), emptyList(), at(date, 12), listOf(mobile())))
    }
    @Test fun rateExcludesMobileOutsideYearAndCurrentPeriod() {
        assertNull(TimetableAttendanceRateCalculator.calculate("C", 2026,
            emptyList(), emptyList(), emptyList(), at(date, 12),
            listOf(mobile(LocalDate.of(2026, 3, 2)))))
        assertNull(TimetableAttendanceRateCalculator.calculate("C", 2025,
            emptyList(), emptyList(), emptyList(), at(date, 12), listOf(mobile())))
        assertNull(TimetableAttendanceRateCalculator.calculate("C", 2026,
            emptyList(), emptyList(), emptyList(), at(date, 9), listOf(mobile())))
    }
}
