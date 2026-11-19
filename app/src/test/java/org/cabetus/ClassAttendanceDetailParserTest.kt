package org.cabetus

import org.cabetus.data.classweb.ClassAttendanceDetailParser
import org.cabetus.data.classweb.ClassAttendanceDetailParser.IssueCode
import org.cabetus.data.classweb.ClassAttendanceDetailParser.IssueSeverity
import org.cabetus.data.classweb.ClassAttendanceDetailParser.ProvisionalAttendance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay

/**
 * 実データ・個人情報を使わない合成 HTML によるパーサのテスト。
 * 実機で確認した表記（年なし MM/DD、漢字曜日 + 数字、〇/空欄）を再現しつつ、
 * 年を推測しないことと、空欄を未記録として安全に扱うことを重点的に確認する。
 */
class ClassAttendanceDetailParserTest {

    private val headers = listOf("回数", "実施日", "曜日時限", "出欠", "試験")

    private fun row(vararg values: String): String =
        "<tr>" + values.joinToString("") { "<td>$it</td>" } + "</tr>"

    private fun table(
        rows: String,
        headerList: List<String> = headers,
        tbodyId: String = "form:jugyoKaisuTbl_data",
    ): String {
        val headerHtml = headerList.joinToString("") { "<th>$it</th>" }
        return """
            <div id="form:jugyoKaisuTbl" class="ui-datatable attendanceInfo">
              <table>
                <thead><tr>$headerHtml</tr></thead>
                <tbody id="$tbodyId">$rows</tbody>
              </table>
            </div>
        """.trimIndent()
    }

    private fun page(inner: String): String =
        """<html><body><div class="mainContent">$inner</div></body></html>"""

    private fun codes(result: ClassAttendanceDetailParser.Result): List<IssueCode> =
        result.issues.map { it.code }

