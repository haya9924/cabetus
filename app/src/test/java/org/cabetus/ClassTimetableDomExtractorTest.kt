package org.cabetus

import kotlinx.serialization.json.JsonPrimitive
import org.cabetus.data.classweb.ClassTimetableDomExtractor
import org.cabetus.ui.browser.BrowserUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実データ・Cookie・個人情報を使わない合成 JSON と固定スクリプトの検証。
 * WebView 実機が無い環境でも、DOM 抽出コードが余計な情報へ触れないことを確認する。
 */
class ClassTimetableDomExtractorTest {

    private fun evaluatedString(json: String): String = JsonPrimitive(json).toString()

    @Test
    fun parsesMultipleCoursesInSameSlotAndWarnings() {
        val payload = """
            {
              "ok": true,
              "error": null,
              "slots": [
                {"dayOfWeek": 1, "period": 1, "courseName": "合成科目A"},
                {"dayOfWeek": 1, "period": 1, "courseName": "合成科目B"},
                {"dayOfWeek": 6, "period": 7, "courseName": " 合成科目C "}
              ],
              "warnings": ["時限セルのない行をスキップしました。"]
            }
        """.trimIndent()

        val result = ClassTimetableDomExtractor.parseEvaluationResult(evaluatedString(payload))

        assertNull(result.error)
        assertEquals(3, result.slots.size)
        assertEquals(
            listOf(
                ClassTimetableDomExtractor.Slot(1, 1, "合成科目A"),
                ClassTimetableDomExtractor.Slot(1, 1, "合成科目B"),
                ClassTimetableDomExtractor.Slot(6, 7, "合成科目C"),
            ),
            result.slots,
        )
        assertEquals(listOf("時限セルのない行をスキップしました。"), result.warnings)
    }

    @Test
    fun acceptsDirectObjectCallbackAndKeepsInternalWhitespaceDistinct() {
        val payload = """
            {"ok":true,"slots":[
              {"dayOfWeek":2,"period":3,"courseName":"合成科目A"},
              {"dayOfWeek":2,"period":3,"courseName":"合成 科目A"}
            ],"warnings":[]}
        """.trimIndent()

        val result = ClassTimetableDomExtractor.parseEvaluationResult(payload)

        assertNull(result.error)
        assertEquals(2, result.slots.size)
        assertEquals(
            listOf(
                ClassTimetableDomExtractor.Slot(2, 3, "合成科目A"),
                ClassTimetableDomExtractor.Slot(2, 3, "合成 科目A"),
            ),
            result.slots,
        )
    }

    @Test
    fun deduplicatesOnlyExactlyEquivalentCells() {
        val payload = """
            {"ok":true,"slots":[
              {"dayOfWeek":2,"period":3,"courseName":"合成科目A"},
              {"dayOfWeek":2,"period":3,"courseName":"合成科目A"}
            ],"warnings":[]}
        """.trimIndent()

        val result = ClassTimetableDomExtractor.parseEvaluationResult(payload)

        assertNull(result.error)
        assertEquals(
            listOf(ClassTimetableDomExtractor.Slot(2, 3, "合成科目A")),
            result.slots,
        )
    }

    @Test
    fun rejectsUnexpectedPageAndNotVisibleStates() {
        val wrongPage = ClassTimetableDomExtractor.parseEvaluationResult(
            evaluatedString(
                """{"ok":false,"error":"UNEXPECTED_PAGE","slots":[],"warnings":[]}""",
            ),
        )
        assertEquals(ClassTimetableDomExtractor.ErrorCode.UNEXPECTED_PAGE, wrongPage.error)
        assertTrue(wrongPage.slots.isEmpty())

        val notVisible = ClassTimetableDomExtractor.parseEvaluationResult(
            evaluatedString(
                """{"ok":false,"error":"PAGE_NOT_VISIBLE","slots":[],"warnings":[]}""",
            ),
        )
        assertEquals(ClassTimetableDomExtractor.ErrorCode.PAGE_NOT_VISIBLE, notVisible.error)
    }

