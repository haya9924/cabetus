package org.cabetus

import org.cabetus.data.classweb.ClassTimetableMergePlanner
import org.cabetus.data.classweb.ClassTimetableSlot
import org.cabetus.data.local.ClassCourseEntity
import org.cabetus.data.local.TimetableCellEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web 時間割取り込みのマージ計画を、合成データだけで検証する。
 * 実データ・個人情報・ログ出力は使わない。
 */
class ClassTimetableMergePlannerTest {

    private fun course(code: String, name: String) = ClassCourseEntity(
        code = code,
        name = name,
        instructor = "",
        credits = null,
        attendanceRate = null,
    )

    private fun cell(
        id: Long,
        day: Int,
        period: Int,
        code: String,
        name: String,
    ) = TimetableCellEntity(
        id = id,
        dayOfWeek = day,
        period = period,
        courseCode = code,
        courseName = name,
        instructor = "",
        room = null,
        isOnline = false,
        isMultiSession = false,
    )

    @Test
    fun blocksWhenExistingTimetableExistsEvenIfSlotsAreNew() {
        val existingCourses = listOf(course("OFFICIAL-A", "既存科目"))
        val existingCells = listOf(cell(1, 1, 1, "OFFICIAL-A", "既存科目"))

        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(ClassTimetableSlot(2, 2, "新規科目")),
            existingCourses = existingCourses,
            existingCells = existingCells,
            academicYear = 2025,
        )

