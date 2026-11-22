package org.cabetus.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * 1科目の予想GPと、目標GPから1項目だけを逆算する純粋ロジック。JVM テスト可能。
 *
 * 通常項目の寄与は `weight × score / max`、出席項目の寄与は
 * `weight × (present + expectedAdditional) / total`。比重の合計は 100% で、
 * 寄与の合計がそのまま科目の得点率（0〜100）になる。
 *
 * 閾値判定と切り上げは [Fraction] の有理数で行い、表示だけを小数2桁で切り捨てる。
 */
object GpCalculator {

    /** 成績評価。 */
    enum class Grade(val gp: Int, val minimum: Int) {
        S(4, 90),
        A(3, 80),
        B(2, 70),
        C(1, 60),
        D(0, 0),
    }

    /**
     * 出席項目の入力。
     *
     * [total] は予定総回数、[completed] は終了済み確定回数、[present] は出席済み回数。
     * [expectedAdditional] は今後の出席見込み（予想にだけ使う）。未入力は null。
     * 逆算の対象にした場合は [expectedAdditional] を使わない。
     */
    data class Attendance(
        val total: Int,
        val completed: Int,
        val present: Int,
        val expectedAdditional: Int?,
    )

    /**
     * 採点項目。[weight] は比重（%）、[score] は得点、[max] は満点。
     * [attendance] が非 null なら出席項目として扱い、[score] と [max] は使わない。
     */
    data class Item(
        val weight: BigDecimal?,
        val score: BigDecimal?,
        val max: BigDecimal?,
        val attendance: Attendance? = null,
    )

    /** 予想結果。[score] は小数2桁で切り捨てた表示用得点率。 */
    data class Prediction(val score: BigDecimal, val grade: Grade)

    /** 目標GPに対する逆算結果。 */
    sealed interface Requirement {
        /** 通常項目に必要な得点（1点単位で切り上げ）。 */
        data class Points(val points: BigDecimal) : Requirement

        /**
         * 出席項目にあと何回出席する必要があるか。
         *
         * @param additional 追加で必要な出席回数（0 なら追加不要）
         * @param remaining 残り出席可能回数（予定総回数 - 終了済み確定回数）
         */
        data class Attend(val additional: Int, val remaining: Int) : Requirement

        /** 満点または残り回数では目標に届かない。 */
        data object Impossible : Requirement
    }

    /**
     * 全項目が入力済みなら予想を返す。比重が不正、または必要な入力が欠けていれば null。
     * 比重 0% の項目は得点も出席も参照しない。
     */
    fun predict(items: List<Item>): Prediction? {
        if (!hasValidWeights(items)) return null
        var total = Fraction.ZERO
        for (item in items) {
            if (item.weight!!.signum() == 0) continue
            total += contribution(item) ?: return null
        }
        return Prediction(total.toDisplayScore(), gradeOf(total))
    }

    /**
     * [targetIndex] の項目だけを目標 [goal] に必要な値へ逆算する。
     *
     * 他の正の比重の項目は入力値を固定する。対象の得点は無視し、出席項目なら
     * [Attendance.expectedAdditional] も無視する。計算できない入力なら null。
     * 目標 D は逆算対象外なので null を返す。
     */
    fun required(items: List<Item>, targetIndex: Int, goal: Grade): Requirement? {
        if (goal == Grade.D) return null
        if (!hasValidWeights(items)) return null
        val target = items.getOrNull(targetIndex) ?: return null
        val targetWeight = target.weight ?: return null
        if (targetWeight.signum() <= 0) return null

        var achievedByOthers = Fraction.ZERO
        for ((index, item) in items.withIndex()) {
            if (index == targetIndex) continue
            if (item.weight!!.signum() == 0) continue
            achievedByOthers += contribution(item) ?: return null
        }

        val difference = Fraction.of(goal.minimum) - achievedByOthers
        val needed = if (difference.signum() <= 0) Fraction.ZERO else difference

        val attendance = target.attendance
        if (attendance != null) {
            if (!isValidAttendance(attendance, requireEstimate = false)) return null
            val requiredFinalExact =
                needed * Fraction.of(attendance.total) / Fraction.of(targetWeight)
            val requiredFinal = requiredFinalExact.ceil()
            if (requiredFinal > BigInteger.valueOf(attendance.total.toLong())) {
                return Requirement.Impossible
            }
            val additional = (requiredFinal.toInt() - attendance.present).coerceAtLeast(0)
            val remaining = attendance.total - attendance.completed
            if (additional > remaining) return Requirement.Impossible
            return Requirement.Attend(additional, remaining)
        }

        val max = target.max ?: return null
        if (max.signum() <= 0) return null
        if (needed.signum() == 0) return Requirement.Points(BigDecimal.ZERO)

        val maxFraction = Fraction.of(max)
        val requiredPoints = needed * maxFraction / Fraction.of(targetWeight)
        if (requiredPoints.signum() <= 0) return Requirement.Points(BigDecimal.ZERO)
        if (requiredPoints > maxFraction) return Requirement.Impossible

        val ceiling = requiredPoints.ceil()
        return if (BigDecimal(ceiling).compareTo(max) <= 0) {
            Requirement.Points(BigDecimal(ceiling))
        } else {
            // 小数の満点そのものなら目標を満たす。1点切り上げると満点を超えるため満点を返す。
            Requirement.Points(max)
        }
    }

