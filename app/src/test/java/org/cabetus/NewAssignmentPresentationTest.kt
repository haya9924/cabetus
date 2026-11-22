package org.cabetus

import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.cabetus.notification.NewAssignmentPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NewAssignmentPresentationTest {

    private val jst: ZoneId = ZoneId.of("Asia/Tokyo")

    private fun millis(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(jst).toInstant().toEpochMilli()

    private fun assignment(
        id: String,
        courseName: String = "科目A",
        title: String = "課題A",
        deadline: Long? = millis(2025, 5, 10, 23, 59),
        deadlineText: String = "2025/05/10 23:59",
    ) = AssignmentEntity(
        id = id,
        courseId = "c1",
        courseName = courseName,
        title = title,
        url = "https://letus.ed.tus.ac.jp/mod/assign/view.php?id=$id",
        deadline = deadline,
        deadlineText = deadlineText,
        submissionStatus = SubmissionStatus.NOT_SUBMITTED,
        lifecycleStatus = LifecycleStatus.ACTIVE,
        detectedAt = 0,
        firstSeenAt = 0,
        lastCheckedAt = 0,
    )

    @Test fun emptyReturnsNull() {
        assertNull(NewAssignmentPresentation.build(emptyList()))
        assertNull(NewAssignmentPresentation.build(emptyList(), isInitialImport = true))
    }

    @Test fun singleAssignment() {
        val a = assignment("a1", courseName = "情報 リテラシー", title = "レポート 1")
        val p = NewAssignmentPresentation.build(listOf(a))!!
        assertEquals("検出した課題: レポート 1", p.title)
        assertEquals("科目: 情報 リテラシー\n締切: 5/10(土) 23:59", p.summary)
        assertEquals("・情報 リテラシー / レポート 1 / 締切: 5/10(土) 23:59", p.expandedText)
        assertEquals(listOf("a1"), p.assignments.map { it.id })
    }

    @Test fun multipleAssignmentsUseSummaryAndExpandedList() {
        val list = (1..6).map { i ->
            assignment(
                id = "a$i",
                courseName = if (i % 2 == 0) "科目B" else "科目A",
                title = "課題$i",
                deadline = millis(2025, 5, i, 12, 0),
            )
        }
        val p = NewAssignmentPresentation.build(list)!!
        assertEquals("課題を6件新たに検出", p.title)
        assertEquals("2科目 / 6件", p.summary)
        val lines = p.expandedText.lines()
        assertEquals(6, lines.size)
        assertTrue(lines.last() == "ほか1件")
        assertEquals("・科目A / 課題1 / 締切: 5/1(木) 12:00", lines.first())
        assertEquals(6, p.assignments.size)
    }

    @Test fun duplicateIdsAreRemoved() {
        val first = assignment("a1", title = "最初")
        val second = assignment("a1", title = "あとから")
        val p = NewAssignmentPresentation.build(listOf(first, second))!!
        assertEquals(listOf("最初"), p.assignments.map { it.title })
    }

    @Test fun unknownDeadlineText() {
        val noRecord = assignment("a1", deadline = null, deadlineText = "   ")
        assertEquals(
            "科目: 科目A\n締切: 記載なし",
            NewAssignmentPresentation.build(listOf(noRecord))!!.summary,
        )

        val unparsed = assignment("a2", deadline = null, deadlineText = "締切未定")
        assertEquals(
            "科目: 科目A\n締切: 取得できません",
            NewAssignmentPresentation.build(listOf(unparsed))!!.summary,
        )
    }

    @Test fun unknownCourseAndTitleAreFilled() {
        val a = assignment("a1", courseName = "  \n ", title = " ")
        val p = NewAssignmentPresentation.build(listOf(a))!!
        assertEquals("検出した課題: 課題名不明", p.title)
        assertTrue(p.summary.startsWith("科目: 科目名不明\n"))
    }

    @Test fun initialImportUsesInitialWordingRegardlessOfCount() {
        val one = NewAssignmentPresentation.build(listOf(assignment("a1")), isInitialImport = true)!!
        assertEquals("初回取得: 対象の課題が1件", one.title)

        val many = NewAssignmentPresentation.build(
            (1..3).map { assignment("a$it", title = "課題$it") },
            isInitialImport = true,
        )!!
        assertEquals("初回取得: 対象の課題が3件", many.title)
        assertEquals("1科目 / 3件", many.summary)
    }

    @Test fun sortedByDeadlineThenCourseTitleId() {
        val list = listOf(
            assignment("z", courseName = "科目B", title = "B課題", deadline = millis(2025, 5, 2, 9, 0)),
            assignment("b", courseName = "科目A", title = "同じ", deadline = millis(2025, 5, 1, 9, 0)),
            assignment("a", courseName = "科目A", title = "同じ", deadline = millis(2025, 5, 1, 9, 0)),
            assignment("n", courseName = "科目A", title = "締切なし", deadline = null),
            assignment("c", courseName = "科目A", title = "あと", deadline = millis(2025, 5, 1, 9, 0)),
        )
        val p = NewAssignmentPresentation.build(list)!!
        assertEquals(listOf("c", "a", "b", "z", "n"), p.assignments.map { it.id })
    }
}
