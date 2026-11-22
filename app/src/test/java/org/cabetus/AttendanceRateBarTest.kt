package org.cabetus

import org.cabetus.domain.AttendanceGoal
import org.cabetus.domain.AttendanceGoalMode
import org.cabetus.ui.timetable.attendanceGoalFraction
import org.cabetus.ui.timetable.attendanceGoalLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttendanceRateBarTest {
    @Test
    fun percentageMarkerUsesTheConfiguredTargetNotTheRoundedMeetingCount() {
        val goal = AttendanceGoal(15, AttendanceGoalMode.PERCENT, 67)
        assertEquals(0.67f, requireNotNull(attendanceGoalFraction(goal)), 0.0001f)
        assertEquals("目標 67%", attendanceGoalLabel(goal))
    }

    @Test
    fun countMarkerUsesTheConfiguredTotalAndSupportsTheRightEdge() {
        val goal = AttendanceGoal(7, AttendanceGoalMode.COUNT, 5)
        assertEquals(5f / 7f, requireNotNull(attendanceGoalFraction(goal)), 0.0001f)
        assertEquals("目標 5 / 7回", attendanceGoalLabel(goal))
        assertEquals(1f, requireNotNull(attendanceGoalFraction(goal.copy(value = 7))), 0.0001f)
    }

    @Test
    fun unsetAndInvalidGoalsHaveNoMarker() {
        assertNull(attendanceGoalFraction(null))
        assertNull(attendanceGoalFraction(AttendanceGoal(0, AttendanceGoalMode.COUNT, 1)))
        assertNull(attendanceGoalLabel(AttendanceGoal(15, AttendanceGoalMode.PERCENT, 101)))
    }
}
