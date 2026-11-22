package org.cabetus

import org.cabetus.data.classweb.ConfirmedAttendanceRecord
import org.cabetus.data.classweb.MobileAttendanceConfirmationPlanner
import org.cabetus.data.classweb.MobileAttendanceSnapshot
import org.cabetus.data.classweb.PendingAttendanceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MobileAttendanceConfirmationPlanner] のテスト。
 *
 * 日付・曜日・時限を推測なしで確定できる「出席」だけを保存行に変換し、
 * 未提出（pending）は扱わないことを確認する。
 */
class MobileAttendanceConfirmationPlannerTest {

    // 2026-07-06 は月曜日。
    private val capturedAt = 1_800_000_000_000L
    private val day = LocalDate.of(2026, 7, 6)
    private val dayEpochMs = day.atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()

    private fun record(
        courseCode: String = "C1",
        dayOfWeek: Int = 1,
        period: Int = 1,
        year: Int? = 2026,
        inferred: Boolean = false,
        inferenceBasis: String? = null,
    ) = ConfirmedAttendanceRecord(
        courseCode = courseCode,
        courseName = "数学",
        dayOfWeek = dayOfWeek,
        period = period,
        year = year,
        month = 7,
        day = 6,
        inferred = inferred,
        inferenceBasis = inferenceBasis,
    )

    private fun snapshot(
        year: Int? = 2026,
        weekdayLabel: String? = "月",
        confirmed: List<ConfirmedAttendanceRecord> = emptyList(),
        pending: List<PendingAttendanceRecord> = emptyList(),
    ) = MobileAttendanceSnapshot(
        capturedAtEpochMs = capturedAt,
        year = year,
        month = 7,
        day = 6,
        weekdayLabel = weekdayLabel,
        confirmed = confirmed,
        pending = pending,
    )

    @Test
    fun `explicit record becomes one row`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(snapshot(confirmed = listOf(record())))

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("C1", row.courseCode)
        assertEquals(dayEpochMs, row.date)
        assertEquals(1, row.period)
        assertEquals(capturedAt, row.confirmedAt)
        assertEquals(false, row.inferred)
        assertEquals(null, row.inferenceBasis)
    }

    @Test
    fun `inferred record keeps its basis`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(
                confirmed = listOf(record(inferred = true, inferenceBasis = "科目名一致・受付時間重なり")),
            ),
        )

        val row = rows.single()
        assertEquals(true, row.inferred)
        assertEquals("科目名一致・受付時間重なり", row.inferenceBasis)
    }

    @Test
    fun `inferred record without basis is rejected`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(confirmed = listOf(record(inferred = true, inferenceBasis = " "))),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `yearless snapshot is not guessed`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(year = null, confirmed = listOf(record())),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `yearless record is not guessed`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(confirmed = listOf(record(year = null))),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `invalid calendar date is rejected`() {
        val snapshot = MobileAttendanceSnapshot(
            capturedAtEpochMs = capturedAt,
            year = 2026,
            month = 2,
            day = 29,
            confirmed = listOf(
                ConfirmedAttendanceRecord(
                    courseCode = "C1",
                    courseName = "数学",
                    dayOfWeek = 7,
                    period = 1,
                    year = 2026,
                    month = 2,
                    day = 29,
                ),
            ),
        )

        assertTrue(MobileAttendanceConfirmationPlanner.plan(snapshot).isEmpty())
    }

    @Test
    fun `record weekday must match real calendar`() {
        // 2026-07-06 は月曜日。火曜日として渡されたレコードは捨てる。
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(confirmed = listOf(record(courseCode = "C1", dayOfWeek = 2))),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `snapshot weekday label must match real calendar`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(weekdayLabel = "火", confirmed = listOf(record())),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `unknown snapshot weekday label is rejected`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(weekdayLabel = "Mon", confirmed = listOf(record())),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `record disagreeing with snapshot page is dropped`() {
        val otherYear = record(courseCode = "C1", period = 2).copy(year = 2025)
        val otherDay = record(courseCode = "C2", period = 3).copy(day = 7)
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(
                confirmed = listOf(
                    otherYear,
                    otherDay,
                    record(courseCode = "C3", period = 4),
                ),
            ),
        )

        assertEquals(listOf("C3"), rows.map { it.courseCode })
    }

    @Test
    fun `unsupported period is rejected`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(
                confirmed = listOf(
                    record(courseCode = "C1", period = 0),
                    record(courseCode = "C2", period = 8),
                ),
            ),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `blank course code is rejected`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(confirmed = listOf(record(courseCode = "  "))),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `duplicate code date period keeps single row`() {
        val rows = MobileAttendanceConfirmationPlanner.plan(
            snapshot(
                confirmed = listOf(
                    record(courseCode = "C1", period = 1),
                    record(courseCode = "C1", period = 1),
                    record(courseCode = "C1", period = 2),
                ),
            ),
        )

        assertEquals(2, rows.size)
        assertEquals(listOf(1, 2), rows.map { it.period })
    }

    @Test
    fun `pending records are ignored entirely`() {
        val pending = PendingAttendanceRecord(
            courseCode = "C1",
            courseName = "数学",
            dayOfWeek = 1,
            period = 1,
            year = 2026,
            month = 7,
            day = 6,
            windowStartMinutes = 10 * 60 + 20,
            windowEndMinutes = 12 * 60,
        )

        val rows = MobileAttendanceConfirmationPlanner.plan(snapshot(pending = listOf(pending)))

        assertTrue(rows.isEmpty())
    }
}
