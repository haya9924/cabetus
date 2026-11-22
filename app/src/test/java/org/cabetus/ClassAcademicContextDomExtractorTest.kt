package org.cabetus

import kotlinx.serialization.json.JsonPrimitive
import org.cabetus.data.classweb.ClassAcademicContextDomExtractor
import org.cabetus.data.classweb.ClassAcademicContextDomExtractor.ErrorCode
import org.cabetus.data.classweb.ClassAcademicContextDomExtractor.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実データ・Cookie・個人情報を使わない合成 JSON と固定スクリプトの検証。
 * WebView 実機が無い環境でも、抽出コードが年度・学期以外へ触れないことを確認する。
 */
class ClassAcademicContextDomExtractorTest {

    private fun evaluatedString(json: String): String = JsonPrimitive(json).toString()

    private fun successJson(
        year: Int = 2026,
        term: String = "後期",
        selectedText: String = "2026年度 後期",
    ): String = """
        {"ok":true,"error":null,"candidates":[
          {"year":$year,"term":"$term","selectedText":"$selectedText"}
        ],"warnings":[]}
    """.trimIndent()

    @Test
    fun parsesSingleYearAndTermCandidate() {
        val result = ClassAcademicContextDomExtractor.parseEvaluationResult(
            evaluatedString(successJson()),
        )

        assertNull(result.error)
        assertTrue(result.isSuccess)
        assertEquals(2026, result.year)
        assertEquals(Term.SECOND, result.term)
        assertEquals(
            ClassAcademicContextDomExtractor.AcademicContext(2026, Term.SECOND),
            result.context,
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun parsesDirectObjectCallbackAndFirstTerm() {
        val payload = """
            {"ok":true,"candidates":[
              {"year":2025,"term":"前期","selectedText":"2025年度　前期"}
            ],"warnings":["選択欄が非表示のため、表示中の値が一致することを確認しました。"]}
        """.trimIndent()

        val result = ClassAcademicContextDomExtractor.parseEvaluationResult(payload)

        assertNull(result.error)
        assertEquals(2025, result.year)
        assertEquals(Term.FIRST, result.term)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun rejectsMultipleCandidatesInsteadOfChoosingOne() {
        val payload = """
            {"ok":true,"candidates":[
              {"year":2026,"term":"後期","selectedText":"2026年度 後期"},
              {"year":2027,"term":"前期","selectedText":"2027年度 前期"}
            ],"warnings":[]}
        """.trimIndent()

        val result = ClassAcademicContextDomExtractor.parseEvaluationResult(
            evaluatedString(payload),
        )

        assertEquals(ErrorCode.MULTIPLE_CANDIDATES, result.error)
        assertNull(result.context)
        assertTrue(result.warnings.any { it.contains("2件") })
    }

    @Test
    fun rejectsEmptyCandidatesAndMissingPayload() {
        assertEquals(
            ErrorCode.NO_CANDIDATE,
            ClassAcademicContextDomExtractor.parseEvaluationResult(
                evaluatedString("""{"ok":true,"candidates":[],"warnings":[]}"""),
            ).error,
        )
        assertEquals(
            ErrorCode.INVALID_RESULT,
            ClassAcademicContextDomExtractor.parseEvaluationResult(
                evaluatedString("""{"ok":true,"warnings":[]}"""),
            ).error,
        )
    }

    @Test
    fun rejectsInvalidJsonAndUnexpectedShapes() {
        listOf(
            null,
            "",
            "null",
            "not json",
            "[1,2,3]",
            """{"ok":"true","candidates":[]}""",
            """{"error":null}""",
        ).forEach { raw ->
            val result = ClassAcademicContextDomExtractor.parseEvaluationResult(raw)
            assertEquals(raw, ErrorCode.INVALID_RESULT, result.error)
            assertNull(raw, result.context)
        }
    }

    @Test
    fun rejectsYearOutOfRangeAndUnsupportedTerm() {
        assertEquals(
            ErrorCode.YEAR_OUT_OF_RANGE,
            ClassAcademicContextDomExtractor.parseEvaluationResult(
                evaluatedString(successJson(year = 1999, term = "後期", selectedText = "1999年度 後期")),
            ).error,
        )
        assertEquals(
            ErrorCode.YEAR_OUT_OF_RANGE,
            ClassAcademicContextDomExtractor.parseEvaluationResult(
                evaluatedString(successJson(year = 2101, term = "前期", selectedText = "2101年度 前期")),
            ).error,
        )
        // 「通期」など実機で確認していない学期は受け付けない。
        assertEquals(
            ErrorCode.INVALID_RESULT,
            ClassAcademicContextDomExtractor.parseEvaluationResult(
                evaluatedString(successJson(year = 2026, term = "通期", selectedText = "2026年度 通期")),
            ).error,
        )
    }

    @Test
    fun rejectsSelectedTextThatDisagreesWithParsedValues() {
        // 表示中の文字と year/term が食い違う結果は、どちらかを推測せず拒否する。
        val mismatchedYear = ClassAcademicContextDomExtractor.parseEvaluationResult(
            evaluatedString(successJson(year = 2026, term = "後期", selectedText = "2025年度 後期")),
        )
        assertEquals(ErrorCode.INVALID_RESULT, mismatchedYear.error)
        assertNull(mismatchedYear.context)
        assertTrue(mismatchedYear.warnings.any { it.contains("一致しません") })

        assertEquals(
            ErrorCode.INVALID_RESULT,
            ClassAcademicContextDomExtractor.parseEvaluationResult(
                evaluatedString(successJson(year = 2026, term = "後期", selectedText = "2026年度 前期")),
            ).error,
        )
        assertEquals(
            ErrorCode.INVALID_RESULT,
            ClassAcademicContextDomExtractor.parseEvaluationResult(
                evaluatedString(successJson(year = 2026, term = "後期", selectedText = "2026年度")),
            ).error,
        )
    }

    @Test
    fun rejectsMissingOrInvalidStructuredFields() {
        listOf(
            """{"ok":true,"candidates":[{"term":"後期","selectedText":"2026年度 後期"}]}""",
            """{"ok":true,"candidates":[{"year":"2026","term":"後期","selectedText":"2026年度 後期"}]}""",
            """{"ok":true,"candidates":[{"year":2026,"selectedText":"2026年度 後期"}]}""",
            """{"ok":true,"candidates":[{"year":2026,"term":"後期"}]}""",
            """{"ok":true,"candidates":["2026年度 後期"]}""",
        ).forEach { payload ->
            assertEquals(
                payload,
                ErrorCode.INVALID_RESULT,
                ClassAcademicContextDomExtractor.parseEvaluationResult(
                    evaluatedString(payload),
                ).error,
            )
        }
    }

    @Test
    fun mapsScriptErrorCodesAndRejectsOkTrueWithError() {
        val ambiguous = ClassAcademicContextDomExtractor.parseEvaluationResult(
            evaluatedString(
                """{"ok":false,"error":"MULTIPLE_CANDIDATES","candidates":[],"warnings":[]}""",
            ),
        )
        assertEquals(ErrorCode.MULTIPLE_CANDIDATES, ambiguous.error)
        assertNull(ambiguous.context)

        listOf(
            "UNEXPECTED_PAGE",
            "PAGE_NOT_VISIBLE",
            "MAIN_CONTENT_NOT_FOUND",
            "MAIN_CONTENT_AMBIGUOUS",
            "MAIN_CONTENT_NOT_VISIBLE",
            "SELECT_NOT_FOUND",
            "SELECT_LIMIT_EXCEEDED",
            "SELECT_AMBIGUOUS",
            "SELECT_NOT_VISIBLE",
            "NO_SELECTION",
            "UNSUPPORTED_OPTION",
            "YEAR_OUT_OF_RANGE",
            "SCRIPT_FAILED",
        ).forEach { code ->
            assertEquals(code, ErrorCode.valueOf(code), ErrorCode.fromJsCode(code))
        }
        assertEquals(ErrorCode.INVALID_RESULT, ErrorCode.fromJsCode("UNKNOWN_CODE"))
        assertEquals(ErrorCode.INVALID_RESULT, ErrorCode.fromJsCode(null))

        // ok=true なのに error が入っている結果は成功扱いしない。
        val contradictory = ClassAcademicContextDomExtractor.parseEvaluationResult(
            evaluatedString(
                """{"ok":true,"error":"SELECT_AMBIGUOUS","candidates":[
                  {"year":2026,"term":"後期","selectedText":"2026年度 後期"}
                ],"warnings":[]}""",
            ),
        )
        assertEquals(ErrorCode.INVALID_RESULT, contradictory.error)
        assertNull(contradictory.context)
    }

    @Test
    fun targetPathsAreExactAndMatchKnownClassPathConvention() {
        assertEquals(
            listOf(
                "/uprx/up/bs/bsa501/Bsa50101.xhtml",
                "/uprx/up/xu/xut613/Xut61301.xhtml",
                "/uprx/up/km/kmd508/Kmd50801.xhtml",
            ),
            ClassAcademicContextDomExtractor.TARGET_PATHS,
        )
        assertTrue(ClassAcademicContextDomExtractor.isTargetPath("/uprx/up/bs/bsa501/Bsa50101.xhtml"))
        assertTrue(ClassAcademicContextDomExtractor.isTargetPath("/uprx/up/xu/xut613/Xut61301.xhtml"))
        listOf(
            null,
            "",
            "/uprx/up/bs/bsa501/Bsa50102.xhtml",
            "/uprx/up/bs/bsa501/Bsa50101.xhtml/",
            "/uprx/up/bs/bsa501/",
            "/uprx/up/xu/xut613/",
            "/uprx/up/xu/xut613/Xut61302.xhtml",
            "/uprx/up/xut/Xut61301.xhtml",
            "/uprx/up/at/ats501/Ats50101.xhtml",
        ).forEach { path ->
            assertFalse(path.toString(), ClassAcademicContextDomExtractor.isTargetPath(path))
        }
    }

    @Test
    fun script_guardsExactHttpsUrlAndMainContent() {
        val script = ClassAcademicContextDomExtractor.SCRIPT

        assertTrue(script.contains("window.location.protocol !== 'https:'"))
        assertTrue(script.contains("window.location.hostname !== TARGET_HOST"))
        assertTrue(script.contains("TARGET_PATHS.indexOf(window.location.pathname) < 0"))
        assertTrue(script.contains("var TARGET_HOST = 'class.admin.tus.ac.jp';"))
        assertTrue(
            script.contains(
                "var TARGET_PATHS = ['/uprx/up/bs/bsa501/Bsa50101.xhtml', " +
                    "'/uprx/up/xu/xut613/Xut61301.xhtml', '/uprx/up/km/kmd508/Kmd50801.xhtml'];",
            ),
        )
        assertTrue(script.contains("document.visibilityState"))
        assertTrue(script.contains("document.querySelectorAll('.' + MAIN_CONTENT_CLASS)"))
        assertTrue(script.contains("var MAIN_CONTENT_CLASS = 'mainContent';"))
        assertTrue(script.contains("content.querySelectorAll('select')"))
        assertTrue(script.contains("JSON.stringify"))
        // 前方一致・類似パスへ広げていないこと。
        assertFalse(script.contains("Bsa50102"))
        assertFalse(script.contains("Xut61302"))
        assertFalse(script.contains("startsWith"))
    }

    @Test
    fun script_readsOnlySelectedOptionsOfNativeSelectAndNeedsVisibilityGuard() {
        val script = ClassAcademicContextDomExtractor.SCRIPT

        // 選択中の値だけを読む。
        assertTrue(script.contains("var selected = select.selectedOptions;"))
        assertTrue(script.contains("if (selected.length !== 1)"))
        assertTrue(script.contains("selected[0].textContent"))
        // native select が隠れている場合の代替ガード（可視祖先 or 表示一致）。
        assertTrue(script.contains("function insideVisibleMainContent(element)"))
        assertTrue(script.contains("function displayedTextMismatch(select, text)"))
        assertTrue(script.contains("var selectVisible = isVisible(candidateSelect) &&"))
        assertTrue(script.contains("if (matches !== 1) return 'SELECT_NOT_VISIBLE';"))
        // 年度・学期の形式は 4 桁年 + 前期/後期だけ。
        assertTrue(script.contains("var parsed = /^(\\d{4})\\s*年度\\s*(前期|後期)\$/.exec(candidateText);"))
        assertTrue(script.contains("var MIN_YEAR = 2000;"))
        assertTrue(script.contains("var MAX_YEAR = 2100;"))
        assertTrue(script.contains("var TERM_LABELS = ['前期', '後期'];"))
        // 複数 select に選択がある場合は 1 つに決めず拒否する。
        assertTrue(script.contains("if (candidateSelect !== null) return fail('SELECT_AMBIGUOUS');"))
    }

    @Test
    fun script_doesNotReadInputsHiddenFieldsOptionsOrStorage() {
        val script = ClassAcademicContextDomExtractor.SCRIPT.lowercase()

        listOf(
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
            ".options",
            "queryselectorall('option",
            "innerhtml",
            "outerhtml",
            "document.write",
            "eval(",
            "fetch(",
            "xmlhttprequest",
            "sendbeacon",
            "websocket",
            "window.open",
        ).forEach { forbidden ->
            assertFalse("script must not contain $forbidden", script.contains(forbidden))
        }
    }
}
