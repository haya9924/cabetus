package org.cabetus

import org.cabetus.data.classweb.MobileAttendanceRepository.CaptureResult
import org.cabetus.domain.ClassPhase
import org.cabetus.domain.ClassSlotState
import org.cabetus.domain.ClassTimeline
import org.cabetus.ui.browser.mobileAttendanceResultMessage
import org.cabetus.ui.home.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * ホームの自動モバイル出席確認の対象コマ判定と、結果メッセージの優先順位。
 *
 * [HomeViewModel.autoCheckTargetSlot] は画面・ブラウザ・時間割に依存しない純粋関数なので、
 * 実施中／開始15分前／境界（ちょうど15分）／対象外をそのまま検証できる。
 */
class HomeMobileAutoCheckTest {

    private val date = LocalDate.of(2026, 7, 6) // 月曜日

    private fun slot(
        period: Int,
        start: LocalTime,
        phase: ClassPhase,
        code: String = "C$period",
    ) = ClassSlotState(
        period = period,
        courseCode = code,
        name = "科目$period",
        room = null,
        isOnline = false,
        start = start,
        end = start.plusMinutes(90),
        phase = phase,
        remainingSeconds = if (phase == ClassPhase.ONGOING) 60L else null,
    )

    private fun timeline(
        now: LocalDateTime?,
        current: ClassSlotState? = null,
        next: ClassSlotState? = null,
    ) = ClassTimeline(
        date = date,
        now = now,
        slots = listOfNotNull(current, next),
        current = current,
        next = next,
    )

    @Test
    fun `cold start initial value has no target`() {
        val initial = timeline(now = null)
        assertNull(HomeViewModel.autoCheckTargetSlot(initial))
    }

    @Test
    fun `ongoing class is the target`() {
        val current = slot(2, LocalTime.of(10, 40), ClassPhase.ONGOING)
        val next = slot(3, LocalTime.of(13, 0), ClassPhase.UPCOMING)
        assertSame(
            current,
            HomeViewModel.autoCheckTargetSlot(timeline(LocalDateTime.of(2026, 7, 6, 11, 0), current, next)),
        )
    }

    @Test
    fun `pre-class window starts at exactly 15 minutes`() {
        val next = slot(3, LocalTime.of(13, 0), ClassPhase.UPCOMING)
        val atBoundary = timeline(LocalDateTime.of(2026, 7, 6, 12, 45), next = next) // 900 秒前
        assertEquals(900L, HomeViewModel.PRE_CLASS_WINDOW_SECONDS)
        assertSame(next, HomeViewModel.autoCheckTargetSlot(atBoundary))
    }

    @Test
    fun `next class inside the window is the target`() {
        val next = slot(3, LocalTime.of(13, 0), ClassPhase.UPCOMING)
        // 10 分前（CLASS の受付開始付近）も対象。
        assertSame(
            next,
            HomeViewModel.autoCheckTargetSlot(timeline(LocalDateTime.of(2026, 7, 6, 12, 50), next = next)),
        )
    }

    @Test
    fun `next class one second outside the window is not a target`() {
        val next = slot(3, LocalTime.of(13, 0), ClassPhase.UPCOMING)
        // 15 分 01 秒前は対象外。
        assertNull(HomeViewModel.autoCheckTargetSlot(timeline(LocalDateTime.of(2026, 7, 6, 12, 44, 59), next = next)))
    }

    @Test
    fun `no next class is not a target`() {
        assertNull(HomeViewModel.autoCheckTargetSlot(timeline(LocalDateTime.of(2026, 7, 6, 18, 0))))
    }

    private fun captureResult(saved: Boolean) = CaptureResult(
        saved = saved,
        confirmedCount = 0,
        pendingCount = 0,
        unboundCount = 0,
        ambiguousCount = 0,
        message = "",
    )

    @Test
    fun `auth required wins over the result`() {
        assertEquals(
            "自動接続を確認してください",
            mobileAttendanceResultMessage(captureResult(saved = true), authRequired = true, failed = false),
        )
    }

    @Test
    fun `failure is reported even without a result`() {
        assertEquals("取得できません", mobileAttendanceResultMessage(null, authRequired = false, failed = true))
    }

    @Test
    fun `unsaved result is reported separately from a read failure`() {
        assertEquals(
            "取得できません（読み取り結果を保持できませんでした）",
            mobileAttendanceResultMessage(captureResult(saved = false), authRequired = false, failed = false),
        )
    }
}
