package org.cabetus

import org.cabetus.data.pdf.AttendanceParser
import org.cabetus.data.pdf.TimetableParser
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 実PDF（個人情報を含むためコミット対象外）でパーサを検証する。
 * フィクスチャが無い環境ではスキップする。
 */
class PdfParserTest {

    private fun hasResource(name: String): Boolean =
        javaClass.classLoader?.getResource(name) != null

    @Before
    fun requireFixtures() {
        assumeTrue(
            "PDF フィクスチャが無いためスキップ",
            hasResource("attendance_sample.pdf") && hasResource("timetable_sample.pdf"),
        )
    }

    @Test
    fun attendance_parsesCoursesAndRates() {
        val runs = TestPdfExtractor.resource("attendance_sample.pdf")
        val result = AttendanceParser.parse(runs)

        assertEquals("年度", 2026, result.academicYear)

        val codes = result.courses.map { it.code }.toSet()
        // 15科目（時間割の科目コード）
        assertTrue("科目数が想定以上: ${codes.size}", codes.size >= 14)

        // 既知の出席率
        val butsuri = result.courses.first { it.code == "9943333" } // 物理学実験
        assertEquals(48.0, butsuri.attendanceRate)
        val seiji = result.courses.first { it.code == "99KT241" } // 政治学
        assertEquals(92.0, seiji.attendanceRate)

        // 物理学実験は 水2/水3/水4 の週3コマ
        val butsuriPeriods = result.sessions.filter { it.code == "9943333" }.map { it.period }.toSet()
        assertEquals(setOf(2, 3, 4), butsuriPeriods)
        val butsuriDays = result.sessions.filter { it.code == "9943333" }.map { it.dayOfWeek }.toSet()
        assertEquals("水のみ", setOf(3), butsuriDays)

        // 線形代数1(9943176) は 火4/金1
        val senkeiPairs = result.sessions.filter { it.code == "9943176" }
            .map { it.dayOfWeek to it.period }.toSet()
        assertTrue("火4を含む", senkeiPairs.contains(2 to 4))
        assertTrue("金1を含む", senkeiPairs.contains(5 to 1))

        // 教員名
        val china = result.courses.first { it.code == "99KT446" }
        assertTrue("中国語の教員に魏", china.instructor.contains("魏"))
    }

    @Test
    fun timetable_parsesGridWithRoomsAndMultiCell() {
        val runs = TestPdfExtractor.resource("timetable_sample.pdf")
        val result = TimetableParser.parse(runs)

        // コード付きセルが十分に取れている
        assertTrue("セル数: ${result.cells.size}", result.cells.size >= 14)

        // 金1(day=5, period=1) に 線形代数1(9943176) と 微分積分1(9943313) の両方
        val fri1 = result.cells.filter { it.dayOfWeek == 5 && it.period == 1 }.map { it.courseCode }
        assertTrue("金1に線形代数1", fri1.contains("9943176"))
        assertTrue("金1に微分積分1", fri1.contains("9943313"))

        // データサイエンス(99KT5D1) は 土1 でオンライン
        val ds = result.cells.first { it.courseCode == "99KT5D1" }
        assertEquals(6, ds.dayOfWeek)
        assertEquals(1, ds.period)
        assertTrue("オンライン", ds.isOnline)

        // 物理学実験(9943333) は 水2/水3/水4 で複数回・教室あり
        val butsuri = result.cells.filter { it.courseCode == "9943333" }
        val butsuriSlots = butsuri.map { it.dayOfWeek to it.period }.toSet()
        assertTrue("水2", butsuriSlots.contains(3 to 2))
        assertTrue("水3", butsuriSlots.contains(3 to 3))
        assertTrue("水4", butsuriSlots.contains(3 to 4))
        assertTrue("複数回フラグ", butsuri.all { it.isMultiSession })
        assertNotNull("教室", butsuri.first().room)

        // 電気回路基礎(994357U) は 火2, 教室 E201
        val kairo = result.cells.first { it.courseCode == "994357U" }
        assertEquals(2, kairo.dayOfWeek)
        assertEquals(2, kairo.period)
        assertTrue("E201教室", kairo.room?.contains("E201") == true)
    }
}
