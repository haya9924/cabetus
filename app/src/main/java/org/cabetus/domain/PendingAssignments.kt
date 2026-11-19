package org.cabetus.domain

import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus

/** DAO observePending/getPending と同じ未提出定義。期限切れでも隠さない。 */
fun AssignmentEntity.isPendingAssignment(): Boolean =
    !ignored && lifecycleStatus != LifecycleStatus.SUBMITTED &&
        submissionStatus != SubmissionStatus.SUBMITTED && submissionStatus != SubmissionStatus.COMPLETED