    @Test
    fun skipsInvalidSlotsAndReportsWarning() {
        val payload = """
            {"ok":true,"slots":[
              {"dayOfWeek":1,"period":1,"courseName":"合成科目A"},
              {"dayOfWeek":0,"period":1,"courseName":"範囲外曜日"},
              {"dayOfWeek":2,"period":8,"courseName":"範囲外時限"},
              {"dayOfWeek":3,"period":2,"courseName":"   "}
            ],"warnings":[]}
        """.trimIndent()

        val result = ClassTimetableDomExtractor.parseEvaluationResult(
            evaluatedString(payload),
        )

        assertNull(result.error)
        assertEquals(listOf(ClassTimetableDomExtractor.Slot(1, 1, "合成科目A")), result.slots)
        assertTrue(result.warnings.any { it.contains("3件") })
    }

    @Test
    fun reportsNoSlotsAndInvalidResults() {
        val noSlots = ClassTimetableDomExtractor.parseEvaluationResult(
            evaluatedString("""{"ok":true,"slots":[],"warnings":[]}"""),
        )
        assertEquals(ClassTimetableDomExtractor.ErrorCode.NO_SLOTS, noSlots.error)

        assertEquals(
            ClassTimetableDomExtractor.ErrorCode.INVALID_RESULT,
            ClassTimetableDomExtractor.parseEvaluationResult(null).error,
        )
        assertEquals(
            ClassTimetableDomExtractor.ErrorCode.INVALID_RESULT,
            ClassTimetableDomExtractor.parseEvaluationResult("not json").error,
        )
        assertEquals(
            ClassTimetableDomExtractor.ErrorCode.INVALID_RESULT,
            ClassTimetableDomExtractor.parseEvaluationResult("""{"ok":true}""").error,
        )
    }

    @Test
    fun script_targetsExactHttpsPagesAndTimetableDomOnly() {
        val script = ClassTimetableDomExtractor.SCRIPT

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
        // 許可リスト以外のパスへ広げていないこと（前方一致・類似パスを含まない）。
        assertFalse(script.contains("Bsa50102"))
        assertFalse(script.contains("Ats50102"))
        assertFalse(script.contains("bsa502"))
        assertTrue(script.contains("document.visibilityState"))
        assertTrue(script.contains("document.querySelector('.rishuArea')"))
        assertTrue(script.contains("area.querySelector('table')"))
        assertTrue(script.contains("classList.contains('colJigen')"))
        assertTrue(script.contains("querySelectorAll('a.jugyo-info')"))
        assertTrue(script.contains("cell.colSpan >= 6"))
        assertTrue(script.contains("textContent"))
        assertTrue(script.contains("JSON.stringify"))
    }

    @Test
    fun targetPaths_matchUiPolicyExactly() {
        assertEquals(
            listOf(
                "/uprx/up/at/ats501/Ats50101.xhtml",
                "/uprx/up/bs/bsa501/Bsa50101.xhtml",
            ),
            ClassTimetableDomExtractor.TARGET_PATHS,
        )
        // ボタン表示判定と抽出スクリプトが同じ許可リストを使う。
        assertEquals(
            ClassTimetableDomExtractor.TARGET_PATHS,
            BrowserUrlPolicy.CLASS_TIMETABLE_PATHS,
        )
    }

    @Test
    fun parsesOfficialCodeAndNormalizesClassroom() {
        val payload = """
            {"ok":true,"slots":[
              {"dayOfWeek":1,"period":2,"courseName":"合成科目A",
               "courseCode":"994357U","room":"葛：E501教室"},
              {"dayOfWeek":3,"period":4,"courseName":"合成科目B",
               "courseCode":"1010996","room":"神：E201"}
            ],"warnings":[]}
        """.trimIndent()

        val result = ClassTimetableDomExtractor.parseEvaluationResult(
            evaluatedString(payload),
        )

        assertNull(result.error)
        assertEquals(
            listOf(
                ClassTimetableDomExtractor.Slot(1, 2, "合成科目A", "994357U", "E501"),
                ClassTimetableDomExtractor.Slot(3, 4, "合成科目B", "1010996", "E201"),
            ),
            result.slots,
        )
    }

