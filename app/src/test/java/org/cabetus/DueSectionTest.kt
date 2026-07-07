package org.cabetus

import org.cabetus.ui.assignments.AssignmentsViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class DueSectionTest {

    private val now = 1_000_000_000_000L // 任意の基準時刻
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun nullDeadline_isNoDeadlineSection() {
        assertEquals("期限なし", AssignmentsViewModel.sectionLabel(null, now))
    }

    @Test
    fun pastDeadline_isOverdue() {
        assertEquals("期限切れ", AssignmentsViewModel.sectionLabel(now - 1, now))
    }

    @Test
    fun exactly24Hours_isWithin24h() {
        // 境界: ちょうど 24 時間後は「24時間以内」に含む。
        assertEquals("24時間以内", AssignmentsViewModel.sectionLabel(now + day, now))
    }

    @Test
    fun justOver24Hours_isWithin3Days() {
        assertEquals("3日以内", AssignmentsViewModel.sectionLabel(now + day + 1, now))
    }

    @Test
    fun exactly3Days_isWithin3Days() {
        assertEquals("3日以内", AssignmentsViewModel.sectionLabel(now + 3 * day, now))
    }

    @Test
    fun exactly7Days_isWithin7Days() {
        assertEquals("7日以内", AssignmentsViewModel.sectionLabel(now + 7 * day, now))
    }

    @Test
    fun beyond7Days_isLater() {
        assertEquals("それ以降", AssignmentsViewModel.sectionLabel(now + 7 * day + 1, now))
    }

    @Test
    fun nowExactly_isWithin24h() {
        // deadline == now は「期限切れ」ではなく「24時間以内」。
        assertEquals("24時間以内", AssignmentsViewModel.sectionLabel(now, now))
    }
}
