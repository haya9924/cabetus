package org.cabetus

import org.cabetus.data.classweb.ConfirmedAttendanceRecord
import org.cabetus.data.classweb.MobileAttendanceSnapshot
import org.cabetus.data.classweb.PendingAttendanceRecord
import org.cabetus.data.network.NetworkTransport
import org.cabetus.domain.ClassTimelineResolver
import org.cabetus.domain.MobileAttendanceEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ホームの実施中授業ブロックに出す出席表示の判定。
 *
 * - PENDING は受付終了までの残り秒を1秒単位で出せる
 * - CONFIRMED はカウントダウンせず「出席完了」
 * - EXPIRED / STALE / NONE / NOT_APPLICABLE は「状況未確認」にまとめ、未提出や残り期限を推測しない
 * - 読み取り時刻（手動反映）の古さを明示する
 */
class CurrentClassAttendanceDisplayTest {

    // 2026-07-06 は月曜日。1限は 08:50〜10:20。
    private val now = LocalDateTime.of(2026, 7, 6, 9, 0)

    private fun epochOf(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()

    private fun snapshot(
        capturedAt: LocalDateTime = now,
        confirmed: List<ConfirmedAttendanceRecord> = emptyList(),
        pending: List<PendingAttendanceRecord> = emptyList(),
    ) = MobileAttendanceSnapshot(
        capturedAtEpochMs = epochOf(capturedAt),
        year = 2026,
        month = 7,
        day = 6,
        transport = NetworkTransport.WIFI.name,
        networkKnown = true,
        confirmed = confirmed,
        pending = pending,
    )

    private fun confirmedRecord() = ConfirmedAttendanceRecord(
        courseCode = "C1",
        courseName = "科目",
        dayOfWeek = 1,
        period = 1,
        year = 2026,
        month = 7,
        day = 6,
    )

    private fun pendingRecord(
        startMinutes: Int = 9 * 60,
        endMinutes: Int = 10 * 60,
    ) = PendingAttendanceRecord(
        courseCode = "C1",
        courseName = "科目",
        dayOfWeek = 1,
        period = 1,
        year = 2026,
        month = 7,
        day = 6,
        windowStartMinutes = startMinutes,
        windowEndMinutes = endMinutes,
    )

    private fun evaluate(
        snapshot: MobileAttendanceSnapshot?,
        at: LocalDateTime = now,
    ) = MobileAttendanceEvidence.evaluate(
        snapshot = snapshot,
        now = at,
        dayOfWeek = 1,
        period = 1,
        courseCode = "C1",
        currentTransport = NetworkTransport.WIFI,
        currentNetworkKnown = true,
    )

    @Test
    fun pending_shows_remaining_seconds_until_attendance_window_end() {
        val display = evaluate(snapshot(pending = listOf(pendingRecord())))

        assertEquals(MobileAttendanceEvidence.Status.PENDING, display.status)
        assertTrue(display.isPending)
        assertEquals(3_600L, display.remainingSeconds)
        assertEquals("09:00～10:00", display.windowLabel)
        assertEquals(MobileAttendanceEvidence.PENDING_LABEL, MobileAttendanceEvidence.statusLabel(display))
        assertNull(display.reason)
    }

    @Test
    fun pending_remaining_seconds_decrease_every_second_without_changing_status() {
        val first = evaluate(snapshot(pending = listOf(pendingRecord())), at = now)
        val next = evaluate(
            snapshot(pending = listOf(pendingRecord())),
            at = now.plusSeconds(1),
        )

        assertEquals(MobileAttendanceEvidence.Status.PENDING, first.status)
        assertEquals(MobileAttendanceEvidence.Status.PENDING, next.status)
        assertEquals(3_600L, first.remainingSeconds)
        assertEquals(3_599L, next.remainingSeconds)
        // 残り時間の表示形式（1秒単位）と、状態の見出しが変わらないこと。
        assertEquals(
            "1時間00分00秒",
            ClassTimelineResolver.formatDuration(first.remainingSeconds!!),
        )
        assertEquals(
            "59分59秒",
            ClassTimelineResolver.formatDuration(next.remainingSeconds!!),
        )
        assertEquals(
            MobileAttendanceEvidence.statusLabel(first),
            MobileAttendanceEvidence.statusLabel(next),
        )
    }

    @Test
    fun attendance_window_end_is_zero_remaining_and_still_pending() {
        // 読み取り直後（失効しない）に受付終了ちょうどを迎えた場合。
        val at = LocalDateTime.of(2026, 7, 6, 10, 0)
        val display = evaluate(
            snapshot(capturedAt = at, pending = listOf(pendingRecord())),
            at = at,
        )

        assertEquals(MobileAttendanceEvidence.Status.PENDING, display.status)
        assertEquals(0L, display.remainingSeconds)
    }

    @Test
    fun expired_window_is_unknown_and_has_no_countdown() {
        // 読み取り直後（失効しない）に受付終了を過ぎた場合。
        val at = LocalDateTime.of(2026, 7, 6, 10, 1)
        val display = evaluate(
            snapshot(capturedAt = at, pending = listOf(pendingRecord())),
            at = at,
        )

        assertEquals(MobileAttendanceEvidence.Status.EXPIRED, display.status)
        assertFalse(display.isPending)
        assertFalse(display.isConfirmed)
        assertNull(display.remainingSeconds)
        assertEquals(
            MobileAttendanceEvidence.STATUS_UNKNOWN_LABEL,
            MobileAttendanceEvidence.statusLabel(display),
        )
        // 受付時間の事実だけを示し、「未提出」や残り期限は推測しない。
        assertTrue(display.reason!!.contains("09:00～10:00"))
        assertTrue(display.reason!!.contains("過ぎています"))
    }

    @Test
    fun confirmed_has_no_countdown_and_stays_confirmed_as_time_passes() {
        val atCapture = evaluate(
            snapshot(confirmed = listOf(confirmedRecord())),
            at = now,
        )
        val later = evaluate(
            snapshot(confirmed = listOf(confirmedRecord())),
            at = now.plusSeconds(90),
        )

        assertEquals(MobileAttendanceEvidence.Status.CONFIRMED, atCapture.status)
        assertEquals(MobileAttendanceEvidence.Status.CONFIRMED, later.status)
        assertNull(atCapture.remainingSeconds)
        assertNull(later.remainingSeconds)
        assertEquals("出席完了", MobileAttendanceEvidence.statusLabel(atCapture))
        assertEquals("出席完了", MobileAttendanceEvidence.statusLabel(later))
    }

    @Test
    fun no_reading_is_unknown_and_does_not_guess_absence() {
        val display = evaluate(null)

        assertEquals(MobileAttendanceEvidence.Status.NONE, display.status)
        assertEquals(
            MobileAttendanceEvidence.STATUS_UNKNOWN_LABEL,
            MobileAttendanceEvidence.statusLabel(display),
        )
        assertNull(display.remainingSeconds)
        assertNull(display.capturedAt)
        assertNull(MobileAttendanceEvidence.freshnessLabel(display))
    }

    @Test
    fun stale_reading_is_unknown_and_does_not_guess_absence() {
        val display = evaluate(snapshot(capturedAt = now.minusMinutes(16)))

        assertEquals(MobileAttendanceEvidence.Status.STALE, display.status)
        assertEquals(
            MobileAttendanceEvidence.STATUS_UNKNOWN_LABEL,
            MobileAttendanceEvidence.statusLabel(display),
        )
        assertNull(display.remainingSeconds)
        // 手動反映の古さは失効していても明示する。
        assertEquals(
            "手動反映: 08:44 の読み取り（16分00秒前）",
            MobileAttendanceEvidence.freshnessLabel(display),
        )
    }

    @Test
    fun not_applicable_course_is_unknown() {
        val display = MobileAttendanceEvidence.evaluate(
            snapshot = snapshot(confirmed = listOf(confirmedRecord())),
            now = now,
            dayOfWeek = 1,
            period = 2,
            courseCode = "C2",
            currentTransport = NetworkTransport.WIFI,
            currentNetworkKnown = true,
        )

        assertEquals(MobileAttendanceEvidence.Status.NOT_APPLICABLE, display.status)
        assertEquals(
            MobileAttendanceEvidence.STATUS_UNKNOWN_LABEL,
            MobileAttendanceEvidence.statusLabel(display),
        )
        assertNull(display.remainingSeconds)
    }

    @Test
    fun freshness_label_shows_capture_time_and_age() {
        val display = evaluate(
            snapshot(capturedAt = now.minusSeconds(12), pending = listOf(pendingRecord())),
        )

        assertEquals(12L, display.ageSeconds)
        assertEquals(
            "手動反映: 08:59 の読み取り（12秒前）",
            MobileAttendanceEvidence.freshnessLabel(display),
        )
    }

    @Test
    fun freshness_label_without_age_shows_capture_time_only() {
        val display = MobileAttendanceEvidence.Display(
            status = MobileAttendanceEvidence.Status.CONFIRMED,
            capturedAt = LocalDateTime.of(2026, 7, 6, 9, 5),
        )

        assertEquals(
            "手動反映: 09:05 の読み取り",
            MobileAttendanceEvidence.freshnessLabel(display),
        )
    }

    @Test
    fun attendance_remaining_is_distinct_from_class_remaining() {
        // 授業は 10:20 まで（残り4,800秒）、出席確認は 10:00 まで（残り3,600秒）。
        val display = evaluate(snapshot(pending = listOf(pendingRecord())))
        val classRemaining = ClassTimelineResolver.remainingSeconds(
            java.time.LocalTime.of(10, 20),
            now.toLocalTime(),
        )

        assertEquals(3_600L, display.remainingSeconds)
        assertEquals(4_800L, classRemaining)
        assertNotEquals(display.remainingSeconds, classRemaining)
        // 出席確認の残りは「出席確認の終了まで」として表示する（授業の残りとは別）。
        assertEquals(
            "出席確認の終了まで 1時間00分00秒",
            "出席確認の終了まで " +
                ClassTimelineResolver.formatDuration(display.remainingSeconds!!),
        )
    }

    @Test
    fun manual_read_note_explains_no_auto_refresh_and_separate_deadline() {
        val note = MobileAttendanceEvidence.MANUAL_READ_NOTE

        assertTrue(note.contains("手動"))
        assertTrue(note.contains("自動更新"))
        assertTrue(note.contains("状況未確認"))
        assertTrue(note.contains("授業自体の残り時間"))
        assertTrue(MobileAttendanceEvidence.PENDING_NOTE.contains("推測しません"))
    }
}