    @Test
    fun dropsInvalidCodeAndUnknownClassroomInsteadOfGuessing() {
        val payload = """
            {"ok":true,"slots":[
              {"dayOfWeek":1,"period":1,"courseName":"合成科目A",
               "courseCode":"994357u","room":"E501"},
              {"dayOfWeek":2,"period":2,"courseName":"合成科目B",
               "courseCode":"12345678","room":"不明：E101教室"},
              {"dayOfWeek":3,"period":3,"courseName":"合成科目C",
               "courseCode":"ABC1234","room":"葛："}
            ],"warnings":[]}
        """.trimIndent()

        val result = ClassTimetableDomExtractor.parseEvaluationResult(
            evaluatedString(payload),
        )

        assertNull(result.error)
        assertEquals(
            listOf(
                ClassTimetableDomExtractor.Slot(1, 1, "合成科目A", null, null),
                ClassTimetableDomExtractor.Slot(2, 2, "合成科目B", null, null),
                ClassTimetableDomExtractor.Slot(3, 3, "合成科目C", "ABC1234", null),
            ),
            result.slots,
        )
    }

    @Test
    fun keepsConflictingMetadataForPlannerInsteadOfSilentlyDroppingIt() {
        val payload = """
            {"ok":true,"slots":[
              {"dayOfWeek":1,"period":1,"courseName":"合成科目A",
               "courseCode":"1111111","room":"葛：E101"},
              {"dayOfWeek":1,"period":1,"courseName":"合成科目A",
               "courseCode":"2222222","room":"葛：E202"}
            ],"warnings":[]}
        """.trimIndent()

        val result = ClassTimetableDomExtractor.parseEvaluationResult(
            evaluatedString(payload),
        )

        assertNull(result.error)
        assertEquals(2, result.slots.size)
        assertEquals(setOf("1111111", "2222222"), result.slots.mapNotNull { it.courseCode }.toSet())
    }

    @Test
    fun script_readsOnlyConfirmedDetailDirectChildSequenceForCodeAndClassroom() {
        val script = ClassTimetableDomExtractor.SCRIPT

        assertTrue(script.contains("scrollToRisyuInfo"))
        assertTrue(script.contains("getAttribute('onclick')"))
        assertTrue(script.contains("document.getElementById('jugyoInfo' + detailId)"))
        assertTrue(script.contains("classList.contains('jugyoInfoArea')"))
        assertTrue(script.contains("nameElement.classList.contains('jugyoName')"))
        assertTrue(script.contains("var children = detail.children || [];"))
        assertTrue(script.contains("child.classList.contains('iconArea')"))
        assertTrue(script.contains("child.classList.contains('jugyo-btnArea')"))

        // 実機で確認した「span.jugyoName / 教員 / 教室 / 科目コード / 単位」の
        // 直接子 5 要素がこの順に揃う場合だけコード・教室を採用する。
        assertTrue(script.contains("if (elements.length !== 5) return emptyFields(false);"))
        assertTrue(script.contains("var nameElement = elements[0];"))
        assertTrue(script.contains("var instructorElement = elements[1];"))
        assertTrue(script.contains("var roomElement = elements[2];"))
        assertTrue(script.contains("var codeElement = elements[3];"))
        assertTrue(script.contains("var creditElement = elements[4];"))
        assertTrue(script.contains("/^[神葛野]：.+$/"))
        assertTrue(script.contains("/^[0-9A-Z]{7}$/"))
        assertTrue(script.contains("/^[0-9]+(?:\\.[0-9]+)?\\s*単位$/"))
        assertFalse(script.contains("if (/^[0-9A-Z]{7}$/.test(text))"))
        assertTrue(script.contains("科目詳細の項目配置を確認できないリンク"))
        assertTrue(script.contains("科目名と詳細が一致しないリンク"))
    }

    @Test
    fun script_comparesNamesWithNfkcButKeepsInternalWhitespace() {
        val script = ClassTimetableDomExtractor.SCRIPT

        assertTrue(script.contains("normalized.normalize('NFKC')"))
        assertFalse(script.contains("normalize(text).replace(/\\s+/g, '')"))
    }

    @Test
    fun script_doesNotReadCookiesStorageHiddenInputsOrCredentials() {
        val script = ClassTimetableDomExtractor.SCRIPT.lowercase()

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
