package org.cabetus

import org.cabetus.data.local.*
import org.cabetus.data.letus.AssignmentMerge
import org.junit.Assert.*
import org.junit.Test

class NewAssignmentEligibilityTest {
    private val task = AssignmentEntity("a", "c", "科目", "課題", "", 101, "", SubmissionStatus.NOT_SUBMITTED, LifecycleStatus.ACTIVE, 0, 0, 0)
    @Test fun excludesSubmittedHiddenAndPast() {
        assertTrue(AssignmentMerge.isNewNotificationEligible(task, 100))
        assertFalse(AssignmentMerge.isNewNotificationEligible(task, 101))
        assertFalse(AssignmentMerge.isNewNotificationEligible(task.copy(ignored = true), 100))
        assertFalse(AssignmentMerge.isNewNotificationEligible(task.copy(submissionStatus = SubmissionStatus.SUBMITTED), 100))
        assertFalse(AssignmentMerge.isNewNotificationEligible(task.copy(submissionStatus = SubmissionStatus.COMPLETED), 100))
        assertFalse(AssignmentMerge.isNewNotificationEligible(task.copy(lifecycleStatus = LifecycleStatus.PASSED), 100))
    }
    @Test fun unknownDeadlineAndBeforeStartAreEligible() {
        assertTrue(AssignmentMerge.isNewNotificationEligible(task.copy(deadline = null, submissionStatus = SubmissionStatus.UNKNOWN), 100))
        assertTrue(AssignmentMerge.isNewNotificationEligible(task.copy(lifecycleStatus = LifecycleStatus.BEFORE_START), 100))
    }
}
