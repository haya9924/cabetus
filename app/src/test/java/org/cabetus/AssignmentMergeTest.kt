package org.cabetus

import org.cabetus.data.letus.AssignmentMerge
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AssignmentMergeTest {

    private val now = 1_000_000_000_000L
    private val jst = ZoneId.of("Asia/Tokyo")

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(jst).toInstant().toEpochMilli()

    private fun assignment(
        id: String = "a1",
        deadline: Long? = null,
        deadlineText: String = "",
        submission: SubmissionStatus = SubmissionStatus.UNKNOWN,
        lifecycle: LifecycleStatus = LifecycleStatus.ACTIVE,
        title: String = "レポート1",
        lastCheckedAt: Long = now,
        ignored: Boolean = false,
    ) = AssignmentEntity(
        id = id,
        courseId = "c1",
        courseName = "線形代数",
        title = title,
        url = "https://letus.ed.tus.ac.jp/mod/assign/view.php?id=1",
        deadline = deadline,
        deadlineText = deadlineText,
        submissionStatus = submission,
        lifecycleStatus = lifecycle,
        detectedAt = now - 10_000,
        firstSeenAt = now - 10_000,
        lastCheckedAt = lastCheckedAt,
        ignored = ignored,
    )

    @Test
    fun newAssignmentIsUnchanged() {
        val current = assignment(deadline = now + 1_000)
        assertSame(current, AssignmentMerge.merge(current, null))
    }

    @Test
    fun unknownSubmissionKeepsPreviousSubmittedState() {
        val previous = assignment(
            deadline = now + 1_000,
            deadlineText = "提出期限 2026年1月1日 23:59",
            submission = SubmissionStatus.SUBMITTED,
            lifecycle = LifecycleStatus.SUBMITTED,
        )
        val current = assignment()

        val merged = AssignmentMerge.merge(current, previous)

        assertEquals(SubmissionStatus.SUBMITTED, merged.submissionStatus)
        assertEquals(LifecycleStatus.SUBMITTED, merged.lifecycleStatus)
        assertEquals(previous.deadline, merged.deadline)
        assertEquals(previous.deadlineText, merged.deadlineText)
    }

    @Test
    fun parsedDeadlineOverridesPreviousDeadline() {
        val previous = assignment(
            deadline = now + 1_000,
            deadlineText = "提出期限 2026年1月1日 23:59",
            submission = SubmissionStatus.NOT_SUBMITTED,
            lifecycle = LifecycleStatus.ACTIVE,
        )
        val newDeadline = now + 2_000
        val current = assignment(
            deadline = newDeadline,
            deadlineText = "提出期限 2026年1月2日 23:59",
            submission = SubmissionStatus.NOT_SUBMITTED,
            lifecycle = LifecycleStatus.ACTIVE,
        )

        val merged = AssignmentMerge.merge(current, previous)

        assertEquals(newDeadline, merged.deadline)
        assertEquals(current.deadlineText, merged.deadlineText)
    }

    @Test
    fun unparsedDeadlineTextKeepsPreviousDeadline() {
        val previous = assignment(
            deadline = now + 1_000,
            deadlineText = "提出期限 2026年1月1日 23:59",
            submission = SubmissionStatus.NOT_SUBMITTED,
        )
        val current = assignment(
            deadline = null,
            deadlineText = "提出期限 日付不明",
            submission = SubmissionStatus.NOT_SUBMITTED,
        )

        val merged = AssignmentMerge.merge(current, previous)

        assertEquals(previous.deadline, merged.deadline)
        assertEquals(previous.deadlineText, merged.deadlineText)
        assertEquals(LifecycleStatus.ACTIVE, merged.lifecycleStatus)
    }

    @Test
    fun knownSubmissionUpdatesPreviousState() {
        val previous = assignment(
            submission = SubmissionStatus.NOT_SUBMITTED,
            lifecycle = LifecycleStatus.ACTIVE,
        )
        val current = assignment(
            submission = SubmissionStatus.SUBMITTED,
            lifecycle = LifecycleStatus.SUBMITTED,
        )

        val merged = AssignmentMerge.merge(current, previous)

        assertEquals(SubmissionStatus.SUBMITTED, merged.submissionStatus)
        assertEquals(LifecycleStatus.SUBMITTED, merged.lifecycleStatus)
    }

    @Test
    fun pastDeadlineBecomesPassedWhenStillUnsubmitted() {
        val previous = assignment(
            deadline = now + 1_000,
            submission = SubmissionStatus.NOT_SUBMITTED,
            lifecycle = LifecycleStatus.ACTIVE,
        )
        // 今回のページから締切が読めなかった場合でも、保持した締切が過去なら PASSED。
        val current = assignment(
            deadline = null,
            submission = SubmissionStatus.NOT_SUBMITTED,
            lifecycle = LifecycleStatus.ACTIVE,
            lastCheckedAt = now + 2_000,
        )

        val merged = AssignmentMerge.merge(current, previous)

        assertEquals(LifecycleStatus.PASSED, merged.lifecycleStatus)
    }

    @Test
    fun previousLifecycleAndIgnoredFlagArePreserved() {
        val previous = assignment(
            submission = SubmissionStatus.SUBMITTED,
            lifecycle = LifecycleStatus.SUBMITTED,
            ignored = true,
        )
        val current = assignment()

        val merged = AssignmentMerge.merge(current, previous)

        assertEquals(previous.detectedAt, merged.detectedAt)
        assertEquals(previous.firstSeenAt, merged.firstSeenAt)
        assertEquals(true, merged.ignored)
    }

    @Test
    fun deadlineTextBodyChangeDoesNotTriggerDeadlineChange() {
        val deadline = epoch(2026, 1, 1, 23, 59)
        val previous = assignment(
            deadline = deadline,
            deadlineText = "提出期限 2026年1月1日 23:59 説明文A",
        )
        val current = assignment(
            deadline = deadline,
            deadlineText = "提出期限 2026年1月1日 23:59 説明文B 本文が変わった",
        )

        assertEquals(emptyList<String>(), AssignmentMerge.changeLabels(previous, current))
        assertNull(AssignmentMerge.deadlineChangeLabel(previous, current))
    }

    @Test
    fun parsedDeadlineChangeTriggersNotification() {
        val previous = assignment(
            deadline = epoch(2026, 1, 1, 23, 59),
            deadlineText = "提出期限 2026年1月1日 23:59",
        )
        val current = assignment(
            deadline = epoch(2026, 1, 2, 23, 59),
            deadlineText = "提出期限 2026年1月2日 23:59",
        )

        assertEquals(
            listOf("締切: 1/2 23:59"),
            AssignmentMerge.changeLabels(previous, current),
        )
    }

    @Test
    fun unparsedCurrentDeadlineDoesNotTriggerNotification() {
        val previous = assignment(
            deadline = epoch(2026, 1, 1, 23, 59),
            deadlineText = "提出期限 2026年1月1日 23:59",
        )
        val current = assignment(
            deadline = null,
            deadlineText = "提出期限 日付不明 本文だけが変わった",
        )

        assertEquals(emptyList<String>(), AssignmentMerge.changeLabels(previous, current))
    }

    @Test
    fun explicitNoDeadlineAfterDeadlineIsNotReportedAsRemoval() {
        // 締切キーワードが消えただけでは、ページの一時的な欠損と区別できないため
        // 削除通知は行わない（既存の締切保持動作に合わせる）。
        val previous = assignment(
            deadline = epoch(2026, 1, 1, 23, 59),
            deadlineText = "提出期限 2026年1月1日 23:59",
        )
        val current = assignment(deadline = null, deadlineText = "")

        assertEquals(emptyList<String>(), AssignmentMerge.changeLabels(previous, current))
    }

    @Test
    fun deadlineAddedAfterExplicitNoneTriggersNotification() {
        val previous = assignment(deadline = null, deadlineText = "")
        val current = assignment(
            deadline = epoch(2026, 1, 2, 23, 59),
            deadlineText = "提出期限 2026年1月2日 23:59",
        )

        assertEquals(
            listOf("締切: 1/2 23:59"),
            AssignmentMerge.changeLabels(previous, current),
        )
    }

    @Test
    fun unparsedPreviousDeadlineToParsedIsNotReported() {
        // 前回解析できなかった場合、実際に締切が変わったかは判断できない。
        val previous = assignment(deadline = null, deadlineText = "提出期限 日付不明")
        val current = assignment(
            deadline = epoch(2026, 1, 2, 23, 59),
            deadlineText = "提出期限 2026年1月2日 23:59",
        )

        assertEquals(emptyList<String>(), AssignmentMerge.changeLabels(previous, current))
    }

    @Test
    fun titleChangeIsStillDetected() {
        val deadline = epoch(2026, 1, 1, 23, 59)
        val previous = assignment(
            title = "レポート1",
            deadline = deadline,
            deadlineText = "提出期限 2026年1月1日 23:59",
        )
        val current = assignment(
            title = "レポート1（改訂版）",
            deadline = deadline,
            deadlineText = "提出期限 2026年1月1日 23:59",
        )

        assertEquals(
            listOf("タイトルが変更されました"),
            AssignmentMerge.changeLabels(previous, current),
        )
    }

    @Test
    fun ignoredAssignmentDoesNotProduceChanges() {
        val previous = assignment(
            deadline = epoch(2026, 1, 1, 23, 59),
            deadlineText = "提出期限 2026年1月1日 23:59",
            ignored = true,
        )
        val current = assignment(
            deadline = epoch(2026, 1, 2, 23, 59),
            deadlineText = "提出期限 2026年1月2日 23:59",
            ignored = true,
        )

        assertEquals(emptyList<String>(), AssignmentMerge.changeLabels(previous, current))
    }
}
