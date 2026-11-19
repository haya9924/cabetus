package org.cabetus

import kotlinx.serialization.json.JsonPrimitive
import org.cabetus.data.classweb.ClassTimetableDomExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ボタン表示・クリック前再検証用スクリプトの合成テスト。
 * 実データ・Cookie・個人情報は使わず、固定文字列と合成 JSON だけを検証する。
 */
class ClassTimetablePageVerificationTest {

    private fun evaluatedString(json: String): String = JsonPrimitive(json).toString()

    @Test
    fun verifyScript_checksExactUrlVisibilityTableAndWeekdayHeader() {
        val script = ClassTimetableDomExtractor.VERIFY_SCRIPT

        assertTrue(script.contains("window.location.protocol !== 'https:'"))
        assertTrue(script.contains("window.location.hostname !== TARGET_HOST"))
        assertTrue(script.contains("TARGET_PATHS.indexOf(window.location.pathname) < 0"))
        assertTrue(script.contains("var TARGET_HOST = 'class.admin.tus.ac.jp';"))
        assertTrue(
            script.contains(
                "var TARGET_PATHS = ['/uprx/up/at/ats501/Ats50101.xhtml', " +
                    "'/uprx/up/bs/bsa501/Bsa50101.xhtml'];",
            ),
        )

        // 実際に表示中の時間割領域・表・月〜土ヘッダーを確認する。
        assertTrue(script.contains("document.visibilityState"))
        assertTrue(script.contains("document.querySelector('.rishuArea')"))
        assertTrue(script.contains("area.querySelector('table')"))
        assertTrue(script.contains("table.getClientRects().length === 0"))
        assertTrue(script.contains("window.getComputedStyle(table)"))
        assertTrue(script.contains("tableStyle.display === 'none'"))
        assertTrue(script.contains("tableStyle.visibility !== 'visible'"))
        assertTrue(script.contains("table.rows.length < 2"))
        assertTrue(script.contains("var WEEKDAYS = ['月', '火', '水', '木', '金', '土'];"))
        assertTrue(script.contains("HEADER_MISMATCH"))
        assertTrue(script.contains("JSON.stringify({ ok: ok, error: code })"))
    }

    @Test
    fun verifyScript_returnsOnlyStatusAndDoesNotReadCourseDataOrCredentials() {
        val script = ClassTimetableDomExtractor.VERIFY_SCRIPT.lowercase()

        // 検証だけを行い、科目名・セル内容・ページ全体は返さない。
        listOf(
            "a.jugyo-info",
            "coursename",
            "slots",
            "addjavascriptinterface",
            "cookie",
            "localstorage",
            "sessionstorage",
            "indexeddb",
            "document.forms",
            "formdata",
            "input",
            "hidden",
            ".value",
            "innerhtml",
            "outerhtml",
            "document.write",
            "eval(",
            "fetch(",
            "xmlhttprequest",
            "sendbeacon",
            "websocket",
            "window.open",
            "console.",
        ).forEach { forbidden ->
            assertFalse("verification script must not contain $forbidden", script.contains(forbidden))
        }
    }

    @Test
    fun verifyParser_acceptsExplicitSuccessFromCallbackForms() {
        assertNull(
            ClassTimetableDomExtractor.parseVerificationResult(
                """{"ok":true,"error":null}""",
            ),
        )
        assertNull(
            ClassTimetableDomExtractor.parseVerificationResult(
                evaluatedString("""{"ok":true,"error":null}"""),
            ),
        )
    }

    @Test
    fun verifyParser_mapsKnownFailureCodes() {
        listOf(
            ClassTimetableDomExtractor.ErrorCode.UNEXPECTED_PAGE,
            ClassTimetableDomExtractor.ErrorCode.PAGE_NOT_VISIBLE,
            ClassTimetableDomExtractor.ErrorCode.AREA_NOT_FOUND,
            ClassTimetableDomExtractor.ErrorCode.TABLE_NOT_FOUND,
            ClassTimetableDomExtractor.ErrorCode.TABLE_NOT_VISIBLE,
            ClassTimetableDomExtractor.ErrorCode.ROWS_NOT_FOUND,
            ClassTimetableDomExtractor.ErrorCode.HEADER_MISMATCH,
            ClassTimetableDomExtractor.ErrorCode.SCRIPT_FAILED,
        ).forEach { code ->
            assertEquals(
                code,
                ClassTimetableDomExtractor.parseVerificationResult(
                    evaluatedString("""{"ok":false,"error":"${code.jsCode}"}"""),
                ),
            )
        }
    }

    @Test
    fun verifyParser_rejectsMalformedOrContradictoryResults() {
        listOf(
            null,
            "",
            "null",
            "not json",
            """[]""",
            """{"ok":false}""",
            """{"ok":"true","error":null}""",
            """{"ok":true,"error":"AREA_NOT_FOUND"}""",
            """{"ok":true,"error":""}""",
            """{"ok":false,"error":"UNKNOWN"}""",
        ).forEach { raw ->
            assertEquals(
                "raw=$raw",
                ClassTimetableDomExtractor.ErrorCode.INVALID_RESULT,
                ClassTimetableDomExtractor.parseVerificationResult(raw),
            )
        }
    }

    @Test
    fun extractionScript_usesTheSameStrictPageAndTableGuard() {
        val script = ClassTimetableDomExtractor.SCRIPT

        assertTrue(script.contains("function pageError()"))
        assertTrue(script.contains("function timetableTable()"))
        assertTrue(script.contains("var tableInfo = timetableTable();"))
        assertTrue(script.contains("var pageFailure = pageError();"))
        assertTrue(script.contains("if (tableInfo.error) return fail(tableInfo.error);"))
        assertEquals(
            ClassTimetableDomExtractor.TARGET_PATHS,
            org.cabetus.ui.browser.BrowserUrlPolicy.CLASS_TIMETABLE_PATHS,
        )
    }
}
