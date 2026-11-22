package org.cabetus.ui.gp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpWeightSuggestionTest {

    @Test
    fun blankSiblingIsZeroAndSuggestionFillsTheRest() {
        // 空欄の比重は0%。他の合計が65.5%なら残りは34.5%。
        assertEquals(
            "34.5",
            suggestRemainingWeight(
                currentWeight = "",
                otherWeights = listOf("", "65.5", ""),
            ),
        )
    }

    @Test
    fun allBlankSuggestsFullHundred() {
        assertEquals(
            "100",
            suggestRemainingWeight(
                currentWeight = "",
                otherWeights = listOf("", "", ""),
            ),
        )
    }

    @Test
    fun explicitTapReplacesExistingWeight() {
        assertEquals(
            "40",
            suggestRemainingWeight(
                currentWeight = "60",
                otherWeights = listOf("30", "30"),
            ),
        )
    }

    @Test
    fun invalidCurrentWeightStillGetsSuggestion() {
        assertEquals(
            "34.5",
            suggestRemainingWeight(
                currentWeight = "abc",
                otherWeights = listOf("65.5"),
            ),
        )
        assertEquals(
            "100",
            suggestRemainingWeight(
                currentWeight = "100.5",
                otherWeights = emptyList(),
            ),
        )
    }

    @Test
    fun invalidSiblingWeightHasNoSuggestion() {
        assertNull(
            suggestRemainingWeight(
                currentWeight = "",
                otherWeights = listOf("65.5", "abc"),
            ),
        )
        assertNull(
            suggestRemainingWeight(
                currentWeight = "",
                otherWeights = listOf("-1"),
            ),
        )
    }

    @Test
    fun siblingsAtOrAboveHundredHaveNoSuggestion() {
        assertNull(
            suggestRemainingWeight(
                currentWeight = "",
                otherWeights = listOf("60", "40"),
            ),
        )
        assertNull(
            suggestRemainingWeight(
                currentWeight = "",
                otherWeights = listOf("100"),
            ),
        )
        assertNull(
            suggestRemainingWeight(
                currentWeight = "",
                otherWeights = listOf("70", "50"),
            ),
        )
    }

    @Test
    fun suggestionLongerThanInputLimitIsRejected() {
        // 入力は16文字だが、提案は「99.99999999999999」(17文字) になる。
        val tiny = "0.00000000000001"
        assertNull(
            suggestRemainingWeight(
                currentWeight = "",
                otherWeights = listOf(tiny),
            ),
        )
        // 提案が上限ちょうどの16文字なら入力できる。
        assertEquals(
            "99.9999999999999",
            suggestRemainingWeight(
                currentWeight = "",
                otherWeights = listOf("0.0000000000001"),
            ),
        )
    }

    @Test
    fun numericallyUnchangedSuggestionIsSuppressed() {
        assertNull(
            suggestRemainingWeight(
                currentWeight = "34.50",
                otherWeights = listOf("65.5"),
            ),
        )
        assertNull(
            suggestRemainingWeight(
                currentWeight = "34.5",
                otherWeights = listOf("", "65.5"),
            ),
        )
    }
}