    private fun hasValidWeights(items: List<Item>): Boolean {
        var sum = BigDecimal.ZERO
        for (item in items) {
            val weight = item.weight ?: return false
            if (weight.signum() < 0 || weight > HUNDRED) return false
            sum = sum.add(weight)
        }
        return sum.compareTo(HUNDRED) == 0
    }

    /** 正の比重の項目の寄与。入力不足・不正なら null。 */
    private fun contribution(item: Item): Fraction? {
        val weight = item.weight ?: return null
        if (weight.signum() == 0) return Fraction.ZERO

        val attendance = item.attendance
        if (attendance != null) {
            if (!isValidAttendance(attendance, requireEstimate = true)) return null
            val finalAttended = attendance.present.toLong() + attendance.expectedAdditional!!.toLong()
            return Fraction.of(weight) *
                Fraction.of(finalAttended, attendance.total.toLong())
        }

        val score = item.score ?: return null
        val max = item.max ?: return null
        if (max.signum() <= 0 || score.signum() < 0 || score > max) return null
        return Fraction.of(weight) * Fraction.of(score) / Fraction.of(max)
    }

    private fun isValidAttendance(attendance: Attendance, requireEstimate: Boolean): Boolean {
        if (attendance.total !in 1..200) return false
        if (attendance.present < 0 || attendance.completed < 0) return false
        if (attendance.present > attendance.completed) return false
        if (attendance.completed > attendance.total) return false
        if (requireEstimate) {
            val expected = attendance.expectedAdditional ?: return false
            if (expected < 0 || expected > attendance.total - attendance.completed) return false
        }
        return true
    }

    private fun gradeOf(score: Fraction): Grade {
        for (grade in Grade.entries) {
            if (score >= Fraction.of(grade.minimum)) return grade
        }
        return Grade.D
    }

    /** 厳密な有理数。閾値比較と切り上げに使う。 */
    private class Fraction private constructor(
        val numerator: BigInteger,
        val denominator: BigInteger,
    ) {
        operator fun plus(other: Fraction): Fraction =
            of(
                numerator * other.denominator + other.numerator * denominator,
                denominator * other.denominator,
            )

        operator fun minus(other: Fraction): Fraction =
            of(
                numerator * other.denominator - other.numerator * denominator,
                denominator * other.denominator,
            )

        operator fun times(other: Fraction): Fraction =
            of(numerator * other.numerator, denominator * other.denominator)

        operator fun div(other: Fraction): Fraction {
            require(other.numerator.signum() != 0) { "division by zero" }
            return of(numerator * other.denominator, denominator * other.numerator)
        }

        operator fun compareTo(other: Fraction): Int =
            (numerator * other.denominator).compareTo(other.numerator * denominator)

        fun signum(): Int = numerator.signum()

        /** 切り上げ。負数でも数学的な ceil を返す。 */
        fun ceil(): BigInteger {
            val quotient = numerator.divide(denominator)
            val remainder = numerator.remainder(denominator)
            return when {
                remainder.signum() == 0 -> quotient
                numerator.signum() > 0 -> quotient + BigInteger.ONE
                else -> quotient
            }
        }

        /** 小数2桁で切り捨てた表示用得点。 */
        fun toDisplayScore(): BigDecimal =
            BigDecimal(numerator).divide(BigDecimal(denominator), 2, RoundingMode.DOWN)

        companion object {
            val ZERO: Fraction = Fraction(BigInteger.ZERO, BigInteger.ONE)

            fun of(value: Int): Fraction =
                Fraction(BigInteger.valueOf(value.toLong()), BigInteger.ONE)

            fun of(value: BigDecimal): Fraction {
                val scale = value.scale()
                return if (scale >= 0) {
                    of(value.unscaledValue(), BigInteger.TEN.pow(scale))
                } else {
                    of(
                        value.unscaledValue().multiply(BigInteger.TEN.pow(-scale)),
                        BigInteger.ONE,
                    )
                }
            }

            fun of(numerator: Long, denominator: Long): Fraction =
                of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator))

            fun of(numerator: BigInteger, denominator: BigInteger): Fraction {
                require(denominator.signum() != 0) { "denominator must not be zero" }
                if (numerator.signum() == 0) return ZERO
                val gcd = numerator.gcd(denominator)
                val divisor = if (denominator.signum() < 0) gcd.negate() else gcd
                return Fraction(numerator.divide(divisor), denominator.divide(divisor))
            }
        }
    }

    private val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
}
