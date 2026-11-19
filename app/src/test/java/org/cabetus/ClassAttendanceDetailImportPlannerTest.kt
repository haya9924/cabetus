package org.cabetus

import org.cabetus.data.classweb.ClassAttendanceDetailImportPlanner
import org.cabetus.data.classweb.ClassAttendanceDetailImportRepository
import org.cabetus.data.classweb.ClassAttendanceDetailDomExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.cabetus.domain.AttendanceStatus

/**
 * 出欠詳細の読み取りプレビューを、実データ・個人情報を使わない合成 HTML で検証する。
 *
 * 第一段階はプレビュー専用のため、保存計画（追加行）や既存 DB とのマージは行わない。
 * 年・時限・出欠記号を推測しないこと、保存経路が常に拒否されることを重点的に確認する。
 */
class ClassAttendanceDetailImportPlannerTest {

    private val headers = listOf("回数", "実施日", "曜日時限", "出欠", "試験")

    private fun row(vararg values: String): String =
        "<tr>" + values.joinToString("") { "<td>$it</td>" } + "</tr>"

    private fun html(rows: String, headerList: List<String> = headers): String {
        val headerHtml = headerList.joinToString("") { "<th>$it</th>" }
        return """
            <html><body><div class="mainContent">
              <div id="form:jugyoKaisuTbl" class="ui-datatable attendanceInfo">
                <table>
                  <thead><tr>$headerHtml</tr></thead>
                  <tbody id="form:jugyoKaisuTbl_data">$rows</tbody>
                </table>
              </div>
            </div></body></html>
        """.trimIndent()
    }

    private fun plan(html: String) = ClassAttendanceDetailImportPlanner.plan(html = html)

    @Test
    fun previewsRowsWithRawValuesAndDoesNotInferYearOrPeriod() {
        val plan = plan(
            html(
                row("1", "04/10", "木1", "〇", "") +
                    row("2", "04/17", "木2", "", "") +
                    row("3", "04/24", "木1・2", "×", ""),
            ),
        )

        assertFalse(plan.errors.toString(), plan.hasErrors)
        assertEquals(3, plan.previewRows.size)

        val first = plan.previewRows[0]
        assertEquals(1, first.sessionNo)
        assertEquals("04/10", first.dateText)
        // 年なしの月日は date にせず、raw 表記のまま保持する。
        assertNull(first.date)
        assertEquals("木1", first.dayPeriodText)
        assertEquals(1, first.period)
        assertEquals("〇", first.mark)
        assertEquals(AttendanceStatus.PRESENT, first.status)

        val blank = plan.previewRows[1]
        assertEquals("", blank.mark)
        // 空欄は未記録。欠席などの分類を勝手に補わない。
        assertEquals(AttendanceStatus.UNRECORDED, blank.status)
        assertTrue(plan.warnings.any { it.contains("未記録") })
        assertTrue(plan.warnings.any { it.contains("年は推測せず") })

        val multiplePeriods = plan.previewRows[2]
        assertEquals("木1・2", multiplePeriods.dayPeriodText)
        assertNull(multiplePeriods.period)
        assertEquals("×", multiplePeriods.mark)
        assertTrue(plan.warnings.any { it.contains("時限は推測しません") })
    }

    @Test
    fun marksUnknownAttendanceMarkAndMissingHeaderAsErrorsWithoutDroppingRows() {
        val unknownMark = plan(html(row("1", "2025/04/10", "木1", "？", "")))
        assertTrue(unknownMark.hasErrors)
        assertTrue(unknownMark.errors.any { it.contains("安全に解釈できない") })
        assertEquals("？", unknownMark.previewRows.single().mark)
        assertEquals(AttendanceStatus.UNRECORDED, unknownMark.previewRows.single().status)

        val missingHeader = plan(
            html(
                rows = row("1", "2025/04/10", "木1", "〇"),
                headerList = listOf("回数", "実施日", "曜日時限", "試験"),
            ),
        )
        assertTrue(missingHeader.hasErrors)
        assertTrue(missingHeader.previewRows.isEmpty())
    }

    @Test
    fun explicitDatesAreKeptAsReadWithoutGuessingOthers() {
        val plan = plan(html(row("1", "2025/04/10", "木1", "〇", "")))

        assertFalse(plan.errors.toString(), plan.hasErrors)
        assertEquals(LocalDate.of(2025, 4, 10), plan.previewRows.single().date)
        assertFalse(plan.warnings.any { it.contains("年は推測せず") })
    }

    @Test
    fun emptyTableIsReportedAsErrorForPreview() {
        val plan = plan(html(""))

        assertTrue(plan.hasErrors)
        assertTrue(plan.errors.any { it.contains("出席回を確認できません") })
        assertTrue(plan.previewRows.isEmpty())
    }

    @Test
    fun repositorySaveIsAlwaysBlockedAndReportsReadOnlyReason() = runBlocking {
        val detail = ClassAttendanceDetailDomExtractor.ExtractedDetail(
            heading = "出欠詳細 合成科目A",
            tableHtml = "<table><thead><tr><th>回数</th></tr></thead>" +
                "<tbody id=\"extracted:jugyoKaisuTbl_data\">" +
                "<tr><td>1</td></tr></tbody></table>",
            warnings = emptyList(),
        )

        val result = ClassAttendanceDetailImportRepository().save(detail)

        assertTrue(result.blocked)
        assertTrue(result.blockedReasons.any { it.contains("読み取りプレビュー専用") })
        assertTrue(result.blockedReasons.any { it.contains("自動巡回") })
    }
}
