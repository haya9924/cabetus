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
    private val physics = Course("DEF5678", "物理")

    @Test
    fun exactCourseNamesAndKindsAreNormalizedWithoutChangingTheDisplayedTitles() {
        val titles = listOf("休講：数学　Ａ", "【実施場所変更】物理", "休講取消：数学 A", "教室移動：数学 A")
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
            "日程変更 【科目名：物理】",
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
        assertTrue(result.byCourse.isEmpty())
        assertEquals(titles, result.unmatched.map { it.title })
        assertEquals(Kind.OTHER, result.unmatched.first { it.title == "休講予定 ABC1234" }.kind)
    }
}
