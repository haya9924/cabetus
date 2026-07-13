package org.cabetus

import org.cabetus.domain.AttendanceResolver
import org.cabetus.domain.AttendanceSource
import org.cabetus.domain.AttendanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttendanceResolverTest {

    @Test
    fun fromMark_maps_all_known_marks() {
        assertEquals(AttendanceStatus.PRESENT, AttendanceResolver.fromMark("〇"))
        assertEquals(AttendanceStatus.PRESENT, AttendanceResolver.fromMark("○"))
        assertEquals(AttendanceStatus.PRESENT, AttendanceResolver.fromMark("◯"))
        assertEquals(AttendanceStatus.LATE, AttendanceResolver.fromMark("▽"))
        assertEquals(AttendanceStatus.LATE, AttendanceResolver.fromMark("△"))
        assertEquals(AttendanceStatus.ABSENT, AttendanceResolver.fromMark("×"))
        assertEquals(AttendanceStatus.ABSENT, AttendanceResolver.fromMark("✕"))
        assertEquals(AttendanceStatus.HOLIDAY, AttendanceResolver.fromMark("公"))
    }

    @Test
    fun fromMark_null_or_unknown_is_unrecorded() {
        assertEquals(AttendanceStatus.UNRECORDED, AttendanceResolver.fromMark(null))
        assertEquals(AttendanceStatus.UNRECORDED, AttendanceResolver.fromMark(""))
        assertEquals(AttendanceStatus.UNRECORDED, AttendanceResolver.fromMark("外"))
    }

    @Test
    fun resolve_manual_override_wins() {
        // 手動修正はPDFマーク・アプリ記録より優先される
        val r = AttendanceResolver.resolve(
            mark = "〇",
            hasLocalCheck = true,
            overrideStatus = AttendanceStatus.ABSENT,
        )
        assertEquals(AttendanceStatus.ABSENT, r.status)
        assertEquals(AttendanceSource.MANUAL, r.source)
    }

    @Test
    fun resolve_local_check_beats_mark() {
        val r = AttendanceResolver.resolve(
            mark = "×",
            hasLocalCheck = true,
            overrideStatus = null,
        )
        assertEquals(AttendanceStatus.PRESENT, r.status)
        assertEquals(AttendanceSource.LOCAL, r.source)
    }

    @Test
    fun resolve_falls_back_to_mark() {
        val r = AttendanceResolver.resolve(mark = "×", hasLocalCheck = false, overrideStatus = null)
        assertEquals(AttendanceStatus.ABSENT, r.status)
        assertEquals(AttendanceSource.PDF, r.source)
    }

    @Test
    fun resolve_no_data_is_unrecorded_none() {
        val r = AttendanceResolver.resolve(mark = null, hasLocalCheck = false, overrideStatus = null)
        assertEquals(AttendanceStatus.UNRECORDED, r.status)
        assertEquals(AttendanceSource.NONE, r.source)
    }

    @Test
    fun summarize_excludes_holiday_and_unrecorded_from_denominator() {
        val statuses = listOf(
            AttendanceStatus.PRESENT,
            AttendanceStatus.PRESENT,
            AttendanceStatus.ABSENT,
            AttendanceStatus.LATE,
            AttendanceStatus.HOLIDAY,      // 分母に入らない
            AttendanceStatus.UNRECORDED,   // 分母に入らない
        )
        val s = AttendanceResolver.summarize(statuses)
        assertEquals(4, s.held)            // PRESENT×2 + ABSENT + LATE（公休・未記録は除外）
        assertEquals(2, s.present)
        assertEquals(50, s.ratePercent)   // 2/4
    }

    @Test
    fun summarize_counts_late_in_denominator_but_not_present() {
        // 遅刻早退は実施回に数えるが出席には数えない
        val statuses = listOf(AttendanceStatus.PRESENT, AttendanceStatus.LATE)
        val s = AttendanceResolver.summarize(statuses)
        assertEquals(2, s.held)
        assertEquals(1, s.present)
        assertEquals(50, s.ratePercent)
    }

    @Test
    fun summarize_empty_or_all_holiday_has_null_rate() {
        assertNull(AttendanceResolver.summarize(emptyList()).ratePercent)
        assertNull(
            AttendanceResolver.summarize(
                listOf(AttendanceStatus.HOLIDAY, AttendanceStatus.UNRECORDED),
            ).ratePercent,
        )
    }
}
