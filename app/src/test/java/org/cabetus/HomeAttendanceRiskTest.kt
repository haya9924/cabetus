package org.cabetus

import org.cabetus.domain.*
import org.cabetus.data.local.TimetableCellEntity
import org.junit.Assert.*
import org.junit.Test

class HomeAttendanceRiskTest {
    private fun cell(code: String, day: Int = 1) = TimetableCellEntity(dayOfWeek=day, period=1, courseCode=code, courseName=code, instructor="", room=null, isOnline=false, isMultiSession=false)
    private fun evaluation(remaining: Int, year: Int = 2026) = AttendanceGoalMonitor.Evaluation(
        AttendanceGoal(15, AttendanceGoalMode.COUNT, 12), AttendanceGoalCalculator.Result.Computed(12, remaining), year)
    @Test fun includesTwoZeroNegativeButNotThree() {
        val values = mapOf("A" to evaluation(3), "B" to evaluation(2), "C" to evaluation(0), "D" to evaluation(-1))
        val rows = attendanceRiskRows(values, values.keys.map { cell(it) } + cell("B", 5), 2026)
        assertEquals(listOf("D", "C", "B"), rows.map { it.code })
    }
    @Test fun excludesUnknownOtherYearAndInactive() {
        val unknown = evaluation(2).copy(result=AttendanceGoalCalculator.Result.NotComputed(AttendanceGoalCalculator.NotComputedReason.PAST_PENDING_INPUT))
        val values = mapOf("A" to unknown, "B" to evaluation(2,2025), "C" to evaluation(0))
        assertTrue(attendanceRiskRows(values, listOf(cell("A"),cell("B")),2026).isEmpty())
        assertTrue(attendanceRiskRows(values, listOf(cell("C")),0).isEmpty())
    }
}
