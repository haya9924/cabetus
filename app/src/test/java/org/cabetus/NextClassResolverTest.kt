package org.cabetus

import org.cabetus.data.local.TimetableCellEntity
import org.cabetus.domain.NextClassResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NextClassResolverTest {

    private fun cell(day: Int, period: Int, name: String = "科目$period", room: String? = "E$period") =
        TimetableCellEntity(
            id = 0,
            dayOfWeek = day,
            period = period,
            courseCode = "C$day$period",
            courseName = name,
            instructor = "",
            room = room,
            isOnline = false,
            isMultiSession = false,
        )

    // 2026-07-06 は月曜日
    private val monday = 1

    @Test
    fun ongoing_isPreferred() {
        // 月曜 1限(08:50-10:20)中の 09:30
        val cells = listOf(cell(monday, 1), cell(monday, 3))
        val now = LocalDateTime.of(2026, 7, 6, 9, 30)
        val info = NextClassResolver.resolve(cells, now)!!
        assertEquals(1, info.period)
        assertTrue(info.isOngoing)
    }

    @Test
    fun betweenPeriods_returnsNextUpcomingToday() {
        // 1限終了(10:20)後・2限開始(10:30)前の 10:25。次は同日の後続コマ。
        val cells = listOf(cell(monday, 1), cell(monday, 3))
        val now = LocalDateTime.of(2026, 7, 6, 10, 25)
        val info = NextClassResolver.resolve(cells, now)!!
        assertEquals(3, info.period)
        assertFalse(info.isOngoing)
    }

    @Test
    fun afterLastClassToday_rollsToNextDay() {
        // 月曜の全授業(1限のみ)が終わった夜。次は火曜の授業。
        val cells = listOf(cell(monday, 1), cell(2, 2, name = "火曜2限"))
        val now = LocalDateTime.of(2026, 7, 6, 22, 0)
        val info = NextClassResolver.resolve(cells, now)!!
        assertEquals(2, info.dayOfWeek)
        assertEquals(2, info.period)
        assertFalse(info.isOngoing)
    }

    @Test
    fun noClasses_returnsNull() {
        val now = LocalDateTime.of(2026, 7, 6, 9, 0)
        assertNull(NextClassResolver.resolve(emptyList(), now))
    }

    @Test
    fun sunday_hasNoClassesButFindsMonday() {
        // 2026-07-12 は日曜。授業は月曜のみ→翌日の月曜が返る。
        val cells = listOf(cell(monday, 2))
        val now = LocalDateTime.of(2026, 7, 12, 12, 0)
        val info = NextClassResolver.resolve(cells, now)!!
        assertEquals(monday, info.dayOfWeek)
        assertEquals(2, info.period)
    }
}
