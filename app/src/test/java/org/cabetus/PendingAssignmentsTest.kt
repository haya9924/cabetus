package org.cabetus

import org.cabetus.data.local.*
import org.cabetus.domain.isPendingAssignment
import org.junit.Assert.*
import org.junit.Test

class PendingAssignmentsTest {
    private fun assignment(lifecycle: LifecycleStatus, status: SubmissionStatus, ignored: Boolean = false) = AssignmentEntity(
        "a", "c", "course", "task", "https://letus.ed.tus.ac.jp", 0, "", status, lifecycle, 0, 0, 0, ignored,
    )
    @Test fun passingDeadlineDoesNotHideUnsubmittedTask() {
        for (state in listOf(LifecycleStatus.ACTIVE, LifecycleStatus.BEFORE_START, LifecycleStatus.PASSED)) {
            assertTrue(assignment(state, SubmissionStatus.NOT_SUBMITTED).isPendingAssignment())
            assertTrue(assignment(state, SubmissionStatus.UNKNOWN).isPendingAssignment())
        }
    }
    @Test fun submittedAndHiddenStayExcluded() {
        assertFalse(assignment(LifecycleStatus.SUBMITTED, SubmissionStatus.UNKNOWN).isPendingAssignment())
        assertFalse(assignment(LifecycleStatus.ACTIVE, SubmissionStatus.SUBMITTED).isPendingAssignment())
        assertFalse(assignment(LifecycleStatus.ACTIVE, SubmissionStatus.COMPLETED).isPendingAssignment())
        assertFalse(assignment(LifecycleStatus.ACTIVE, SubmissionStatus.NOT_SUBMITTED, true).isPendingAssignment())
    }
}
