package org.cabetus

import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.cabetus.ui.assignments.AssignmentCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class AssignmentCalendarTest {

    private val jst = ZoneId.of("Asia/Tokyo")

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(jst).toInstant().toEpochMilli()

    private fun assignment(id: String, deadline: Long?, title: String = id) = AssignmentEntity(
        id = id,
        courseId = "c1",
        courseName = "線形代数",
        title = title,
        url = "https://letus.ed.tus.ac.jp/mod/assign/view.php?id=$id",
        deadline = deadline,
        deadlineText = "",
        submissionStatus = SubmissionStatus.NOT_SUBMITTED,
        lifecycleStatus = LifecycleStatus.ACTIVE,
        detectedAt = 0L,
        firstSeenAt = 0L,
        lastCheckedAt = 0L,
    )

    @Test
    fun deadlineDateUsesAsiaTokyo() {
        // 2026-01-01 23:59 JST
        val deadline = epoch(2026, 1, 1, 23, 59)
        assertEquals(LocalDate.of(2026, 1, 1), AssignmentCalendar.deadlineDate(deadline, jst))
        assertNull(AssignmentCalendar.deadlineDate(null))
    }

    @Test
    fun deadlineWithoutDateIsNotGrouped() {
        val withDeadline = assignment("a", epoch(2026, 3, 10, 9, 0))
        val noDeadline = assignment("b", null)

        val grouped = AssignmentCalendar.groupByDeadlineDate(listOf(withDeadline, noDeadline), jst)

        assertEquals(1, grouped.size)
        assertEquals(listOf("a"), grouped.getValue(LocalDate.of(2026, 3, 10)).map { it.id })
        assertEquals(mapOf(LocalDate.of(2026, 3, 10) to 1), AssignmentCalendar.countByDeadlineDate(listOf(withDeadline, noDeadline), jst))
    }

    @Test
    fun groupingSortsByDeadlineWithinDay() {
        val late = assignment("late", epoch(2026, 3, 10, 18, 0))
        val early = assignment("early", epoch(2026, 3, 10, 9, 0))

        val grouped = AssignmentCalendar.groupByDeadlineDate(listOf(late, early), jst)

        assertEquals(listOf("early", "late"), grouped.getValue(LocalDate.of(2026, 3, 10)).map { it.id })
    }

    @Test
    fun gridIsAlwaysSixWeeks() {
        val march = YearMonth.of(2026, 3)
        val cells = AssignmentCalendar.buildMonthCells(march)

        assertEquals(42, cells.size)
        // 2026-03-01 は日曜 -> 月曜始まりでは先頭 6 セルが空白。
        assertEquals(6, cells.takeWhile { it.date == null }.size)
        assertEquals(LocalDate.of(2026, 3, 1), cells.first { it.date != null }.date)
        assertEquals(LocalDate.of(2026, 3, 31), cells.last { it.inMonth }.date)
        assertEquals(31, cells.count { it.inMonth })
        assertTrue(cells.all { it.date == null || !it.inMonth || YearMonth.from(it.date) == march })
    }

    @Test
    fun gridStartsOnMondayWhenMonthBeginsMonday() {
        val june = YearMonth.of(2026, 6) // 2026-06-01 は月曜
        val cells = AssignmentCalendar.buildMonthCells(june)

        assertEquals(LocalDate.of(2026, 6, 1), cells.first().date)
        assertTrue(cells.first().inMonth)
    }

    @Test
    fun gridCarriesCountsForInMonthDaysOnly() {
        val month = YearMonth.of(2026, 5)
        val counts = mapOf(
            LocalDate.of(2026, 5, 12) to 3,
            LocalDate.of(2026, 5, 31) to 1,
            LocalDate.of(2026, 4, 1) to 9, // 月外は載せない
        )
        val cells = AssignmentCalendar.buildMonthCells(month, counts)

        assertEquals(3, cells.first { it.date == LocalDate.of(2026, 5, 12) }.count)
        assertEquals(1, cells.first { it.date == LocalDate.of(2026, 5, 31) }.count)
        assertTrue(cells.filter { !it.inMonth }.all { it.date == null && it.count == 0 })
    }

    @Test
    fun weekdayLabelsStartOnMonday() {
        assertEquals(listOf("月", "火", "水", "木", "金", "土", "日"), AssignmentCalendar.WEEKDAY_LABELS)
        assertFalse(AssignmentCalendar.WEEKDAY_LABELS.isEmpty())
    }
}
