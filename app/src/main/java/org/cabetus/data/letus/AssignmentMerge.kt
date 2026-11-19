package org.cabetus.data.letus

import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 新しく取得した課題情報と既存行を統合する純ロジック。
 *
 * LETUS のページが一時的に欠損・変更している場合、締切や提出状況を
 * 解析できないことがある。そのまま上書きすると、以前取得できていた
 * 締切・提出済み状態が消えてしまうため、不明な項目だけ既存値を残す。
 */
object AssignmentMerge {

    private val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    private val DEADLINE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M/d HH:mm").withZone(JST)

    /**
     * 締切の解釈状態。
     *
     * [None] は「締切キーワードがページに無い」、[Unparsed] は
     * 「締切らしき記述はあるが日付を解釈できない」を表す。
     * ページの一時的な欠損でも同じ結果になり得るため、これらを
     * 締切削除の根拠にはしない。追加は今回解析できた場合だけ通知する。
     */
    private sealed interface DeadlineState {
        data object None : DeadlineState
        data class Parsed(val epochMillis: Long) : DeadlineState
        data object Unparsed : DeadlineState
    }

    private fun deadlineState(deadline: Long?, deadlineText: String): DeadlineState = when {
        deadline != null -> DeadlineState.Parsed(deadline)
        deadlineText.isBlank() -> DeadlineState.None
        else -> DeadlineState.Unparsed
    }

    fun merge(current: AssignmentEntity, previous: AssignmentEntity?): AssignmentEntity {
        if (previous == null) return current

        val submission = if (current.submissionStatus == SubmissionStatus.UNKNOWN) {
            previous.submissionStatus
        } else {
            current.submissionStatus
        }

        // 今回の締切を解析できなかった場合は、既存の締切を保持する。
        // 新規に締切が設定された場合は今回の値を優先する。
        val hasCurrentDeadline = current.deadline != null
        val deadline = current.deadline ?: previous.deadline
        val deadlineText = when {
            hasCurrentDeadline -> current.deadlineText
            previous.deadline != null -> previous.deadlineText
            current.deadlineText.isNotBlank() -> current.deadlineText
            else -> previous.deadlineText
        }

        val lifecycle = resolveLifecycle(
            current = current,
            submission = submission,
            deadline = deadline,
        )

        return current.copy(
            deadline = deadline,
            deadlineText = deadlineText,
            submissionStatus = submission,
            lifecycleStatus = lifecycle,
            detectedAt = previous.detectedAt,
            firstSeenAt = previous.firstSeenAt,
            ignored = previous.ignored,
        )
    }

    private fun resolveLifecycle(
        current: AssignmentEntity,
        submission: SubmissionStatus,
        deadline: Long?,
    ): LifecycleStatus = when {
        // 開始前はページ本文からしか判定できないため、今回の判定を尊重する。
        current.lifecycleStatus == LifecycleStatus.BEFORE_START -> LifecycleStatus.BEFORE_START
        submission == SubmissionStatus.SUBMITTED ||
            submission == SubmissionStatus.COMPLETED -> LifecycleStatus.SUBMITTED
        deadline != null && deadline < current.lastCheckedAt -> LifecycleStatus.PASSED
        else -> LifecycleStatus.ACTIVE
    }

    /**
     * 新規課題として通知してよいかを判定する。
     *
     * [assignment] は保存前・マージ後の値を想定する。[now] は取得時刻。
     * 次のいずれかに該当する場合は通知しない。
     * - ユーザーが非表示にした課題（[AssignmentEntity.ignored]）
     * - 提出済み（提出状況が SUBMITTED/COMPLETED）
     * - ライフサイクルが SUBMITTED/PASSED
     * - 締切が取得時刻以前（締切超過）
     */
    fun isNewNotificationEligible(assignment: AssignmentEntity, now: Long): Boolean = when {
        assignment.ignored -> false
        assignment.submissionStatus == SubmissionStatus.SUBMITTED ||
            assignment.submissionStatus == SubmissionStatus.COMPLETED -> false
        assignment.lifecycleStatus == LifecycleStatus.SUBMITTED ||
            assignment.lifecycleStatus == LifecycleStatus.PASSED -> false
        assignment.deadline != null && assignment.deadline <= now -> false
        else -> true
    }

    /**
     * 既存課題との差分から通知文言を作る。新規課題・非表示課題は対象外。
     *
     * 締切は [AssignmentEntity.deadlineText] 全体の差分ではなく、解析済みの
     * 締切時刻で比較する。ページ本文の説明文などが変わっただけで
     * 「締切が変更された」と誤通知しないため。
     *
     * 比較に使う [current] はマージ前の取得結果であること。マージ後は
     * 解析失敗時に前回の締切が残るため、今回の解析状態を区別できない。
     */
    fun changeLabels(previous: AssignmentEntity?, current: AssignmentEntity): List<String> {
        if (previous == null || previous.ignored || current.ignored) return emptyList()
        return buildList {
            deadlineChangeLabel(previous, current)?.let(::add)
            if (current.title != previous.title) add("タイトルが変更されました")
        }
    }

    /**
     * 締切が変わった場合だけ通知用の文言を返す。
     *
     * - 前回・今回とも解析できた場合: 締切時刻が異なるときだけ変更とみなす。
     * - 前回が明示的な締切なしで、今回解析できた場合: 締切の追加とみなす。
     * - 今回解析できない場合: ページの一時的な欠損と区別できないため通知しない。
     * - 前回が解析不能だった場合: 実際に変更されたか判断できないため通知しない。
     */
    fun deadlineChangeLabel(previous: AssignmentEntity, current: AssignmentEntity): String? {
        val previousState = deadlineState(previous.deadline, previous.deadlineText)
        val currentState = deadlineState(current.deadline, current.deadlineText)
        val changed = when {
            previousState is DeadlineState.Parsed && currentState is DeadlineState.Parsed ->
                previousState.epochMillis != currentState.epochMillis
            previousState is DeadlineState.None && currentState is DeadlineState.Parsed -> true
            else -> false
        }
        if (!changed) return null
        val deadline = current.deadline ?: return null
        return "締切: ${DEADLINE_FMT.format(Instant.ofEpochMilli(deadline))}"
    }
}
