package org.cabetus

import org.cabetus.data.local.TimetableCellEntity
import org.cabetus.domain.ClassPhase
import org.cabetus.domain.ClassTimelineResolver
import org.cabetus.domain.PeriodTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ClassTimelineResolverTest {

    private fun cell(
        day: Int,
        period: Int,
        code: String = "C$day$period",
        name: String = "科目$period",
        room: String? = "E$period",
        isOnline: Boolean = false,
    ) = TimetableCellEntity(
        id = 0,
        dayOfWeek = day,
        period = period,
        courseCode = code,
        courseName = name,
        instructor = "",
        room = room,
        isOnline = isOnline,
        isMultiSession = false,
    )

    // 2026-07-06 は月曜日
    private val monday = 1

    @Test
    fun ongoing_class_reports_remaining_seconds() {
        // 1限 08:50-10:20 の 09:00 時点
        val now = LocalDateTime.of(2026, 7, 6, 9, 0)
        val timeline = ClassTimelineResolver.resolve(listOf(cell(monday, 1)), emptyMap(), now)
        val current = timeline.current!!
        assertEquals(1, current.period)
        assertEquals(ClassPhase.ONGOING, current.phase)
        // 10:20 まで 80 分 = 4800 秒
        assertEquals(4_800L, current.remainingSeconds)
        assertTrue(timeline.next == null)
    }

    @Test
    fun exactly_at_start_and_end_are_ongoing() {
        val cells = listOf(cell(monday, 1))
        val atStart = ClassTimelineResolver.resolve(
            cells, emptyMap(), LocalDateTime.of(2026, 7, 6, 8, 50),
        )
        assertEquals(ClassPhase.ONGOING, atStart.current?.phase)
        val atEnd = ClassTimelineResolver.resolve(
            cells, emptyMap(), LocalDateTime.of(2026, 7, 6, 10, 20),
        )
        assertEquals(ClassPhase.ONGOING, atEnd.current?.phase)
        assertEquals(0L, atEnd.current?.remainingSeconds)
    }

    @Test
    fun between_periods_has_no_current_but_next() {
        val cells = listOf(cell(monday, 1), cell(monday, 2))
        val now = LocalDateTime.of(2026, 7, 6, 10, 25)
        val timeline = ClassTimelineResolver.resolve(cells, emptyMap(), now)
        assertNull(timeline.current)
        assertEquals(2, timeline.next?.period)
        assertEquals(300L, ClassTimelineResolver.secondsUntil(timeline.next!!.start!!, now.toLocalTime()))
        assertEquals(ClassPhase.FINISHED, timeline.slots[0].phase)
        assertEquals(ClassPhase.UPCOMING, timeline.slots[1].phase)
    }

    @Test
    fun after_last_class_today_current_and_next_are_null() {
        val cells = listOf(cell(monday, 1))
        val now = LocalDateTime.of(2026, 7, 6, 22, 0)
        val timeline = ClassTimelineResolver.resolve(cells, emptyMap(), now)
        assertTrue(timeline.hasClassesToday)
        assertNull(timeline.current)
        assertNull(timeline.next)
        assertEquals(ClassPhase.FINISHED, timeline.slots.single().phase)
    }

    @Test
    fun no_classes_today_is_empty() {
        val now = LocalDateTime.of(2026, 7, 6, 9, 0)
        val timeline = ClassTimelineResolver.resolve(emptyList(), emptyMap(), now)
        assertFalse(timeline.hasClassesToday)
        assertTrue(timeline.slots.isEmpty())
        assertNull(timeline.current)
        assertNull(timeline.next)
    }

    @Test
    fun other_day_cells_are_ignored() {
        val cells = listOf(cell(2, 1), cell(3, 2))
        val timeline = ClassTimelineResolver.resolve(
            cells, emptyMap(), LocalDateTime.of(2026, 7, 6, 9, 0),
        )
        assertTrue(timeline.slots.isEmpty())
    }

    @Test
    fun course_name_prefers_course_master_and_falls_back_to_cell() {
        val cells = listOf(
            cell(monday, 1, code = "A", name = "セル名"),
            cell(monday, 2, code = "B", name = "セル名2"),
            cell(monday, 3, code = "C", name = ""),
        )
        val timeline = ClassTimelineResolver.resolve(
            cells,
            mapOf("A" to "正式名", "B" to ""),
            LocalDateTime.of(2026, 7, 6, 9, 0),
        )
        assertEquals("正式名", timeline.slots[0].name)
        assertEquals("セル名2", timeline.slots[1].name)
        assertEquals("C", timeline.slots[2].name)
    }

    @Test
    fun missing_room_and_online_are_kept_distinct() {
        val cells = listOf(
            cell(monday, 1, room = null),
            cell(monday, 2, room = null, isOnline = true),
            cell(monday, 3, room = "E501"),
        )
        val timeline = ClassTimelineResolver.resolve(
            cells, emptyMap(), LocalDateTime.of(2026, 7, 6, 9, 0),
        )
        assertNull(timeline.slots[0].room)
        assertFalse(timeline.slots[0].isOnline)
        assertTrue(timeline.slots[1].isOnline)
        assertEquals("E501", timeline.slots[2].room)
    }

    @Test
    fun custom_period_times_are_used() {
        val custom = listOf(
            org.cabetus.domain.PeriodTime(
                1,
                java.time.LocalTime.of(9, 0),
                java.time.LocalTime.of(10, 0),
            ),
        )
        val timeline = ClassTimelineResolver.resolve(
            listOf(cell(monday, 1)),
            emptyMap(),
            LocalDateTime.of(2026, 7, 6, 9, 30),
            custom,
        )
        assertEquals(1_800L, timeline.current?.remainingSeconds)
    }

    @Test
    fun period_without_definition_has_no_times_and_is_not_current() {
        val timeline = ClassTimelineResolver.resolve(
            listOf(cell(monday, 8)),
            emptyMap(),
            LocalDateTime.of(2026, 7, 6, 9, 0),
        )
        val slot = timeline.slots.single()
        assertNull(slot.start)
        assertEquals(ClassPhase.UPCOMING, slot.phase)
        assertNull(timeline.current)
        assertNull(timeline.next)
    }

    @Test
    fun default_period_times_match_documented_assumption() {
        val p1 = PeriodTimes.of(1)!!
        assertEquals(java.time.LocalTime.of(8, 50), p1.start)
        assertEquals(java.time.LocalTime.of(10, 20), p1.end)
        val p7 = PeriodTimes.of(7)!!
        assertEquals(java.time.LocalTime.of(19, 50), p7.start)
        assertEquals(java.time.LocalTime.of(21, 20), p7.end)
    }

    @Test
    fun formatRemaining_and_formatDuration() {
        assertEquals("残り1時間01分01秒", ClassTimelineResolver.formatRemaining(3661))
        assertEquals("残り12分34秒", ClassTimelineResolver.formatRemaining(754))
        assertEquals("残り9秒", ClassTimelineResolver.formatRemaining(9))
        assertEquals("残り0秒", ClassTimelineResolver.formatRemaining(-5))
        assertEquals("1時間00分00秒", ClassTimelineResolver.formatDuration(3600))
        assertEquals("0秒", ClassTimelineResolver.formatDuration(0))
    }
}