    @Test
    fun parsesSyntheticAttendanceDetailAndKeepsRawValues() {
        val html = page(
            table(
                row("1", "2025/04/10", "木1", "〇", "") +
                    row("2", "2025年4月17日（木）", "木2", "×", "小テスト"),
            ),
        )

        val result = ClassAttendanceDetailParser.parse(html)

        assertFalse(result.issues.toString(), result.hasErrors)
        assertEquals(headers, result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(emptyList<IssueCode>(), codes(result))

        val first = result.rows[0]
        assertEquals("1", first.sessionNoText)
        assertEquals(1, first.sessionNo)
        assertEquals("2025/04/10", first.dateText)
        assertEquals(LocalDate.of(2025, 4, 10), first.date)
        assertEquals("木1", first.dayPeriodText)
        assertEquals(4, first.dayOfWeek)
        assertEquals(listOf(1), first.periods)
        assertEquals("〇", first.attendanceMark)
        assertEquals(ProvisionalAttendance.PRESENT, first.attendance)
        assertEquals("", first.examText)
        assertEquals(listOf("1", "2025/04/10", "木1", "〇", ""), first.rawValues)

        val second = result.rows[1]
        assertEquals(LocalDate.of(2025, 4, 17), second.date)
        assertEquals(listOf(2), second.periods)
        assertEquals("×", second.attendanceMark)
        assertEquals(ProvisionalAttendance.ABSENT, second.attendance)
        assertEquals("小テスト", second.examText)
    }

    @Test
    fun mapsColumnsByHeaderNotByFixedPosition() {
        val headerList = listOf("試験", "出欠", "曜日時限", "実施日", "回数", "備考")
        val html = page(
            table(
                rows = row("レポート", "○", "金3", "2025-05-02", "3", "任意メモ"),
                headerList = headerList,
            ),
        )

        val result = ClassAttendanceDetailParser.parse(html)

        assertFalse(result.hasErrors)
        assertEquals(headerList, result.headers)
        assertEquals(1, result.rows.size)
        assertEquals(3, result.rows.single().sessionNo)
        assertEquals(LocalDate.of(2025, 5, 2), result.rows.single().date)
        assertEquals(5, result.rows.single().dayOfWeek)
        assertEquals(listOf(3), result.rows.single().periods)
        assertEquals(ProvisionalAttendance.PRESENT, result.rows.single().attendance)
        assertEquals("レポート", result.rows.single().examText)
        assertEquals(listOf("レポート", "○", "金3", "2025-05-02", "3", "任意メモ"), result.rows.single().rawValues)

        val warning = result.warnings.single()
        assertEquals(IssueCode.UNUSED_HEADER, warning.code)
        assertEquals("備考", warning.column)
    }

    @Test
    fun missingMainContentIsExplicitError() {
        val result = ClassAttendanceDetailParser.parse("<html><body><p>no content</p></body></html>")

        assertTrue(result.hasErrors)
        assertEquals(listOf(IssueCode.MAIN_CONTENT_NOT_FOUND), codes(result))
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun missingDatatableIsExplicitError() {
        val result = ClassAttendanceDetailParser.parse(page("<div id=\"other\"></div>"))

        assertTrue(result.hasErrors)
        assertEquals(listOf(IssueCode.DATATABLE_NOT_FOUND), codes(result))
    }

    @Test
    fun ambiguousDatatablesAreExplicitError() {
        val html = page(table(row("1", "2025/04/10", "木1", "〇", "")) + table(row("2", "2025/04/17", "木2", "〇", "")))

        val result = ClassAttendanceDetailParser.parse(html)

        assertTrue(result.hasErrors)
        assertEquals(listOf(IssueCode.DATATABLE_AMBIGUOUS), codes(result))
    }

    @Test
    fun missingRequiredHeaderIsExplicitErrorAndNoRowsAreReturned() {
        val html = page(
            table(
                rows = row("1", "2025/04/10", "木1", ""),
                headerList = listOf("回数", "実施日", "曜日時限", "試験"),
            ),
        )

        val result = ClassAttendanceDetailParser.parse(html)

        assertTrue(result.hasErrors)
        assertEquals(listOf(IssueCode.MISSING_REQUIRED_HEADER), codes(result))
        assertEquals("出欠", result.errors.single().column)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun missingTbodyIsExplicitError() {
        val html = page(
            """
            <div id="form:jugyoKaisuTbl" class="ui-datatable attendanceInfo">
              <table><thead><tr><th>回数</th><th>実施日</th><th>曜日時限</th><th>出欠</th><th>試験</th></tr></thead></table>
            </div>
            """.trimIndent(),
        )

        val result = ClassAttendanceDetailParser.parse(html)

        assertTrue(result.hasErrors)
        assertEquals(listOf(IssueCode.TBODY_NOT_FOUND), codes(result))
    }

    @Test
    fun rowCellCountMismatchIsExplicitErrorButRowIsRetained() {
        val html = page(table(row("1", "2025/04/10", "木1", "〇")))

        val result = ClassAttendanceDetailParser.parse(html)

        assertTrue(result.hasErrors)
        assertTrue(codes(result).contains(IssueCode.ROW_CELL_COUNT_MISMATCH))
        assertEquals(1, result.rows.size)
        assertEquals(listOf("1", "2025/04/10", "木1", "〇"), result.rows.single().rawValues)
        assertEquals("", result.rows.single().examText)
    }

    @Test
    fun unknownAttendanceMarkIsExplicitErrorAndRawIsKept() {
        val html = page(table(row("1", "2025/04/10", "木1", "？", "")))

        val result = ClassAttendanceDetailParser.parse(html)

        assertTrue(result.hasErrors)
        assertEquals(listOf(IssueCode.UNKNOWN_ATTENDANCE_MARK), codes(result))
        assertEquals("？", result.rows.single().attendanceMark)
        assertNull(result.rows.single().attendance)
        assertEquals("？", result.rows.single().rawValues[3])
    }

    @Test
    fun blankAttendanceMarkIsAllowedAsUnrecordedRawValue() {
        val html = page(table(row("1", "2025/04/10", "木1", "", "")))

        val result = ClassAttendanceDetailParser.parse(html)

        assertFalse(result.hasErrors)
        assertEquals("", result.rows.single().attendanceMark)
        assertNull(result.rows.single().attendance)
        assertFalse(result.rows.single().isAttendanceRecorded)
    }

    @Test
    fun dateWithoutYearIsParsedAsMonthDayWithoutGuessingYear() {
        val html = page(table(row("1", "4/10", "木1", "〇", "")))

        val result = ClassAttendanceDetailParser.parse(html)

        assertFalse(result.issues.toString(), result.hasErrors)
        assertEquals(emptyList<IssueCode>(), codes(result))

        val row = result.rows.single()
        assertEquals("4/10", row.dateText)
        // 年はセルにないので date にはせず、月日の中間表現だけを持つ。
        assertNull(row.date)
        assertEquals(MonthDay.of(4, 10), row.monthDay)
        assertEquals(LocalDate.of(2025, 4, 10), row.resolveDate(2025))
        assertEquals(LocalDate.of(2026, 4, 10), row.resolveDate(2026))
    }

    @Test
    fun parsesVerifiedClassFormatWithMonthDayKanjiWeekdayAndBlankAttendance() {
        val html = page(
            table(
                row("1", "04/10", "木1", "〇", "") +
                    row("2", "04/17", "木2", "", ""),
            ),
        )

        val result = ClassAttendanceDetailParser.parse(html)

        assertFalse(result.issues.toString(), result.hasErrors)
        assertEquals(emptyList<IssueCode>(), codes(result))
        assertEquals(2, result.rows.size)

        val attended = result.rows[0]
        assertEquals(MonthDay.of(4, 10), attended.monthDay)
        assertNull(attended.date)
        assertEquals(4, attended.dayOfWeek)
        assertEquals(listOf(1), attended.periods)
        assertEquals("〇", attended.attendanceMark)
        assertEquals(ProvisionalAttendance.PRESENT, attended.attendance)
        assertTrue(attended.isAttendanceRecorded)
        assertEquals(LocalDate.of(2025, 4, 10), attended.resolveDate(2025))

        val blank = result.rows[1]
        assertEquals(MonthDay.of(4, 17), blank.monthDay)
        assertNull(blank.date)
        assertEquals("", blank.attendanceMark)
        // 空欄は未記録。欠席などの分類を勝手に補わない。
        assertNull(blank.attendance)
        assertFalse(blank.isAttendanceRecorded)
    }

    @Test
    fun fullDateAlsoProvidesMonthDayAndExplicitYearWins() {
        val html = page(table(row("1", "2025/04/10", "木1", "〇", "")))

        val row = ClassAttendanceDetailParser.parse(html).rows.single()

        assertEquals(LocalDate.of(2025, 4, 10), row.date)
        assertEquals(MonthDay.of(4, 10), row.monthDay)
        // セルに年が明示されている場合は、呼び出し側の文脈年より優先する。
        assertEquals(LocalDate.of(2025, 4, 10), row.resolveDate(2030))
    }

    @Test
    fun yearlessDateWithWeekdaySuffixIsParsedAsMonthDay() {
        val html = page(table(row("1", "4月10日（木）", "木1", "〇", "")))

        val row = ClassAttendanceDetailParser.parse(html).rows.single()

        assertEquals(MonthDay.of(4, 10), row.monthDay)
        assertNull(row.date)
    }

    @Test
    fun monthDayResolutionDoesNotRollInvalidLeapDayIntoMarch() {
        val html = page(table(row("1", "02/29", "木1", "", "")))

        val result = ClassAttendanceDetailParser.parse(html)

        assertFalse(result.issues.toString(), result.hasErrors)
        val row = result.rows.single()
        assertEquals(MonthDay.of(2, 29), row.monthDay)
        assertNull(row.date)
        // 非閏年では 3/1 などへ丸めず、解決不能として null を返す。
        assertNull(row.resolveDate(2025))
        assertEquals(LocalDate.of(2024, 2, 29), row.resolveDate(2024))
    }

    @Test
    fun invalidMonthDayIsExplicitErrorWithoutGuessing() {
        val html = page(table(row("1", "2/30", "木1", "", "")))

        val result = ClassAttendanceDetailParser.parse(html)

        assertTrue(result.hasErrors)
        assertEquals(listOf(IssueCode.DATE_UNPARSED), codes(result))
        assertEquals("2/30", result.rows.single().dateText)
        assertNull(result.rows.single().date)
        assertNull(result.rows.single().monthDay)
    }

    @Test
    fun unknownDayPeriodIsExplicitErrorAndRawIsKept() {
        val html = page(table(row("1", "2025/04/10", "集中", "〇", "")))

        val result = ClassAttendanceDetailParser.parse(html)

        assertTrue(result.hasErrors)
        assertEquals(listOf(IssueCode.DAY_PERIOD_UNPARSED), codes(result))
        assertEquals("集中", result.rows.single().dayPeriodText)
        assertNull(result.rows.single().dayOfWeek)
        assertTrue(result.rows.single().periods.isEmpty())
    }

    @Test
    fun duplicateSessionNoIsWarningNotFatal() {
        val html = page(
            table(
                row("1", "2025/04/10", "木1", "〇", "") +
                    row("1", "2025/04/17", "木2", "〇", ""),
            ),
        )

        val result = ClassAttendanceDetailParser.parse(html)

        assertFalse(result.hasErrors)
        assertEquals(1, result.warnings.size)
        assertEquals(IssueCode.DUPLICATE_SESSION_NO, result.warnings.single().code)
        assertEquals(IssueSeverity.WARNING, result.warnings.single().severity)
    }

    @Test
    fun emptyTbodyIsNotAnErrorAndReturnsNoRows() {
        val html = page(table(""))

        val result = ClassAttendanceDetailParser.parse(html)

        assertFalse(result.hasErrors)
        assertTrue(result.rows.isEmpty())
        assertEquals(headers, result.headers)
    }
}