        assertTrue(plan.blocked)
        assertTrue(plan.blockedReason!!.contains("年度・学期"))
        assertTrue(plan.cellsToInsert.isEmpty())
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertEquals(0, plan.newCourseCount)
    }

    @Test
    fun doesNotMergeIntoSingleOfficialCourseWithSameName() {
        val existingCourses = listOf(course("OFFICIAL-A", "合成科目A"))
        val slots = listOf(
            ClassTimetableSlot(3, 4, "合成科目A"),
            ClassTimetableSlot(2, 2, "合成科目B"),
        )

        val plan = ClassTimetableMergePlanner.plan(
            slots = slots,
            existingCourses = existingCourses,
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        // 同名の正式科目には統合せず、そのコマ自体も追加しない。
        assertTrue(plan.cellsToInsert.none { it.courseName == "合成科目A" })
        assertTrue(plan.cellsToInsert.none { it.courseCode == "OFFICIAL-A" })
        assertTrue(plan.coursesToUpsert.none { it.code == "OFFICIAL-A" })
        assertEquals(0, plan.existingCourseCount)
        assertEquals(listOf("合成科目B"), plan.cellsToInsert.map { it.courseName })
        assertEquals(1, plan.newCourseCount)
        assertTrue(plan.warnings.any { it.contains("正式科目") })
    }

    @Test
    fun reusesOnlyProvisionalCourseCreatedByThisFeature() {
        val name = "合成科目A"
        val provisionalCode = ClassTimetableMergePlanner.generatedCourseCode(name)
        assertTrue(ClassTimetableMergePlanner.isProvisionalCourseCode(provisionalCode))

        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(ClassTimetableSlot(3, 4, name)),
            existingCourses = listOf(course(provisionalCode, name)),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertEquals(1, plan.cellsToInsert.size)
        assertEquals(provisionalCode, plan.cellsToInsert.single().courseCode)
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertEquals(1, plan.existingCourseCount)
    }

    @Test
    fun doesNotReuseSameNameProvisionalCourseWhenCodeDiffers() {
        // 名前が同じでも、この機能が生成する仮コードと一致しない既存科目には統合しない。
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(ClassTimetableSlot(3, 4, "合成科目A")),
            existingCourses = listOf(course("CLASSWEB-legacy", "合成科目A")),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertTrue(plan.cellsToInsert.isEmpty())
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertEquals(1, plan.skippedAmbiguousCount)
    }

    @Test
    fun skipsAmbiguousMultipleExistingNamesInsteadOfGuessing() {
        val existingCourses = listOf(
            course("CLASSWEB-1111", "同名科目"),
            course("CLASSWEB-2222", "同名科目"),
        )
        val slots = listOf(ClassTimetableSlot(1, 1, "同名科目"))

        val plan = ClassTimetableMergePlanner.plan(
            slots = slots,
            existingCourses = existingCourses,
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertTrue(plan.cellsToInsert.isEmpty())
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertEquals(1, plan.skippedAmbiguousCount)
        assertTrue(plan.warnings.any { it.contains("同名") })
    }

    @Test
    fun generatedCodeIsDeterministicAndKeepsInternalWhitespaceSignificant() {
        val same = ClassTimetableMergePlanner.generatedCourseCode("合成科目A")
        val first = ClassTimetableMergePlanner.generatedCourseCode("合成科目A")
        val second = ClassTimetableMergePlanner.generatedCourseCode("合成 科目 A")

        assertEquals(first, same)
        assertNotEquals(first, second)
        assertTrue(first.startsWith("CLASSWEB-"))

        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(ClassTimetableSlot(1, 1, "合成科目A")),
            existingCourses = listOf(course(first, "別の合成科目")),
            existingCells = emptyList(),
            academicYear = 2025,
        )
        assertTrue(plan.cellsToInsert.isEmpty())
        assertEquals(1, plan.skippedAmbiguousCount)
    }

    @Test
    fun skipsSameSlotDifferentCourseNamesInsteadOfGuessing() {
        val slots = listOf(
            ClassTimetableSlot(1, 1, "合成科目A"),
            ClassTimetableSlot(1, 1, "合成科目B"),
            ClassTimetableSlot(1, 1, "合成 科目A"),
        )

        val plan = ClassTimetableMergePlanner.plan(
            slots = slots,
            existingCourses = emptyList(),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertTrue(plan.cellsToInsert.isEmpty())
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertEquals(3, plan.sameSlotDifferentCourseCount)
        assertTrue(plan.warnings.any { it.contains("同じコマに別の科目名") })
    }

    @Test
    fun allowsImportIntoEmptyTimetableButWarnsAboutUnknownTermAndPdfLinkage() {
        val slots = listOf(
            ClassTimetableSlot(1, 1, "合成科目A"),
            ClassTimetableSlot(1, 1, "合成科目A"),
            ClassTimetableSlot(0, 1, "範囲外"),
            ClassTimetableSlot(1, 8, "範囲外"),
            ClassTimetableSlot(1, 1, "   "),
        )

        val plan = ClassTimetableMergePlanner.plan(
            slots = slots,
            existingCourses = emptyList(),
            existingCells = emptyList(),
            academicYear = 0,
        )

        assertFalse(plan.blocked)
        assertEquals(1, plan.cellsToInsert.size)
        assertTrue(plan.warnings.any { it.contains("形式が不正なコマを3件") })
        assertTrue(plan.warnings.any { it.contains("年度が未設定") })
        assertTrue(plan.warnings.any { it.contains("年度・学期は取得できません") })
        assertTrue(plan.warnings.any { it.contains("CLASS") && it.contains("紐づきません") })
        assertFalse(plan.warnings.any { it.contains("合成科目A") })
    }

    @Test
    fun importsConfirmedOfficialCodeAndRoomWithoutOverwritingExistingCourse() {
        val existing = course("994357U", "合成科目A").copy(
            instructor = "既存教員",
            credits = 2.0,
            attendanceRate = 80.0,
        )
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 2, "合成科目A", "994357U", "E501"),
            ),
            existingCourses = listOf(existing),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertEquals(1, plan.cellsToInsert.size)
        assertEquals("994357U", plan.cellsToInsert.single().courseCode)
        assertEquals("E501", plan.cellsToInsert.single().room)
        // 既存の教員・単位数・出席率を上書きしない。
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertTrue(plan.warnings.any { it.contains("年度・学期を確認できない") })
        assertEquals(1, plan.officialCodeCellCount)
        assertEquals(0, plan.unknownCodeCellCount)
        assertEquals(0, plan.unknownRoomCellCount)
    }

    @Test
    fun createsOfficialCourseOnceForMultipleCells() {
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 1, "合成科目A", "994357U", "E501"),
                ClassTimetableSlot(2, 3, "合成科目A", "994357U", "E502"),
            ),
            existingCourses = emptyList(),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertEquals(1, plan.newCourseCount)
        assertEquals("994357U", plan.coursesToUpsert.single().code)
        assertEquals(2, plan.cellsToInsert.size)
        assertEquals(setOf("E501", "E502"), plan.cellsToInsert.map { it.room }.toSet())
        assertEquals(2, plan.officialCodeCellCount)
        assertTrue(plan.warnings.any { it.contains("正式な科目コードを確認できた1科目") })
    }

    @Test
    fun skipsOfficialCodeOwnedByDifferentExistingCourse() {
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 1, "合成科目A", "994357U", "E501"),
            ),
            existingCourses = listOf(course("994357U", "別の合成科目")),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertTrue(plan.cellsToInsert.isEmpty())
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertEquals(1, plan.skippedCodeConflictCount)
        assertTrue(plan.warnings.any { it.contains("既存の別科目と競合") })
    }

    @Test
    fun fallsBackToProvisionalCodeWhenSameCodeHasDifferentNames() {
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 1, "合成科目A", "994357U", "E501"),
                ClassTimetableSlot(2, 2, "合成科目B", "994357U", "E502"),
            ),
            existingCourses = emptyList(),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertEquals(2, plan.codeConflictCellCount)
        assertEquals(2, plan.unknownCodeCellCount)
        assertEquals(2, plan.cellsToInsert.size)
        assertTrue(
            plan.cellsToInsert.all {
                ClassTimetableMergePlanner.isProvisionalCourseCode(it.courseCode)
            },
        )
        assertTrue(plan.coursesToUpsert.none { it.code == "994357U" })
        assertTrue(plan.warnings.any { it.contains("重複・不一致") })
    }

    @Test
    fun holdsSameNameWithDifferentOfficialCodesInsteadOfMergingIntoOneProvisionalCourse() {
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 1, "合成科目A", "1111111", "E501"),
                ClassTimetableSlot(2, 2, "合成科目A", "2222222", "E502"),
            ),
            existingCourses = emptyList(),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertEquals(2, plan.codeConflictCellCount)
        assertEquals(0, plan.newCourseCount)
        assertTrue(plan.cellsToInsert.isEmpty())
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertEquals(2, plan.skippedAmbiguousCount)
        assertTrue(plan.warnings.any { it.contains("異なる正式な科目コード") })
        assertTrue(plan.warnings.any { it.contains("同じ仮コードへ統合せず") })
    }

    @Test
    fun sameNameWithPartiallyMissingCodeIsOrderIndependentAndHoldsOnlyUncertainCells() {
        val withCode = ClassTimetableSlot(1, 1, "合成科目A", "1111111", "E501")
        val withoutCode = ClassTimetableSlot(2, 2, "合成科目A", null, "E502")

        fun planFor(slots: List<ClassTimetableSlot>) = ClassTimetableMergePlanner.plan(
            slots = slots,
            existingCourses = emptyList(),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        val forward = planFor(listOf(withCode, withoutCode))
        val reverse = planFor(listOf(withoutCode, withCode))

        assertEquals(forward.cellsToInsert, reverse.cellsToInsert)
        assertEquals(forward.coursesToUpsert, reverse.coursesToUpsert)
        assertEquals(forward.skippedAmbiguousCount, reverse.skippedAmbiguousCount)
        assertEquals(1, forward.cellsToInsert.size)
        assertEquals("1111111", forward.cellsToInsert.single().courseCode)
        assertEquals(1, forward.newCourseCount)
        assertEquals(1, forward.skippedAmbiguousCount)
        assertTrue(
            forward.cellsToInsert.none {
                ClassTimetableMergePlanner.isProvisionalCourseCode(it.courseCode)
            },
        )
        assertTrue(forward.warnings.any { it.contains("一部で正式な科目コードを確認できない") })
    }

    @Test
    fun keepsInternalWhitespaceSignificantWhenMatchingNames() {
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 1, "合成科目A"),
                ClassTimetableSlot(2, 2, "合成 科目A"),
            ),
            existingCourses = emptyList(),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertEquals(2, plan.cellsToInsert.size)
        assertEquals(2, plan.newCourseCount)
        assertEquals(setOf("合成科目A", "合成 科目A"), plan.cellsToInsert.map { it.courseName }.toSet())
        assertEquals(
            2,
            plan.cellsToInsert.map { it.courseCode }.distinct().size,
        )
    }

    @Test
    fun treatsNamesThatDifferOnlyByInternalWhitespaceAsDifferentCoursesInSameCell() {
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 1, "合成科目A"),
                ClassTimetableSlot(1, 1, "合成 科目A"),
            ),
            existingCourses = emptyList(),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertTrue(plan.cellsToInsert.isEmpty())
        assertEquals(2, plan.sameSlotDifferentCourseCount)
    }

    @Test
    fun leavesRoomBlankWhenMissingOrConflictingInsteadOfGuessing() {
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 1, "合成科目A", "994357U", null),
                ClassTimetableSlot(2, 2, "合成科目B", "1010996", "葛：E101教室"),
                ClassTimetableSlot(2, 2, "合成科目B", "1010996", "葛：E202教室"),
            ),
            existingCourses = emptyList(),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertEquals(2, plan.cellsToInsert.size)
        assertTrue(plan.cellsToInsert.all { it.room == null })
        assertEquals(2, plan.unknownRoomCellCount)
        assertTrue(plan.warnings.any { it.contains("複数の教室") })
        assertTrue(plan.warnings.any { it.contains("教室を確認できない") })
    }

    @Test
    fun keepsExistingProvisionalCourseInsteadOfReplacingItWithOfficialCode() {
        val name = "合成科目A"
        val provisionalCode = ClassTimetableMergePlanner.generatedCourseCode(name)
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 1, name, "994357U", "E501"),
            ),
            existingCourses = listOf(course(provisionalCode, name)),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertEquals(provisionalCode, plan.cellsToInsert.single().courseCode)
        assertEquals("E501", plan.cellsToInsert.single().room)
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertTrue(plan.warnings.any { it.contains("正式コードへ置き換えません") })
    }

    @Test
    fun skipsWhenSameNameOfficialCourseHasDifferentCode() {
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(
                ClassTimetableSlot(1, 1, "合成科目A", "994357U", "E501"),
            ),
            existingCourses = listOf(course("OFFICIAL-A", "合成科目A")),
            existingCells = emptyList(),
            academicYear = 2025,
        )

        assertFalse(plan.blocked)
        assertTrue(plan.cellsToInsert.isEmpty())
        assertTrue(plan.coursesToUpsert.isEmpty())
        assertTrue(plan.warnings.any { it.contains("正式科目") })
    }

    @Test
    fun addsNothingWhenOnlyExistingTimetableIsPresent() {
        val existingCells = listOf(cell(1, 1, 1, "OFFICIAL-A", "合成科目A"))
        val plan = ClassTimetableMergePlanner.plan(
            slots = listOf(ClassTimetableSlot(1, 1, "合成科目A")),
            existingCourses = listOf(course("OFFICIAL-A", "合成科目A")),
            existingCells = existingCells,
            academicYear = 2025,
        )

        assertTrue(plan.blocked)
        assertTrue(plan.cellsToInsert.isEmpty())
        assertTrue(plan.coursesToUpsert.isEmpty())
    }
}
