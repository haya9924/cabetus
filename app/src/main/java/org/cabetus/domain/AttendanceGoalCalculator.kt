package org.cabetus.domain

import kotlinx.serialization.Serializable

/** 出席目標の指定方法。 */
enum class AttendanceGoalMode {
    /** 出席率（%）。 */
    PERCENT,

    /** 必要出席回数（回）。 */
    COUNT,
}

/**
 * 科目ごとの出席目標。
 *
 * [totalClasses] はその科目の総授業回数（1..200）、[value] は
 * [AttendanceGoalMode.PERCENT] なら 1..100（%）、
 * [AttendanceGoalMode.COUNT] なら 1..[totalClasses]（回）。
 * 範囲外の値は [AttendanceGoalCalculator.validate] が false を返す。
 */
@Serializable
data class AttendanceGoal(
    val totalClasses: Int,
    val mode: AttendanceGoalMode,
    val value: Int,
)

/**
 * 出席目標の判定に使う1回分の記録。
 *
 * @param status 実効的な出席状況（[AttendanceResolver.resolve] の結果）
 * @param hasUnconfirmedInput 過去の回で入力が確定していない（未確定入力あり）
 */
data class AttendanceRecord(
    val status: AttendanceStatus,
    val hasUnconfirmedInput: Boolean = false,
)

/**
 * 出席目標に対する「あと何回休めるか」を求める純粋ロジック。JVM テスト可能。
 *
 * 数え方は既存の [AttendanceResolver.summarize] に合わせる。
 * - 出席・遅刻早退・欠席だけを「確定した回」として数える（公休・未記録は数えない）。
 * - 遅刻早退は出席に数えない（欠席と同じ扱い）。
 *
 * 必要出席回数は切り上げ整数で求める（例: 15回・80% → 12回）。
 * 残り欠席可能回数 = (総授業回数 - 必要出席回数) - これまでの欠席回数。
 * 残りが 2 以下なら [Result.Computed.isRisk]、負なら [Result.Computed.isUnattainable]。
 *
 * 次の場合は計算せず [Result.NotComputed] を返す。
 * - 目標の値が範囲外
 * - 記録の総数が設定した総授業回数を超えている（設定と記録の食い違い）
 * - 過去の未確定入力がある（確定するまで残り回数を保証できない）
 */
object AttendanceGoalCalculator {

    const val MIN_TOTAL_CLASSES: Int = 1
    const val MAX_TOTAL_CLASSES: Int = 200
    const val MIN_PERCENT: Int = 1
    const val MAX_PERCENT: Int = 100

    /** 残り欠席可能回数がこれ以下なら「危険」とみなす。 */
    const val RISK_REMAINING_ABSENCES: Int = 2

    /** 計算できなかった理由。 */
    enum class NotComputedReason {
        /** 目標の総数・割合・回数が範囲外。 */
        INVALID_GOAL,

        /** 記録の総数が設定した総授業回数を超えている。 */
        RECORD_COUNT_EXCEEDS_TOTAL,

        /** 過去の回に入力が確定していない記録がある。 */
        PAST_PENDING_INPUT,
    }

    /** 判定結果。 */
    sealed interface Result {
        /**
         * @param required 目標達成に必要な出席回数（切り上げ）
         * @param remainingAbsences これ以上休める回数（マイナスなら達成不能）
         */
        data class Computed(
            val required: Int,
            val remainingAbsences: Int,
        ) : Result {
            /** 残り欠席可能回数が [RISK_REMAINING_ABSENCES] 以下（達成不能な負数も含む）。 */
            val isRisk: Boolean
                get() = remainingAbsences <= RISK_REMAINING_ABSENCES

            /** すでに目標を達成できない。 */
            val isUnattainable: Boolean
                get() = remainingAbsences < 0
        }

        data class NotComputed(val reason: NotComputedReason) : Result
    }

    /** 目標の値が範囲内か。 */
    fun validate(goal: AttendanceGoal): Boolean {
        if (goal.totalClasses !in MIN_TOTAL_CLASSES..MAX_TOTAL_CLASSES) return false
        return when (goal.mode) {
            AttendanceGoalMode.PERCENT -> goal.value in MIN_PERCENT..MAX_PERCENT
            AttendanceGoalMode.COUNT -> goal.value in 1..goal.totalClasses
        }
    }

    /** 目標達成に必要な出席回数。範囲外なら null。 */
    fun requiredAttendance(goal: AttendanceGoal): Int? {
        if (!validate(goal)) return null
        return when (goal.mode) {
            // 切り上げ整数。端数は落とさない。
            AttendanceGoalMode.PERCENT ->
                (goal.totalClasses * goal.value + (MAX_PERCENT - 1)) / MAX_PERCENT
            AttendanceGoalMode.COUNT -> goal.value
        }
    }

    fun calculate(goal: AttendanceGoal, records: List<AttendanceRecord>): Result {
        val required = requiredAttendance(goal)
            ?: return Result.NotComputed(NotComputedReason.INVALID_GOAL)
        if (records.size > goal.totalClasses) {
            return Result.NotComputed(NotComputedReason.RECORD_COUNT_EXCEEDS_TOTAL)
        }
        if (records.any { it.hasUnconfirmedInput }) {
            return Result.NotComputed(NotComputedReason.PAST_PENDING_INPUT)
        }
        val absences = records.count {
            it.status == AttendanceStatus.ABSENT || it.status == AttendanceStatus.LATE
        }
        val allowedAbsences = goal.totalClasses - required
        return Result.Computed(
            required = required,
            remainingAbsences = allowedAbsences - absences,
        )
    }
}
