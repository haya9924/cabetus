package org.cabetus

import org.cabetus.domain.GpCalculator
import org.cabetus.domain.GpCalculator.Attendance
import org.cabetus.domain.GpCalculator.Grade
import org.cabetus.domain.GpCalculator.Item
import org.cabetus.domain.GpCalculator.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class GpCalculatorTest {

    private fun bd(value: String): BigDecimal = BigDecimal(value)

    private fun normal(weight: String, score: String?, max: String?): Item =
        Item(bd(weight), score?.let { bd(it) }, max?.let { bd(it) })

    private fun attendance(
        weight: String,
        total: Int,
        completed: Int,
        present: Int,
        expectedAdditional: Int?,
    ): Item = Item(
        weight = bd(weight),
        score = null,
        max = null,
        attendance = Attendance(total, completed, present, expectedAdditional),
    )

    @Test
    fun predictionGradeBoundariesUseExactScoreButDisplayIsTruncated() {
        val cases = listOf(
            "90" to Grade.S,
            "89.999" to Grade.A,
            "80" to Grade.A,
            "79.999" to Grade.B,
            "70" to Grade.B,
            "69.999" to Grade.C,
            "60" to Grade.C,
            "59.999" to Grade.D,
        )
        for ((score, grade) in cases) {
            val prediction = GpCalculator.predict(listOf(normal("100", score, "100")))!!
            assertEquals("score $score", grade, prediction.grade)
        }

        assertEquals(bd("90.00"), GpCalculator.predict(listOf(normal("100", "90", "100")))!!.score)
        assertEquals(bd("89.99"), GpCalculator.predict(listOf(normal("100", "89.999", "100")))!!.score)
        assertEquals(bd("59.99"), GpCalculator.predict(listOf(normal("100", "59.999", "100")))!!.score)
    }

    @Test
    fun predictionUsesExactRepeatingDecimals() {
        val oneThird = GpCalculator.predict(listOf(normal("100", "1", "3")))!!
        assertEquals(bd("33.33"), oneThird.score)
        assertEquals(Grade.D, oneThird.grade)

        val twoThirds = GpCalculator.predict(listOf(normal("100", "2", "3")))!!
        assertEquals(bd("66.66"), twoThirds.score)
        assertEquals(Grade.C, twoThirds.grade)
    }

    @Test
    fun zeroWeightIgnoresMissingScoreButPositiveWeightRequiresInput() {
        val prediction = GpCalculator.predict(
            listOf(
                normal("100", "80", "100"),
                Item(bd("0"), null, null, Attendance(10, 0, 0, null)),
            ),
        )!!
        assertEquals(bd("80.00"), prediction.score)
        assertEquals(Grade.A, prediction.grade)

        assertNull(GpCalculator.predict(listOf(Item(bd("100"), null, null))))
        assertNull(GpCalculator.predict(listOf(Item(bd("100"), bd("0"), bd("0")))))
    }

    @Test
    fun scoreMustBeBetweenZeroAndMax() {
        assertEquals(Grade.D, GpCalculator.predict(listOf(normal("100", "0", "80")))?.grade)
        assertNull(GpCalculator.predict(listOf(normal("100", "-1", "80"))))
        assertNull(GpCalculator.predict(listOf(normal("100", "81", "80"))))
        assertNull(GpCalculator.required(listOf(normal("100", null, "0")), 0, Grade.C))
    }

    @Test
    fun invalidWeightsAreRejected() {
        assertNull(GpCalculator.predict(emptyList()))
        assertNull(GpCalculator.predict(listOf(Item(null, bd("100"), bd("100")))))
        assertNull(GpCalculator.predict(listOf(normal("-1", "100", "100"), normal("101", "0", "100"))))
        assertNull(GpCalculator.predict(listOf(normal("50", "80", "100"))))
        assertNull(GpCalculator.predict(listOf(normal("50", "80", "100"), normal("49.99", "80", "100"))))

        val exact = GpCalculator.predict(
            listOf(normal("50", "90", "100"), normal("50", "90", "100")),
        )!!
        assertEquals(bd("90.00"), exact.score)
        assertEquals(Grade.S, exact.grade)
    }

    @Test
    fun predictionCombinesWeightedActualAndEstimatedAttendance() {
        val prediction = GpCalculator.predict(
            listOf(
                normal("60", "80", "100"), // 48
                attendance("40", total = 10, completed = 4, present = 3, expectedAdditional = 3), // 24
            ),
        )!!
        assertEquals(bd("72.00"), prediction.score)
        assertEquals(Grade.B, prediction.grade)
    }

    @Test
    fun predictionRequiresAValidAttendanceEstimate() {
        assertNull(
            GpCalculator.predict(
                listOf(
                    normal("60", "80", "100"),
                    attendance("40", total = 10, completed = 4, present = 3, expectedAdditional = null),
                ),
            ),
        )
        assertNull(
            GpCalculator.predict(
                listOf(
                    normal("60", "80", "100"),
                    attendance("40", total = 10, completed = 4, present = 3, expectedAdditional = 7),
                ),
            ),
        )
        assertNull(
            GpCalculator.predict(
                listOf(
                    normal("60", "80", "100"),
                    attendance("40", total = 0, completed = 0, present = 0, expectedAdditional = 0),
                ),
            ),
        )
        assertNull(
            GpCalculator.predict(
                listOf(
                    normal("60", "80", "100"),
                    attendance("40", total = 10, completed = 5, present = 6, expectedAdditional = 0),
                ),
            ),
        )
        assertNull(
            GpCalculator.predict(
                listOf(
                    normal("60", "80", "100"),
                    attendance("40", total = 10, completed = 11, present = 0, expectedAdditional = 0),
                ),
            ),
        )
    }

    @Test
    fun requiredPointsUsesOtherItemsAndIgnoresTargetScore() {
        val items = listOf(
            Item(bd("40"), bd("100"), bd("100")), // target score must be ignored
            normal("60", "70", "100"), // 42
        )
        val points = GpCalculator.required(items, targetIndex = 0, goal = Grade.A) as Requirement.Points
        assertEquals(bd("95"), points.points)
    }

    @Test
    fun requiredPointsRoundsUpOnePointAndClampsToZero() {
        val needsCeiling = GpCalculator.required(
            listOf(
                Item(bd("50"), null, bd("100")),
                normal("50", "80.5", "100"), // 40.25, so 79.5 points are needed
            ),
            targetIndex = 0,
            goal = Grade.A,
        ) as Requirement.Points
        assertEquals(bd("80"), needsCeiling.points)

        val alreadyEnough = GpCalculator.required(
            listOf(
                Item(bd("40"), null, bd("100")),
                normal("60", "100", "100"), // 60, already above C
            ),
            targetIndex = 0,
            goal = Grade.C,
        ) as Requirement.Points
        assertEquals(BigDecimal.ZERO, alreadyEnough.points)
    }

    @Test
    fun requiredPointsReturnsMaxForFractionalMaxOrImpossible() {
        val fractionalMax = GpCalculator.required(
            listOf(
                Item(bd("40"), null, bd("50.5")),
                normal("60", "40", "60"), // 40, A needs exactly 40 more
            ),
            targetIndex = 0,
            goal = Grade.A,
        ) as Requirement.Points
        assertEquals(bd("50.5"), fractionalMax.points)

        val impossible = GpCalculator.required(
            listOf(
                Item(bd("40"), null, bd("50.5")),
                normal("60", "40", "60"), // 40, S needs 50 more
            ),
            targetIndex = 0,
            goal = Grade.S,
        )
        assertEquals(Requirement.Impossible, impossible)

        val exactMax = GpCalculator.required(
            listOf(
                Item(bd("40"), null, bd("100")),
                normal("60", "40", "60"), // 40, A needs 40 more
            ),
            targetIndex = 0,
            goal = Grade.A,
        ) as Requirement.Points
        assertEquals(bd("100"), exactMax.points)
    }

    @Test
    fun requiredReturnsNullForIncompleteInputOrInvalidTarget() {
        assertNull(
            GpCalculator.required(
                listOf(Item(bd("50"), null, bd("100")), Item(bd("50"), null, bd("100"))),
                targetIndex = 0,
                goal = Grade.C,
            ),
        )
        assertNull(GpCalculator.required(listOf(Item(bd("100"), null, null)), 0, Grade.C))
        assertNull(GpCalculator.required(listOf(normal("100", "80", "100")), 1, Grade.A))
        assertNull(GpCalculator.required(listOf(normal("100", "0", "100")), 0, Grade.D))
        assertNull(
            GpCalculator.required(
                listOf(Item(bd("0"), null, bd("100")), normal("100", "80", "100")),
                targetIndex = 0,
                goal = Grade.A,
            ),
        )
    }

    @Test
    fun requiredAttendanceCeilsFinalCountAndIgnoresTargetEstimate() {
        val items = listOf(
            attendance("40", total = 15, completed = 5, present = 4, expectedAdditional = 999),
            normal("60", "80", "100"), // 48
        )

        val forC = GpCalculator.required(items, targetIndex = 0, goal = Grade.C) as Requirement.Attend
        // C needs 12: 12 * 15 / 40 = 4.5 -> 5 final attendances, so one more than present.
        assertEquals(1, forC.additional)
        assertEquals(10, forC.remaining)

        val forB = GpCalculator.required(items, targetIndex = 0, goal = Grade.B) as Requirement.Attend
        // B needs 22: 22 * 15 / 40 = 8.25 -> 9 final attendances.
        assertEquals(5, forB.additional)
        assertEquals(10, forB.remaining)
    }

    @Test
    fun requiredAttendanceUsesRemainingClassesAndDetectsImpossible() {
        val exactlyRemaining = GpCalculator.required(
            listOf(
                attendance("40", total = 15, completed = 10, present = 4, expectedAdditional = null),
                normal("60", "80", "100"), // 48, B needs 22
            ),
            targetIndex = 0,
            goal = Grade.B,
        ) as Requirement.Attend
        // 22 * 15 / 40 = 8.25 -> 9 final; 9 - 4 = 5, and exactly 5 classes remain.
        assertEquals(5, exactlyRemaining.additional)
        assertEquals(5, exactlyRemaining.remaining)

        val tooFewRemaining = GpCalculator.required(
            listOf(
                attendance("40", total = 15, completed = 10, present = 3, expectedAdditional = null),
                normal("60", "80", "100"),
            ),
            targetIndex = 0,
            goal = Grade.B,
        )
        assertEquals(Requirement.Impossible, tooFewRemaining)

        val beyondTotal = GpCalculator.required(
            listOf(
                attendance("40", total = 15, completed = 5, present = 4, expectedAdditional = null),
                normal("60", "60", "100"), // 36, A needs 54
            ),
            targetIndex = 0,
            goal = Grade.A,
        )
        assertEquals(Requirement.Impossible, beyondTotal)
    }

    @Test
    fun requiredAttendanceAlreadySufficientNeedsNoAdditional() {
        val requirement = GpCalculator.required(
            listOf(
                attendance("40", total = 15, completed = 5, present = 4, expectedAdditional = null),
                normal("60", "100", "100"), // 60, already above C
            ),
            targetIndex = 0,
            goal = Grade.C,
        ) as Requirement.Attend
        assertEquals(0, requirement.additional)
        assertEquals(10, requirement.remaining)
    }

    @Test
    fun attendanceCountValidationAppliesToRequiredTargets() {
        assertNull(
            GpCalculator.required(
                listOf(
                    attendance("40", total = 0, completed = 0, present = 0, expectedAdditional = null),
                    normal("60", "80", "100"),
                ),
                targetIndex = 0,
                goal = Grade.C,
            ),
        )
        assertNull(
            GpCalculator.required(
                listOf(
                    attendance("40", total = 10, completed = 5, present = 6, expectedAdditional = null),
                    normal("60", "80", "100"),
                ),
                targetIndex = 0,
                goal = Grade.C,
            ),
        )
        assertNull(
            GpCalculator.required(
                listOf(
                    attendance("40", total = 10, completed = 11, present = 0, expectedAdditional = null),
                    normal("60", "80", "100"),
                ),
                targetIndex = 0,
                goal = Grade.C,
            ),
        )
    }

    @Test
    fun attendanceTotalLimitIsTwoHundred() {
        val valid = GpCalculator.required(
            listOf(
                attendance("40", total = 200, completed = 0, present = 0, expectedAdditional = null),
                normal("60", "100", "100"),
            ),
            targetIndex = 0,
            goal = Grade.C,
        )
        assertTrue(valid is Requirement.Attend)

        val invalid = GpCalculator.required(
            listOf(
                attendance("40", total = 201, completed = 0, present = 0, expectedAdditional = null),
                normal("60", "100", "100"),
            ),
            targetIndex = 0,
            goal = Grade.C,
        )
        assertNull(invalid)
    }
}
