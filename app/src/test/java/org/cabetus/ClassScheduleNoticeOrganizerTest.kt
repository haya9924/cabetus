package org.cabetus

import org.cabetus.domain.ClassScheduleNoticeOrganizer.Course
import org.cabetus.domain.ClassScheduleNoticeOrganizer.Kind
import org.cabetus.domain.ClassScheduleNoticeOrganizer.Notice
import org.cabetus.domain.ClassScheduleNoticeOrganizer.organize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassScheduleNoticeOrganizerTest {
    private val math = Course("ABC1234", "数学 A")
    private val physics = Course("DEF5678", "物理学")

    @Test
    fun exactCourseNamesAndKindsAreNormalizedWithoutChangingTheDisplayedTitles() {
        val titles = listOf("休講：数学　Ａ", "【実施場所変更】物理学", "休講取消：数学 A", "教室移動：数学 A")
        val result = organize(titles, listOf(math, physics, math))
        assertEquals(
            listOf(Notice(titles[0], Kind.CANCELLATION), Notice(titles[2], Kind.OTHER), Notice(titles[3], Kind.ROOM_CHANGE)),
            result.byCourse[math.code],
        )
        assertEquals(listOf(Notice(titles[1], Kind.ROOM_CHANGE)), result.byCourse[physics.code])
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun explicitCodeCanDisambiguateIdenticalNamesButMustAgreeWithAnyName() {
        val titles = listOf(
            "休講: 数学 A",
            "【休講】10/1 【科目コード：ＡＢＣ１２３４】",
            "教室変更: 科目名: 数学 A, 科目コード: DEF5678",
            "日程変更 【科目名：物理学】",
            "日程変更 科目名: 数学 A、科目コード: ABC1234",
        )
        val result = organize(titles, listOf(math, physics, Course("XYZ1234", math.name)))
        assertEquals(listOf(Notice(titles[1], Kind.CANCELLATION), Notice(titles[4], Kind.OTHER)), result.byCourse[math.code])
        assertEquals(listOf(Notice(titles[3], Kind.OTHER)), result.byCourse[physics.code])
        assertEquals(listOf(titles[0], titles[2]), result.unmatched.map { it.title })
    }

    @Test
    fun ambiguousAndUnrecognizedTitlesRemainVisibleAndUnassignedInOriginalOrder() {
        val titles = listOf(
            "休講: 数学 A 演習", "休講: 数学 A…", "休講: 科目名: 数学 A／物理",
            "休講: 科目名: 数学 A、物理", "休講予定 ABC1234", "教室変更: 科目コード: ABC1234X",
            "休講: 科目名: 数学 A 科目コード:", "【休講]数学 A", "【科目コード: ABC1234",
            "休講: ABC1234", "休講: 科目名: 物理 科目名: 数学 A", "CLASSからのお知らせ",
        )
        val result = organize(titles, listOf(math, physics))
        // 明示コードを伴うタイトルだけを、区切られた正式コードとして科目に紐付ける。
        assertEquals(
            listOf(Notice(titles[4], Kind.OTHER), Notice(titles[9], Kind.CANCELLATION)),
            result.byCourse[math.code],
        )
        assertEquals(
            listOf(
                titles[0], titles[1], titles[2], titles[3], titles[5], titles[6],
                titles[7], titles[8], titles[10], titles[11],
            ),
            result.unmatched.map { it.title },
        )
    }

    @Test
    fun embeddedOfficialCodeIsAcceptedOnlyWhenWhitespaceOrPunctuationBounded() {
        val titles = listOf(
            "11/21 【休講】（ABC1234）5限",
            "休講: ABC1234",
            "ABC1234 教室変更のお知らせ",
        )
        val result = organize(titles, listOf(math, physics))
        assertEquals(
            listOf(Notice(titles[0], Kind.CANCELLATION), Notice(titles[1], Kind.CANCELLATION), Notice(titles[2], Kind.ROOM_CHANGE)),
            result.byCourse[math.code],
        )
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun codeTokensGluedToPunctuationUnderscoreSlashOrUrlsAreIgnored() {
        val titles = listOf(
            "休講: ABC1234_数学 A",
            "休講: https://example.com/ABC1234",
            "休講: ABC1234/DEF5678",
            "教室変更: x_ABC1234",
            "教室変更: ABC1234X",
        )
        val result = organize(titles, listOf(math, physics))
        assertTrue(result.byCourse.isEmpty())
        assertEquals(titles, result.unmatched.map { it.title })
    }

    @Test
    fun moreThanOneDifferentRegisteredCodeInTheTitleIsUnmatched() {
        val titles = listOf("休講: ABC1234 DEF5678", "教室変更: ABC1234, DEF5678")
        val result = organize(titles, listOf(math, physics))
        assertTrue(result.byCourse.isEmpty())
        assertEquals(titles, result.unmatched.map { it.title })
    }

    @Test
    fun embeddedCourseNameRequiresBoundedSpanAndInfersJapaneseNoticeKind() {
        val titles = listOf(
            "11月21日 物理学の教室変更について",
            "数学 A の休講",
            "数学 A",
            "数学 A の教室変更についてのお知らせ",
        )
        val result = organize(titles, listOf(math, physics))
        assertEquals(
            listOf(Notice(titles[1], Kind.CANCELLATION), Notice(titles[3], Kind.ROOM_CHANGE)),
            result.byCourse[math.code],
        )
        assertEquals(listOf(Notice(titles[0], Kind.ROOM_CHANGE)), result.byCourse[physics.code])
        assertEquals(listOf(titles[2]), result.unmatched.map { it.title })
    }

    @Test
    fun longerRegisteredNameWinsAndUnknownSuffixDoesNotFallBackToShorterName() {
        val mathExercise = Course("GHI7890", "数学 A 演習")
        val mathLab = Course("JKL2345", "数学 A 実験")
        val courses = listOf(math, mathExercise, mathLab)

        val result = organize(listOf("休講: 数学 A 演習", "休講: 数学 A 実験"), courses)
        assertEquals(listOf(Notice("休講: 数学 A 演習", Kind.CANCELLATION)), result.byCourse[mathExercise.code])
        assertEquals(listOf(Notice("休講: 数学 A 実験", Kind.CANCELLATION)), result.byCourse[mathLab.code])
        assertTrue(result.byCourse.keys.none { it == math.code })

        // 未知の接尾辞「実験室」は、短い「数学 A」へも長い「数学 A 実験」へも倒さない。
        val unknownSuffix = organize(listOf("休講: 数学 A 実験室"), courses)
        assertTrue(unknownSuffix.byCourse.isEmpty())
        assertEquals(listOf("休講: 数学 A 実験室"), unknownSuffix.unmatched.map { it.title })
    }

    @Test
    fun embeddedCodeCanDisambiguateSameNameButConflictsWithAnotherDetectedCourse() {
        val sameName = Course("XYZ1234", math.name)
        val courses = listOf(math, physics, sameName)

        assertEquals(
            listOf(Notice("11/21 数学 A（ABC1234）", Kind.OTHER)),
            organize(listOf("11/21 数学 A（ABC1234）"), courses).byCourse[math.code],
        )
        assertTrue(organize(listOf("11/21 数学 A"), courses).byCourse.isEmpty())
        // 科目名が数学 A なのに、別科目のコードが併記されている場合は確定しない。
        val conflict = organize(listOf("休講: 数学 A（DEF5678）"), courses)
        assertTrue(conflict.byCourse.isEmpty())
        assertEquals(listOf("休講: 数学 A（DEF5678）"), conflict.unmatched.map { it.title })
    }

    @Test
    fun rescissionAndDecorationAreNotTreatedAsCancellation() {
        val titles = listOf(
            "11/21 【休講取消】（ABC1234）5限",
            "休講取消 ABC1234",
            "【休講]数学 A",
        )
        val result = organize(titles, listOf(math, physics))
        assertEquals(
            listOf(Notice(titles[0], Kind.OTHER), Notice(titles[1], Kind.OTHER)),
            result.byCourse[math.code],
        )
        assertEquals(listOf(titles[2]), result.unmatched.map { it.title })
    }

    @Test
    fun officialCodeAliasAndDuplicateSlotsResolveToTheSingleCourse() {
        val aliased = Course("ABC1234", "数学 A", officialCodeAlias = "abc1235")
        val titles = listOf("11/21 abc1235 補講", "11/21 ABC1235")
        val result = organize(titles, listOf(aliased, aliased, Course("ABC1234", "数学 A")))
        assertEquals(
            listOf(Notice(titles[0], Kind.OTHER), Notice(titles[1], Kind.OTHER)),
            result.byCourse["ABC1234"],
        )
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun aliasTokenCollidingAcrossDifferentCodesIsNotUsed() {
        val first = Course("ABC1234", "数学 A", officialCodeAlias = "ZZZ9999")
        val second = Course("DEF5678", "物理", officialCodeAlias = "ZZZ9999")
        val result = organize(listOf("11/21 ZZZ9999"), listOf(first, second))
        assertTrue(result.byCourse.isEmpty())
        assertEquals(listOf("11/21 ZZZ9999"), result.unmatched.map { it.title })
    }

    @Test
    fun syntheticClassStyleTitlesAreMatchedConservatively() {
        val courses = listOf(
            math,
            physics,
            Course("MNO3456", "化学"),
            Course("PQR6789", "英語コミュニケーション"),
        )
        val titles = listOf(
            "11/21 【休講】（ABC1234）5限",
            "11月21日 物理学の教室変更について",
            "11月22日 化学の休講について",
            "11/25 英語コミュニケーション（PQR6789）補講",
            "11月26日 数学 A の休講",
            "11月27日 数学 B の休講",
            "11月28日 ABC1234 と DEF5678 の教室変更",
            "11/29 【休講】",
        )
        val result = organize(titles, courses)
        assertEquals(
            listOf(
                Notice(titles[0], Kind.CANCELLATION),
                Notice(titles[4], Kind.CANCELLATION),
            ),
            result.byCourse[math.code],
        )
        assertEquals(listOf(Notice(titles[1], Kind.ROOM_CHANGE)), result.byCourse[physics.code])
        assertEquals(listOf(Notice(titles[2], Kind.CANCELLATION)), result.byCourse["MNO3456"])
        assertEquals(listOf(Notice(titles[3], Kind.OTHER)), result.byCourse["PQR6789"])
        assertEquals(
            listOf(titles[5], titles[6], titles[7]),
            result.unmatched.map { it.title },
        )
    }
}
