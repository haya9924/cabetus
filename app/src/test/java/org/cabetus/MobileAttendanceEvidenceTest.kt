package org.cabetus

import org.cabetus.data.classweb.ConfirmedAttendanceRecord
import org.cabetus.data.classweb.MobileAttendanceCodec
import org.cabetus.data.classweb.MobileAttendancePageCourse
import org.cabetus.data.classweb.MobileAttendanceSnapshot
import org.cabetus.data.network.NetworkTransport
import org.cabetus.domain.MobileAttendanceEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MobileAttendanceEvidenceTest {

    // 2026-07-06 は月曜日。1限は 08:50〜10:20。時刻はすべて日本時間。
    private val now = LocalDateTime.of(2026, 7, 6, 9, 0)

    private fun epochOf(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()

    private fun snapshot(
        capturedAt: LocalDateTime = now,
        capturedAtEpochMs: Long? = null,
        transport: String? = NetworkTransport.WIFI.name,
        networkKnown: Boolean = true,
        campusLabel: Boolean = false,
        confirmed: List<ConfirmedAttendanceRecord> = listOf(record()),
        year: Int? = 2026,
        month: Int = 7,
        day: Int = 6,
    ) = MobileAttendanceSnapshot(
        capturedAtEpochMs = capturedAtEpochMs ?: epochOf(capturedAt),
        year = year,
        month = month,
        day = day,
        transport = transport,
        networkKnown = networkKnown,
        campusNetworkLabelVisible = campusLabel,
        confirmed = confirmed,
    )

    private fun record(
        courseCode: String = "C1",
        dayOfWeek: Int = 1,
        period: Int = 1,
    ) = ConfirmedAttendanceRecord(
        courseCode = courseCode,
        courseName = "科目",
        dayOfWeek = dayOfWeek,
        period = period,
        year = 2026,
        month = 7,
        day = 6,
    )

    private fun evaluate(
        snapshot: MobileAttendanceSnapshot?,
        at: LocalDateTime = now,
        period: Int = 1,
        courseCode: String = "C1",
        transport: NetworkTransport? = NetworkTransport.WIFI,
        known: Boolean = true,
    ) = MobileAttendanceEvidence.evaluate(
        snapshot = snapshot,
        now = at,
        dayOfWeek = 1,
        period = period,
        courseCode = courseCode,
        currentTransport = transport,
        currentNetworkKnown = known,
    )

    private fun pagePanel(
        snapshot: MobileAttendanceSnapshot?,
        at: LocalDateTime = now,
        transport: NetworkTransport? = NetworkTransport.WIFI,
        known: Boolean = true,
        login: MobileAttendanceEvidence.LoginState = MobileAttendanceEvidence.LoginState.UNKNOWN,
    ) = MobileAttendanceEvidence.evaluatePage(snapshot, at, transport, known, login)

    @Test
    fun panelDisplaysClassNamesAndDoesNotFallBackToTimetableRecords() {
        assertTrue(pagePanel(snapshot()).isEmpty())
        val pageCourses = listOf(
            MobileAttendancePageCourse("ＣＬＡＳＳの科目", confirmed = true),
            MobileAttendancePageCourse("別の出席確認", confirmed = false, 535, 545),
            MobileAttendancePageCourse("科目名だけ確認できた授業", confirmed = false),
        )
        for (confirmed in listOf(emptyList(), listOf(record().copy(courseName = "時間割だけの科目")))) {
            val read = snapshot(confirmed = confirmed).copy(pageCourses = pageCourses)
            val roundTrip = MobileAttendanceCodec.decode(MobileAttendanceCodec.encode(read))
            assertEquals(read, roundTrip)
            val rows = pagePanel(roundTrip)
            assertEquals(pageCourses.map { it.courseName }, rows.map { it.courseName })
            assertTrue(rows[0].attendance.isConfirmed)
            assertEquals(null, rows[0].attendance.windowLabel)
            assertTrue(rows[1].attendance.isPending)
            assertEquals(300L, rows[1].attendance.remainingSeconds)
            assertEquals(MobileAttendanceEvidence.Status.NOT_APPLICABLE, rows[2].attendance.status)
            assertFalse(rows.any { it.attendance.inferred })
        }
    }

    @Test
    fun panelOnlyShowsPendingDuringItsOwnWindow() {
        val read = snapshot(capturedAt = now.minusMinutes(1)).copy(pageCourses = listOf(
            MobileAttendancePageCourse("受付確認", confirmed = false, 540, 545),
        ))
        val before = pagePanel(read, now.minusSeconds(1)).single()
        assertEquals("受付確認", before.courseName)
        assertFalse(before.attendance.isPending)
        assertEquals(null, before.attendance.remainingSeconds)
        assertEquals(300L, pagePanel(read).single().attendance.remainingSeconds)
        assertEquals(0L, pagePanel(read, now.plusMinutes(5)).single().attendance.remainingSeconds)
        val after = pagePanel(read, now.plusMinutes(5).plusSeconds(1)).single()
        assertEquals("受付確認", after.courseName)
        assertFalse(after.attendance.isPending)
        assertEquals(null, after.attendance.remainingSeconds)
    }

    @Test
    fun panelRejectsExpiredWrongDateOrUntrustworthyCaptures() {
        val read = snapshot().copy(pageCourses = listOf(MobileAttendancePageCourse("CLASS科目", confirmed = true)))
        assertEquals(1, pagePanel(read, now.plusMinutes(15).minusSeconds(1)).size)
        assertTrue(pagePanel(read, now.plusMinutes(15)).isEmpty())
        assertTrue(pagePanel(read, now.minusSeconds(1)).isEmpty())
        assertTrue(pagePanel(read.copy(day = 5)).isEmpty())
        assertTrue(pagePanel(read.copy(year = null, weekdayLabel = "火")).isEmpty())
        assertEquals(1, pagePanel(read.copy(year = null, weekdayLabel = "月")).size)
        assertTrue(pagePanel(read.copy(networkKnown = false)).isEmpty())
        assertTrue(pagePanel(read, known = false).isEmpty())
        assertTrue(pagePanel(read, transport = NetworkTransport.CELLULAR).isEmpty())
        assertTrue(pagePanel(read, login = MobileAttendanceEvidence.LoginState.LOGGED_OUT).isEmpty())
        assertTrue(pagePanel(null).isEmpty())
    }

    @Test
    fun no_snapshot_is_none() {
        val display = evaluate(null)
        assertEquals(MobileAttendanceEvidence.Status.NONE, display.status)
        assertFalse(display.isConfirmed)
    }

    @Test
    fun matching_snapshot_on_same_network_is_confirmed() {
        val display = evaluate(snapshot())
        assertEquals(MobileAttendanceEvidence.Status.CONFIRMED, display.status)
        assertTrue(display.isConfirmed)
        assertEquals(null, display.reason)
        assertEquals(NetworkTransport.WIFI, display.transport)
    }

    @Test
    fun campus_label_is_reported_only_when_site_showed_it() {
        assertFalse(evaluate(snapshot(campusLabel = false)).campusNetworkLabelVisible)
        assertEquals(
            "学内/学外: 未確認",
            MobileAttendanceEvidence.campusScopeLabel(evaluate(snapshot(campusLabel = false))),
        )

        val labelled = evaluate(snapshot(campusLabel = true))
        assertTrue(labelled.campusNetworkLabelVisible)
        assertEquals(
            "学内/学外: 学内（CLASS の表示）",
            MobileAttendanceEvidence.campusScopeLabel(labelled),
        )
    }

    @Test
    fun old_snapshot_is_stale() {
        val display = evaluate(snapshot(capturedAt = now.minusMinutes(16)))
        assertEquals(MobileAttendanceEvidence.Status.STALE, display.status)
        assertFalse(display.isConfirmed)
        assertEquals("学内/学外: 未確認（読み取りが失効）", MobileAttendanceEvidence.campusScopeLabel(display))
    }

    @Test
    fun snapshot_at_max_age_is_stale() {
        val display = evaluate(snapshot(capturedAt = now.minusMinutes(15)))
        assertEquals(MobileAttendanceEvidence.Status.STALE, display.status)
    }

    @Test
    fun fresh_capture_across_jst_midnight_is_converted_in_jst() {
        // 2026-07-06 00:30 JST = 2026-07-05 15:30 UTC。UTC ではまだ前日だが、
        // JST では当日の読み取りなので失効しない。
        val utcEpochMs = Instant.parse("2026-07-05T15:30:00Z").toEpochMilli()
        val display = evaluate(
            snapshot = snapshot(capturedAtEpochMs = utcEpochMs, day = 6),
            at = LocalDateTime.of(2026, 7, 6, 0, 40),
        )
        assertEquals(MobileAttendanceEvidence.Status.CONFIRMED, display.status)
        assertEquals(LocalDateTime.of(2026, 7, 6, 0, 30), display.capturedAt)
        assertEquals(600L, display.ageSeconds)
        assertEquals("手動反映: 00:30 の読み取り（10分00秒前）", MobileAttendanceEvidence.freshnessLabel(display))
    }

    @Test
    fun other_date_is_stale() {
        val display = evaluate(snapshot(capturedAt = now, day = 5))
        assertEquals(MobileAttendanceEvidence.Status.STALE, display.status)
    }

    @Test
    fun missing_year_compares_month_and_day_only() {
        val display = evaluate(snapshot(year = null))
        assertEquals(MobileAttendanceEvidence.Status.CONFIRMED, display.status)
    }

    @Test
    fun network_change_is_stale() {
        assertEquals(
            MobileAttendanceEvidence.Status.STALE,
            evaluate(snapshot(), transport = NetworkTransport.CELLULAR).status,
        )
    }

    @Test
    fun unknown_network_is_stale() {
        assertEquals(
            MobileAttendanceEvidence.Status.STALE,
            evaluate(snapshot(), known = false, transport = null).status,
        )
        assertEquals(
            MobileAttendanceEvidence.Status.STALE,
            evaluate(snapshot(networkKnown = false, transport = null)).status,
        )
    }

    @Test
    fun finished_period_is_stale() {
        val display = evaluate(snapshot(), at = LocalDate.of(2026, 7, 6).atTime(10, 21))
        assertEquals(MobileAttendanceEvidence.Status.STALE, display.status)
    }

    @Test
    fun different_course_is_not_applicable() {
        val display = evaluate(snapshot(), courseCode = "OTHER")
        assertEquals(MobileAttendanceEvidence.Status.NOT_APPLICABLE, display.status)
        assertFalse(display.isConfirmed)
    }

    @Test
    fun format_age_is_human_readable() {
        assertEquals("12秒", MobileAttendanceEvidence.formatAge(12))
        assertEquals("2分05秒", MobileAttendanceEvidence.formatAge(125))
        assertEquals("1時間01分", MobileAttendanceEvidence.formatAge(3_660))
    }
}
