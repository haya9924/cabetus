package org.cabetus

import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.CapturedDate
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.ConfirmationWindow
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.Observation
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.ObservedCourse
import org.cabetus.data.classweb.ConfirmedAttendanceRecord
import org.cabetus.data.classweb.MobileAttendanceBinder
import org.cabetus.data.classweb.MobileAttendanceCodec
import org.cabetus.data.classweb.MobileAttendanceSlotInference
import org.cabetus.data.classweb.MobileAttendanceSnapshot
import org.cabetus.data.classweb.PendingAttendanceRecord
import org.cabetus.data.local.TimetableCellEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 画面に曜日時限が無い場合の [MobileAttendanceSlotInference] の合成テスト。
 *
 * 根拠を「ページの年あり日付の曜日」「正規化した科目名の完全一致」
 * 「受付時間と時限時刻の重なり」だけに限定し、一意に決まらない場合は
 * 保留すること（直近・先頭を選ばないこと）を確認する。
 */
class MobileAttendanceSlotInferenceTest {

    // 2026-07-06 は月曜日。2限は 10:30〜12:00。
    private val pageDate = CapturedDate(year = 2026, month = 7, day = 6, weekdayLabel = "月")

    private fun cell(
        courseCode: String = "C1",
        courseName: String = "数学",
        dayOfWeek: Int = 1,
        period: Int = 2,
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

    private fun window(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) =
        ConfirmationWindow(
            LocalTime.of(startHour, startMinute),
            LocalTime.of(endHour, endMinute),
        )

    private fun infer(
        page: CapturedDate? = pageDate,
        name: String? = "数学",
        window: ConfirmationWindow? = window(10, 20, 12, 0),
        cells: List<TimetableCellEntity> = listOf(cell()),
        nameByCode: Map<String, String> = emptyMap(),
    ) = MobileAttendanceSlotInference.infer(page, name, window, cells, nameByCode)

    private fun heldReason(outcome: MobileAttendanceSlotInference.Outcome) =
        (outcome as MobileAttendanceSlotInference.Outcome.Held).reason

    @Test
    fun adoptsOnlyWhenWeekdayNameAndWindowAgree() {
        val outcome = infer()

        assertTrue(outcome is MobileAttendanceSlotInference.Outcome.Adopted)
        val adopted = outcome as MobileAttendanceSlotInference.Outcome.Adopted
        assertEquals("C1", adopted.cell.courseCode)
        assertEquals(2, adopted.cell.period)
        assertTrue(adopted.basis.contains("2026/7/6"))
        assertTrue(adopted.basis.contains("数学"))
        assertTrue(adopted.basis.contains("10:20"))
    }

    @Test
    fun nameComparisonUsesNormalizedFullMatchOnly() {
        // 空白（全角含む）の畳み込みだけを行い、部分一致・表記ゆれの補正はしない。
        val spaced = infer(name = "　数学　", cells = listOf(cell(courseName = "数学")))
        assertTrue(spaced is MobileAttendanceSlotInference.Outcome.Adopted)

        val partial = infer(name = "数学演習", cells = listOf(cell(courseName = "数学")))
        assertEquals(
            MobileAttendanceSlotInference.Reason.NO_CANDIDATE,
            heldReason(partial),
        )
    }

    @Test
    fun courseNameIsResolvedThroughCourseCodeMap() {
        val outcome = infer(
            cells = listOf(cell(courseCode = "MATH1", courseName = "")),
            nameByCode = mapOf("MATH1" to "数学"),
        )

        assertTrue(outcome is MobileAttendanceSlotInference.Outcome.Adopted)
        assertEquals("MATH1", (outcome as MobileAttendanceSlotInference.Outcome.Adopted).cell.courseCode)
    }

    @Test
    fun noWindowMeansNoTimeBasedInference() {
        assertEquals(
            MobileAttendanceSlotInference.Reason.WINDOW_UNKNOWN,
            heldReason(infer(window = null)),
        )
        // 不正な受付時間（終了が開始以前）も時刻の根拠にしない。
        assertEquals(
            MobileAttendanceSlotInference.Reason.WINDOW_UNKNOWN,
            heldReason(infer(window = window(12, 0, 10, 20))),
        )
    }

    @Test
    fun unknownYearIsHeldEvenWithWeekdayLabel() {
        val withoutYear = pageDate.copy(year = null)
        assertEquals(
            MobileAttendanceSlotInference.Reason.DATE_UNKNOWN,
            heldReason(infer(page = withoutYear)),
        )
    }

    @Test
    fun impossibleDateAndWeekdayMismatchAreHeld() {
        val impossible = pageDate.copy(month = 2, day = 30)
        assertEquals(
            MobileAttendanceSlotInference.Reason.DATE_UNKNOWN,
            heldReason(infer(page = impossible)),
        )
        // 2026-07-06 は月曜日。画面の曜日表記が違う場合は曜日を確定しない。
        val mismatch = pageDate.copy(weekdayLabel = "火")
        assertEquals(
            MobileAttendanceSlotInference.Reason.DATE_UNKNOWN,
            heldReason(infer(page = mismatch)),
        )
    }

    @Test
    fun consecutivePeriodsAreHeld() {
        // 3限 13:00〜14:30 と 4限 14:40〜16:10 の両方に重なる受付時間。
        val outcome = infer(window = window(14, 0, 15, 0), cells = listOf(cell(period = 3)))
        assertEquals(
            MobileAttendanceSlotInference.Reason.PERIOD_AMBIGUOUS,
            heldReason(outcome),
        )
        assertEquals(listOf(3, 4), MobileAttendanceSlotInference.overlappingPeriods(window(14, 0, 15, 0)))
    }

    @Test
    fun windowBoundaryTouchingIsNotOverlap() {
        // 1限は 08:50〜10:20、2限は 10:30〜12:00。
        // 10:20〜10:30 は 1限の終了ちょうどから 2限の開始ちょうどまでで、どちらとも重ならない。
        assertEquals(
            emptyList<Int>(),
            MobileAttendanceSlotInference.overlappingPeriods(window(10, 20, 10, 30)),
        )
        // 1限の終了ちょうどから始まる受付時間は 2限とだけ重なる（端が接する 1限は含めない）。
        assertEquals(
            listOf(2),
            MobileAttendanceSlotInference.overlappingPeriods(window(10, 20, 10, 40)),
        )
    }

    @Test
    fun sameNameOnMultiplePeriodsIsHeld() {
        val outcome = infer(
            cells = listOf(cell(courseCode = "C1", period = 1), cell(courseCode = "C2", period = 2)),
        )
        assertEquals(
            MobileAttendanceSlotInference.Reason.MULTIPLE_CANDIDATES,
            heldReason(outcome),
        )
    }

    @Test
    fun sameCourseDuplicateRowsAreTreatedAsOneCandidate() {
        val outcome = infer(cells = listOf(cell(), cell()))
        assertTrue(outcome is MobileAttendanceSlotInference.Outcome.Adopted)
    }

    @Test
    fun nameOnDifferentWeekdayIsNotACandidate() {
        assertEquals(
            MobileAttendanceSlotInference.Reason.NO_CANDIDATE,
            heldReason(infer(cells = listOf(cell(dayOfWeek = 2)))),
        )
    }

    @Test
    fun periodMismatchBetweenWindowAndCellIsHeld() {
        // 受付時間は 2限と重なるが、同名コマは 3限にある。根拠が食い違うため推定しない。
        val outcome = infer(cells = listOf(cell(period = 3)))
        assertEquals(
            MobileAttendanceSlotInference.Reason.PERIOD_MISMATCH,
            heldReason(outcome),
        )
    }

    @Test
    fun blankNameIsHeld() {
        assertEquals(
            MobileAttendanceSlotInference.Reason.NAME_UNKNOWN,
            heldReason(infer(name = "   ")),
        )
    }

    @Test
    fun sameCodeOnDifferentPeriodsIsAmbiguousInEitherOrder() {
        val candidates = listOf(cell(period = 2), cell(period = 3))
        for (rows in listOf(candidates, candidates.reversed())) {
            assertEquals(MobileAttendanceSlotInference.Reason.MULTIPLE_CANDIDATES,
                heldReason(infer(cells = rows)))
        }
    }

    @Test
    fun realMobileShapeWithoutBlockDateCanBindUsingVerifiedPageDate() {
        fun parse(dates: String = "[]", confirmed: Boolean = false): Observation {
            val json = """{"ok":true,"error":null,"titleLabel":"モバイル出席登録",
                "pageDates":["2026/11/17(火)"],"campusNetworkLabelVisible":false,
                "courses":[{"courseNames":["線形代数２"],"slots":[],"dates":$dates,
                "windows":["14:30〜16:10"],"successCount":${if (confirmed) 1 else 0},
                "confirmationOpen":${!confirmed},"negative":false}],"warnings":[]}"""
            return org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.parseEvaluationResult(
                kotlinx.serialization.json.JsonPrimitive(json).toString(),
            ).observation!!
        }
        val rows = listOf(cell(courseName = "線形代数２", dayOfWeek = 2, period = 4))
        val parsed = parse()
        assertNull(parsed.courses.single().date)
        val pending = MobileAttendanceBinder.bind(parsed, rows).pending.single()
        assertTrue(pending.inferred)
        assertEquals(870, pending.windowStartMinutes)
        assertEquals(970, pending.windowEndMinutes)
        assertTrue(MobileAttendanceBinder.bind(parse(confirmed = true), rows).confirmed.single().inferred)
        for (dates in listOf("[\"2026/11/18(水)\"]", "[\"日付不明\"]")) {
            val held = MobileAttendanceBinder.bind(parse(dates), rows)
            assertTrue(held.pending.isEmpty())
            assertTrue(held.confirmed.isEmpty())
        }
        assertTrue(MobileAttendanceBinder.bind(parsed.copy(pageDate = null), rows).pending.isEmpty())
    }

    // --- Binder 連携（合成） ---

    private fun slotlessConfirmed(
        courseName: String = "数学",
        window: ConfirmationWindow? = window(10, 20, 12, 0),
        date: CapturedDate? = pageDate,
    ) = ObservedCourse(
        courseName = courseName,
        slot = null,
        successLabelVisible = true,
        date = date,
        confirmationWindow = window,
        confirmationOpen = false,
        ambiguous = false,
    )

    private fun slotlessPending(
        courseName: String = "数学",
        window: ConfirmationWindow? = window(10, 20, 12, 0),
        date: CapturedDate? = pageDate,
    ) = ObservedCourse(
        courseName = courseName,
        slot = null,
        successLabelVisible = false,
        date = date,
        confirmationWindow = window,
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
    fun binderAdoptsInferredConfirmedAndKeepsBasis() {
        val result = MobileAttendanceBinder.bind(observation(slotlessConfirmed()), listOf(cell()))

        val record = result.confirmed.single()
        assertEquals("C1", record.courseCode)
        assertEquals(2, record.period)
        assertTrue(record.inferred)
        assertNotNull(record.inferenceBasis)
        assertTrue(record.inferenceBasis!!.isNotBlank())
        assertEquals(0, result.ambiguousCount)
        assertEquals(0, result.unboundCount)
        assertTrue(result.warnings.any { it.contains("推定") })
    }

    @Test
    fun binderDoesNotInferWhenWindowIsMissing() {
        val result = MobileAttendanceBinder.bind(
            observation(slotlessConfirmed(window = null)),
            listOf(cell()),
        )

        assertTrue(result.confirmed.isEmpty())
        assertEquals(0, result.ambiguousCount)
        assertEquals(1, result.unboundCount)
    }

    @Test
    fun binderAdoptsInferredPendingWithWindowMinutes() {
        val result = MobileAttendanceBinder.bind(observation(slotlessPending()), listOf(cell()))

        val record = result.pending.single()
        assertEquals("C1", record.courseCode)
        assertEquals(2, record.period)
        assertEquals(10 * 60 + 20, record.windowStartMinutes)
        assertEquals(12 * 60, record.windowEndMinutes)
        assertTrue(record.inferred)
        assertNotNull(record.inferenceBasis)
        assertEquals(0, result.pendingAmbiguousCount)
        assertEquals(0, result.pendingUnboundCount)
    }

    @Test
    fun binderDoesNotInferWithoutConfirmationOpen() {
        val notOpen = slotlessPending().copy(confirmationOpen = false)
        val result = MobileAttendanceBinder.bind(observation(notOpen), listOf(cell()))

        assertTrue(result.pending.isEmpty())
        assertEquals(1, result.pendingAmbiguousCount)
    }

    @Test
    fun binderDoesNotInferWithoutYear() {
        val noYearPage = pageDate.copy(year = null)
        val observation = Observation(
            titleLabel = "モバイル出席登録",
            pageDate = noYearPage,
            campusNetworkLabelVisible = false,
            courses = listOf(slotlessConfirmed(date = noYearPage)),
            warnings = emptyList(),
        )
        val result = MobileAttendanceBinder.bind(observation, listOf(cell()))

        assertTrue(result.confirmed.isEmpty())
        assertEquals(1, result.ambiguousCount)
    }

    @Test
    fun binderExcludesDuplicateInferredBlocksOnSameSlot() {
        // 同じ授業名・同じ受付時間のブロックが 2 つある場合は、推定でも両方採用しない。
        val result = MobileAttendanceBinder.bind(
            observation(slotlessConfirmed(), slotlessConfirmed()),
            listOf(cell()),
        )

        assertTrue(result.confirmed.isEmpty())
        assertTrue(result.pending.isEmpty())
        assertEquals(2, result.ambiguousCount)
        assertTrue(result.warnings.any { it.contains("複数") })
    }

    @Test
    fun binderExcludesInferredAndExplicitOnSameSlot() {
        val explicit = slotlessConfirmed().copy(
            slot = org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.SlotLabel(
                1,
                listOf(2),
                "月2",
            ),
        )
        val result = MobileAttendanceBinder.bind(
            observation(slotlessConfirmed(), explicit),
            listOf(cell()),
        )

        assertTrue(result.confirmed.isEmpty())
        assertEquals(2, result.ambiguousCount)
    }

    @Test
    fun existingExplicitRecordsStayNonInferred() {
        val explicit = slotlessConfirmed().copy(
            slot = org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.SlotLabel(
                1,
                listOf(2),
                "月2",
            ),
        )
        val result = MobileAttendanceBinder.bind(observation(explicit), listOf(cell()))

        val record = result.confirmed.single()
        assertFalse(record.inferred)
        assertNull(record.inferenceBasis)
    }

    @Test
    fun codecRoundTripsInferenceFields() {
        val inferredRecord = ConfirmedAttendanceRecord(
            courseCode = "C1",
            courseName = "数学",
            dayOfWeek = 1,
            period = 2,
            year = 2026,
            month = 7,
            day = 6,
            inferred = true,
            inferenceBasis = "推定: 根拠",
        )
        val pendingRecord = PendingAttendanceRecord(
            courseCode = "C1",
            courseName = "数学",
            dayOfWeek = 1,
            period = 2,
            year = 2026,
            month = 7,
            day = 6,
            windowStartMinutes = 620,
            windowEndMinutes = 720,
            inferred = true,
            inferenceBasis = "推定: 根拠",
        )
        val snapshot = MobileAttendanceSnapshot(
            capturedAtEpochMs = 1_700_000_000_000L,
            year = 2026,
            month = 7,
            day = 6,
            confirmed = listOf(inferredRecord),
            pending = listOf(pendingRecord),
        )

        val decoded = MobileAttendanceCodec.decode(MobileAttendanceCodec.encode(snapshot))
        assertNotNull(decoded)
        assertTrue(decoded!!.confirmed.single().inferred)
        assertEquals("推定: 根拠", decoded.confirmed.single().inferenceBasis)
        assertTrue(decoded.pending.single().inferred)
        assertEquals("推定: 根拠", decoded.pending.single().inferenceBasis)
    }

    @Test
    fun codecKeepsLegacyRecordsWithoutInferenceFields() {
        // 推定フィールドを持たない既存の保存文字列も、既定 false / null で読める。
        val legacy = """
            {"capturedAtEpochMs":1700000000000,"year":2026,"month":7,"day":6,
             "confirmed":[{"courseCode":"C1","courseName":"数学","dayOfWeek":1,"period":2,
             "year":2026,"month":7,"day":6}],
             "pending":[]}
        """.trimIndent()

        val decoded = MobileAttendanceCodec.decode(legacy)
        assertNotNull(decoded)
        assertFalse(decoded!!.confirmed.single().inferred)
        assertNull(decoded.confirmed.single().inferenceBasis)
    }
}
