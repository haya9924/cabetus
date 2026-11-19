package org.cabetus

import org.cabetus.domain.TimetableAttendanceRateCalculator
import org.cabetus.data.local.AttendanceOverrideEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimetableAttendanceRateCalculatorTest {
    private fun date(day: Int) = LocalDate.of(2026, 6, day).atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
    private fun row(day: Int, status: String) = AttendanceOverrideEntity("A", date(day), 1, status, 0)
    private fun rate(rows: List<AttendanceOverrideEntity>) = TimetableAttendanceRateCalculator.calculate("A", 2026, emptyList(), emptyList(), rows, date(10))
    @Test fun ratesAreBasedOnConfirmedAttendance() {
        assertNull(rate(emptyList()))
        assertEquals(100, rate(listOf(row(1, "PRESENT"))))
        assertEquals(50, rate(listOf(row(1, "PRESENT"), row(2, "ABSENT"))))
        assertEquals(0, rate(listOf(row(1, "LATE"))))
    }
    @Test fun unknownPastAndFutureAreNotGreenWithoutRecordedAttendance() {
        assertNull(rate(listOf(row(1, "UNRECORDED"))))
        assertNull(rate(listOf(row(12, "PRESENT"))))
        assertNull(rate(listOf(row(1, "HOLIDAY"))))
    }
    @Test fun unrecordedDoesNotHideRecordedRateOrCountAsAbsence() {
        assertEquals(100, rate(listOf(row(1, "PRESENT"), row(2, "UNRECORDED"))))
        assertEquals(50, rate(listOf(row(1, "PRESENT"), row(2, "UNRECORDED"), row(3, "ABSENT"))))
    }
    @Test fun otherAcademicYearIsExcluded() {
        assertNull(TimetableAttendanceRateCalculator.calculate("A", 2025, emptyList(), emptyList(), listOf(row(1, "PRESENT")), date(10)))
    }
}
