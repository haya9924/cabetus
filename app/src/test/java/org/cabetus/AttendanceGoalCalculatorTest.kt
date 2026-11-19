package org.cabetus

import org.cabetus.domain.*
import org.cabetus.data.settings.*
import org.junit.Assert.*
import org.junit.Test

class AttendanceGoalCalculatorTest {
    private val goal = AttendanceGoal(15, AttendanceGoalMode.PERCENT, 80)
    @Test fun percentRoundsUpAndWarnsAtTwo() {
        for (absent in 0..4) {
            val result = AttendanceGoalCalculator.calculate(goal, List(absent) { AttendanceRecord(AttendanceStatus.ABSENT) }) as AttendanceGoalCalculator.Result.Computed
            assertEquals(12, result.required)
            assertEquals(3 - absent, result.remainingAbsences)
            assertEquals(absent >= 1, result.isRisk)
            assertEquals(absent > 3, result.isUnattainable)
        }
        assertEquals(11, AttendanceGoalCalculator.requiredAttendance(AttendanceGoal(15, AttendanceGoalMode.PERCENT, 67)))
    }
    @Test fun countModeAndLateMatchExistingSummary() {
        val g = AttendanceGoal(15, AttendanceGoalMode.COUNT, 12)
        val result = AttendanceGoalCalculator.calculate(g, listOf(AttendanceRecord(AttendanceStatus.LATE))) as AttendanceGoalCalculator.Result.Computed
        assertEquals(2, result.remainingAbsences)
    }
    @Test fun unknownAndHolidayNeverConsumeAllowance() {
        val result = AttendanceGoalCalculator.calculate(goal, listOf(AttendanceRecord(AttendanceStatus.UNRECORDED), AttendanceRecord(AttendanceStatus.HOLIDAY))) as AttendanceGoalCalculator.Result.Computed
        assertEquals(3, result.remainingAbsences)
        assertTrue(AttendanceGoalCalculator.calculate(goal, listOf(AttendanceRecord(AttendanceStatus.UNRECORDED, true))) is AttendanceGoalCalculator.Result.NotComputed)
    }
    @Test fun invalidAndExcessRecordsAreNotCalculated() {
        assertTrue(AttendanceGoalCalculator.calculate(goal.copy(value = 101), emptyList()) is AttendanceGoalCalculator.Result.NotComputed)
        assertTrue(AttendanceGoalCalculator.calculate(goal, List(16) { AttendanceRecord(AttendanceStatus.PRESENT) }) is AttendanceGoalCalculator.Result.NotComputed)
    }
    @Test fun codecRoundTripAndYearIsolation() {
        val map = mapOf(attendanceGoalKey(2026, "A") to goal, attendanceGoalKey(2027, "A") to goal.copy(value=90))
        assertEquals(map, AttendanceGoalCodec.decodeOrThrow(AttendanceGoalCodec.encode(map)))
        assertTrue(AttendanceGoalCodec.decode("broken").isEmpty())
    }
    @Test(expected = IllegalStateException::class) fun corruptSettingsMustNotBeOverwritten() {
        AttendanceGoalCodec.decodeOrThrow("broken")
    }
    @Test(expected = IllegalArgumentException::class) fun unspecifiedYearIsRejected() {
        attendanceGoalKey(0, "A")
    }
}
