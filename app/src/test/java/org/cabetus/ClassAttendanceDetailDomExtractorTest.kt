package org.cabetus

import kotlinx.serialization.json.JsonPrimitive
import org.cabetus.data.classweb.ClassAttendanceDetailDomExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出欠詳細の DOM 抽出結果パーサと固定スクリプトの合成テスト。
 * 実データ・Cookie・個人情報は使わず、固定文字列と合成 JSON だけを検証する。
 */
class ClassAttendanceDetailDomExtractorTest {

    private fun evaluatedString(json: String): String = JsonPrimitive(json).toString()

    @Test
    fun parsesSanitizedTableAndHeadingOnly() {
        val payload = """
            {
              "ok": true,
              "error": null,
              "heading": "学生出欠状況確認",
              "tableHtml": "<table><thead><tr><th>回数</th></tr></thead><tbody id=\"extracted:jugyoKaisuTbl_data\"><tr><td>1</td></tr></tbody></table>",
              "warnings": ["見出しが長いため表示用に短縮しました。"]
            }
        """.trimIndent()

        val result = ClassAttendanceDetailDomExtractor.parseEvaluationResult(
            evaluatedString(payload),
        )

        assertNull(result.error)
        val detail = result.detail!!
        assertEquals("学生出欠状況確認", detail.heading)
        assertTrue(detail.tableHtml.contains("<th>回数</th>"))
        assertEquals(listOf("見出しが長いため表示用に短縮しました。"), result.warnings)
    }

    @Test
    fun masksUnknownHeadingTextSoPreviewDoesNotCarryPageSpecificPii() {
        // 想定外の見出し（科目名・氏名などを含み得る）はプレビューへそのまま返さない。
        val payload = """
            {"ok":true,"error":null,
             "heading":"出欠詳細 合成科目A 山田太郎",
             "tableHtml":"<table></table>","warnings":[]}
        """.trimIndent()

        val result = ClassAttendanceDetailDomExtractor.parseEvaluationResult(
            evaluatedString(payload),
        )

        assertNull(result.error)
        assertEquals("出欠詳細", result.detail!!.heading)
    }

    @Test
    fun acceptsDirectObjectCallbackAndIgnoresCodeLikeFields() {
        // 7桁英数字を正式な科目コードとして採用しない。古い形式の余分なフィールドも無視する。
        val payload = """
            {"ok":true,"heading":"出欠詳細","courseCode":"ABC1234",
             "courseCodeCandidateCount":1,
             "tableHtml":"<table></table>","warnings":[]}
        """.trimIndent()

        val result = ClassAttendanceDetailDomExtractor.parseEvaluationResult(payload)

        assertNull(result.error)
        assertEquals("<table></table>", result.detail!!.tableHtml)
        assertFalse(ClassAttendanceDetailDomExtractor.SCRIPT.contains("courseCode"))
    }

    @Test
    fun reportsKnownErrorCodesAndKeepsWarnings() {
        listOf(
            ClassAttendanceDetailDomExtractor.ErrorCode.UNEXPECTED_PAGE,
            ClassAttendanceDetailDomExtractor.ErrorCode.HEADING_NOT_FOUND,
            ClassAttendanceDetailDomExtractor.ErrorCode.HEADER_MISMATCH,
            ClassAttendanceDetailDomExtractor.ErrorCode.UNSUPPORTED_CELL_SPAN,
            ClassAttendanceDetailDomExtractor.ErrorCode.TOO_LARGE,
        ).forEach { code ->
            val result = ClassAttendanceDetailDomExtractor.parseEvaluationResult(
                evaluatedString(
                    """{"ok":false,"error":"${code.jsCode}","warnings":["w"]}""",
                ),
            )
            assertEquals(code, result.error)
            assertNull(result.detail)
            assertEquals(listOf("w"), result.warnings)
        }
    }

    @Test
    fun rejectsMalformedOrIncompleteSuccessResults() {
        listOf(
            null,
            "",
            "null",
            "not json",
            """[]""",
            """{"ok":false}""",
            """{"ok":"true","error":null}""",
            """{"ok":true,"error":null}""",
            """{"ok":true,"error":null,"tableHtml":""}""",
            """{"ok":true,"error":null,"tableHtml":null}""",
        ).forEach { raw ->
            assertEquals(
                "raw=$raw",
                ClassAttendanceDetailDomExtractor.ErrorCode.INVALID_RESULT,
                ClassAttendanceDetailDomExtractor.parseEvaluationResult(raw).error,
            )
        }
    }

