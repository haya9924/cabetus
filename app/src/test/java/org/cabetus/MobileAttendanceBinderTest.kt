package org.cabetus

import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.CapturedDate
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.ConfirmationWindow
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.Observation
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.ObservedCourse
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.SlotLabel
import org.cabetus.data.classweb.MobileAttendanceBinder
import org.cabetus.data.local.TimetableCellEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 同じ時間割のコマに複数の画面ブロックが対応した場合の [MobileAttendanceBinder] の回帰テスト。
 *
 * confirmed/pending の優先や distinct による先勝ちで 1 件だけを採用せず、
 * そのコマの候補は両方除外して ambiguous として数えることを確認する。
 */
class MobileAttendanceBinderTest {

    // 2026-07-06 は月曜日。
    private val pageDate = CapturedDate(year = 2026, month = 7, day = 6, weekdayLabel = "月")

    private fun cell(
        courseCode: String = "C1",
        courseName: String = "数学",
        dayOfWeek: Int = 1,
        period: Int = 1,
    ) = TimetableCellEntity(
        dayOfWeek = dayOfWeek,
        period = period,
        courseCode = courseCode,
        courseName = courseName,
        instructor = "",
        room = null,
        isOnline = false,
        isMultiSession = false,
    )

    private fun confirmedCourse(
        courseName: String = "数学",
        dayOfWeek: Int = 1,
        period: Int = 1,
    ) = ObservedCourse(
        courseName = courseName,
        slot = SlotLabel(dayOfWeek, listOf(period), "月$period"),
        successLabelVisible = true,
        date = pageDate,
        confirmationWindow = ConfirmationWindow(LocalTime.of(10, 20), LocalTime.of(12, 0)),
        confirmationOpen = false,
        ambiguous = false,
    )

    private fun pendingCourse(
        courseName: String = "数学",
        dayOfWeek: Int = 1,
        period: Int = 1,
    ) = ObservedCourse(
        courseName = courseName,
        slot = SlotLabel(dayOfWeek, listOf(period), "月$period"),
        successLabelVisible = false,
        date = pageDate,
        confirmationWindow = ConfirmationWindow(LocalTime.of(10, 20), LocalTime.of(12, 0)),
        confirmationOpen = true,
        ambiguous = false,
    )

    private fun observation(vararg courses: ObservedCourse) = Observation(
        titleLabel = "モバイル出席登録",
        pageDate = pageDate,
        campusNetworkLabelVisible = false,
        courses = courses.toList(),
        warnings = emptyList(),
    )

    @Test
    fun singleBlocksAreBoundAsBefore() {
        val result = MobileAttendanceBinder.bind(
            observation(confirmedCourse(), pendingCourse(period = 2)),
            listOf(cell(), cell(period = 2)),
        )

        assertEquals(listOf("C1"), result.confirmed.map { it.courseCode })
        assertEquals(listOf("C1"), result.pending.map { it.courseCode })
        assertEquals(0, result.ambiguousCount)
        assertEquals(0, result.pendingAmbiguousCount)
        assertEquals(0, result.unboundCount)
        assertEquals(0, result.pendingUnboundCount)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun twoConfirmedBlocksOnSameSlotAreBothExcluded() {
        val result = MobileAttendanceBinder.bind(
            observation(confirmedCourse(), confirmedCourse()),
            listOf(cell()),
        )

        assertTrue(result.confirmed.isEmpty())
        assertTrue(result.pending.isEmpty())
        assertEquals(2, result.ambiguousCount)
        assertEquals(0, result.pendingAmbiguousCount)
        assertTrue(result.warnings.any { it.contains("複数") })
    }

    @Test
    fun confirmedAndPendingOnSameSlotAreBothExcluded() {
        val result = MobileAttendanceBinder.bind(
            observation(confirmedCourse(), pendingCourse()),
            listOf(cell()),
        )

        assertTrue(result.confirmed.isEmpty())
        assertTrue(result.pending.isEmpty())
        assertEquals(1, result.ambiguousCount)
        assertEquals(1, result.pendingAmbiguousCount)
        assertTrue(result.warnings.any { it.contains("複数") })
    }

    @Test
    fun pendingFirstThenConfirmedIsAlsoBothExcluded() {
        // 並び順に依存せず、先勝ちで 1 件だけ採用しないことを確認する。
        val result = MobileAttendanceBinder.bind(
            observation(pendingCourse(), confirmedCourse()),
            listOf(cell()),
        )

        assertTrue(result.confirmed.isEmpty())
        assertTrue(result.pending.isEmpty())
        assertEquals(1, result.ambiguousCount)
        assertEquals(1, result.pendingAmbiguousCount)
    }

    @Test
    fun twoPendingBlocksOnSameSlotAreBothExcluded() {
        val result = MobileAttendanceBinder.bind(
            observation(pendingCourse(), pendingCourse()),
            listOf(cell()),
        )

        assertTrue(result.confirmed.isEmpty())
        assertTrue(result.pending.isEmpty())
        assertEquals(0, result.ambiguousCount)
        assertEquals(2, result.pendingAmbiguousCount)
        assertTrue(result.warnings.any { it.contains("複数") })
    }

    @Test
    fun conflictingSlotDoesNotAffectOtherSlots() {
        val result = MobileAttendanceBinder.bind(
            observation(
                confirmedCourse(),
                confirmedCourse(),
                confirmedCourse(period = 2),
            ),
            listOf(cell(), cell(period = 2)),
        )

        // 競合した月1 は除外し、競合の無い月2 はそのまま採用する。
        assertTrue(result.confirmed.none { it.period == 1 })
        assertEquals(listOf(2), result.confirmed.map { it.period })
        assertTrue(result.pending.isEmpty())
        assertEquals(2, result.ambiguousCount)
    }
}