    @Test
    fun verificationParserRejectsContradictoryResults() {
        assertNull(
            ClassAttendanceDetailDomExtractor.parseVerificationResult(
                evaluatedString("""{"ok":true,"error":null}"""),
            ),
        )
        listOf(
            null,
            "",
            "null",
            "not json",
            """[]""",
            """{"ok":false}""",
            """{"ok":"true","error":null}""",
            """{"ok":true,"error":"HEADER_MISMATCH"}""",
            """{"ok":false,"error":"UNKNOWN"}""",
        ).forEach { raw ->
            assertEquals(
                "raw=$raw",
                ClassAttendanceDetailDomExtractor.ErrorCode.INVALID_RESULT,
                ClassAttendanceDetailDomExtractor.parseVerificationResult(raw),
            )
        }
    }

    @Test
    fun verifyScriptChecksExactHttpsHostHeadingAndAttendanceDomOnly() {
        val script = ClassAttendanceDetailDomExtractor.VERIFY_SCRIPT

        assertTrue(script.contains("window.location.protocol !== 'https:'"))
        assertTrue(script.contains("window.location.hostname !== TARGET_HOST"))
        assertTrue(script.contains("var TARGET_HOST = 'class.admin.tus.ac.jp';"))
        assertFalse(script.contains("TARGET_PATHS"))
        assertTrue(script.contains("document.visibilityState"))
        assertTrue(script.contains("document.querySelectorAll('.mainContent')"))
        assertTrue(script.contains("HEADING_NOT_FOUND"))
        assertTrue(script.contains("/出席|出欠/"))
        // 実機では出欠見出しが .mainContent の外（h 系 / .ui-title）にあるため、
        // .mainContent 内を優先しつつページ全体からも可視見出しを探す。
        assertTrue(script.contains(".ui-title"))
        assertTrue(script.contains("headingElement(main) || headingElement(document)"))
        assertFalse(script.contains("document.body.textContent"))
        assertFalse(script.contains("document.documentElement.textContent"))
        assertTrue(script.contains("CONTAINER_ID_SUFFIX = 'jugyoKaisuTbl'"))
        assertTrue(script.contains("TBODY_ID_SUFFIX = 'jugyoKaisuTbl_data'"))
        assertTrue(script.contains("REQUIRED_HEADERS"))
        assertTrue(script.contains("HEADER_MISMATCH"))
        assertTrue(script.contains("UNSUPPORTED_CELL_SPAN"))
        assertTrue(script.contains("JSON.stringify({ ok: ok, error: code })"))

        // 検証だけを行い、科目コード・セル内容・表 HTML は返さない。
        listOf("courseCode", "tableHtml", "slots", "innerHTML", "outerHTML").forEach {
            assertFalse("verification script must not expose $it", script.contains(it))
        }
    }

    @Test
    fun extractionScriptBuildsSanitizedTableAndRefusesSecretsAndCodeGuessing() {
        val script = ClassAttendanceDetailDomExtractor.SCRIPT

        assertTrue(script.contains("escapeHtml(headerTexts[t])"))
        assertTrue(script.contains("escapeHtml(text)"))
        assertTrue(script.contains("JSON.stringify"))
        // プレビュー見出しは既知の固定ラベルだけを返し、生の見出し文字列を返さない。
        assertTrue(script.contains("function headingLabel(heading)"))
        assertTrue(script.contains("'学生出欠状況確認'"))
        assertTrue(script.contains("'出欠状況確認'"))
        assertFalse(script.contains("normalize(info.heading.textContent)"))
        assertFalse(script.contains("document.body.textContent"))

        // 意味不明な 7 桁英数字を正式な科目コードとして扱わない。
        listOf("courseCode", "courseCodeCandidateCount", "[0-9A-Z]{7}").forEach {
            assertFalse("extraction script must not include $it", script.contains(it))
        }

        listOf(
            "document.cookie",
            "localStorage",
            "sessionStorage",
            "indexedDB",
            "document.forms",
            "FormData",
            ".value",
            "outerHTML",
            "innerHTML",
            "document.write",
            "eval(",
            "fetch(",
            "XMLHttpRequest",
            "sendBeacon",
            "WebSocket",
            "window.open",
            "console.",
        ).forEach { forbidden ->
            assertFalse("extraction script must not contain $forbidden", script.contains(forbidden))
        }
    }
}
