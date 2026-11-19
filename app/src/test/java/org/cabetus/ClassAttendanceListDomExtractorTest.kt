package org.cabetus

import org.cabetus.data.classweb.ClassAttendanceListDomExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出欠一覧の DOM 抽出結果パーサと固定スクリプトの合成テスト。
 * 実データ・Cookie・個人情報は使わず、固定文字列と合成 JSON だけを検証する。
 */
class ClassAttendanceListDomExtractorTest {

    private fun evaluatedString(json: String): String =
        kotlinx.serialization.json.JsonPrimitive(json).toString()

    private val rowsJson = """
        [
          {"index":1,"label":"合成科目A","token":"0123abcd"},
          {"index":2,"label":"合成科目B","token":"89abcdef"}
        ]
    """.trimIndent()

    @Test
    fun parsesRowsInDisplayOrderWithoutExposingOnclick() {
        val roster = ClassAttendanceListDomExtractor.parseListResult(
            """{"ok":true,"error":null,"rows":$rowsJson,"warnings":[]}""",
        )

        assertNull(roster.error)
        assertEquals(listOf(1, 2), roster.rows.map { it.index })
        assertEquals(listOf("合成科目A", "合成科目B"), roster.rows.map { it.label })
        assertEquals("0123abcd", roster.rows.first().token)
    }

    @Test
    fun rejectsRowsThatAreNotContiguousOrCarryUnsafeTokens() {
        listOf(
            // 行番号の欠落・重複・順序ずれ
            """{"ok":true,"rows":[{"label":"A","token":"0123abcd"}],"warnings":[]}""",
            """{"ok":true,"rows":[
                {"index":2,"label":"A","token":"0123abcd"},
                {"index":1,"label":"B","token":"89abcdef"}],"warnings":[]}""",
            // トークン形式外（onclick の生文字列などをそのまま返した場合）
            """{"ok":true,"rows":[{"index":1,"label":"A","token":"PrimeFaces.addSubmitParam('x')"}],
                "warnings":[]}""",
            // ラベルの欠落
            """{"ok":true,"rows":[{"index":1,"label":"","token":"0123abcd"}],"warnings":[]}""",
            // 空一覧・件数過多・形式不正
            """{"ok":true,"rows":[],"warnings":[]}""",
            """{"ok":"true","rows":[],"warnings":[]}""",
            """{"ok":true,"error":null}""",
            """[]""",
            "not json",
            "",
            "null",
        ).forEach { raw ->
            assertEquals(
                "raw=$raw",
                ClassAttendanceListDomExtractor.ErrorCode.INVALID_RESULT,
                ClassAttendanceListDomExtractor.parseListResult(evaluatedString(raw)).error,
            )
        }
    }

    @Test
    fun mapsKnownListFailuresAndRejectsContradictorySuccess() {
        listOf(
            ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE,
            ClassAttendanceListDomExtractor.ErrorCode.PAGE_NOT_VISIBLE,
            ClassAttendanceListDomExtractor.ErrorCode.MAIN_CONTENT_NOT_FOUND,
            ClassAttendanceListDomExtractor.ErrorCode.MAIN_CONTENT_AMBIGUOUS,
            ClassAttendanceListDomExtractor.ErrorCode.LIST_NOT_FOUND,
            ClassAttendanceListDomExtractor.ErrorCode.LIST_AMBIGUOUS,
            ClassAttendanceListDomExtractor.ErrorCode.LIST_NOT_VISIBLE,
            ClassAttendanceListDomExtractor.ErrorCode.LIST_HEADING_NOT_FOUND,
            ClassAttendanceListDomExtractor.ErrorCode.DETAIL_VISIBLE,
            ClassAttendanceListDomExtractor.ErrorCode.LIST_EMPTY,
            ClassAttendanceListDomExtractor.ErrorCode.LIST_ITEM_MISMATCH,
            ClassAttendanceListDomExtractor.ErrorCode.LIST_ITEM_NOT_VISIBLE,
            ClassAttendanceListDomExtractor.ErrorCode.TOO_LARGE,
            ClassAttendanceListDomExtractor.ErrorCode.SCRIPT_FAILED,
        ).forEach { code ->
            assertEquals(
                code,
                ClassAttendanceListDomExtractor.parseActionResult(
                    evaluatedString("""{"ok":false,"error":"${code.jsCode}"}"""),
                ),
            )
        }

        listOf(
            null,
            "",
            "null",
            "not json",
            """[]""",
            """{"ok":false}""",
            """{"ok":"true","error":null}""",
            """{"ok":true,"error":"LIST_CHANGED"}""",
            """{"ok":false,"error":"UNKNOWN"}""",
        ).forEach { raw ->
            assertEquals(
                "raw=$raw",
                ClassAttendanceListDomExtractor.ErrorCode.INVALID_RESULT,
                ClassAttendanceListDomExtractor.parseActionResult(raw),
            )
        }
        assertNull(
            ClassAttendanceListDomExtractor.parseActionResult(
                evaluatedString("""{"ok":true,"error":null}"""),
            ),
        )
    }

    @Test
    fun screenStateDistinguishesListDetailAndTransitions() {
        val list = ClassAttendanceListDomExtractor.parseScreenState(
            """{"ok":true,"error":null,"listReady":true,"detailPresent":false,
                "listError":null}""",
        )
        assertNull(list.error)
        assertTrue(list.isListOnly)
        assertFalse(list.isDetailOnly)

        val detail = ClassAttendanceListDomExtractor.parseScreenState(
            """{"ok":true,"error":null,"listReady":false,"detailPresent":true,
                "listError":"DETAIL_VISIBLE"}""",
        )
        assertTrue(detail.isDetailOnly)
        assertFalse(detail.isListOnly)

        // 一覧と詳細が併存する遷移中は、どちらとも見なさない。
        val both = ClassAttendanceListDomExtractor.parseScreenState(
            """{"ok":true,"error":null,"listReady":true,"detailPresent":true,
                "listError":"DETAIL_VISIBLE"}""",
        )
        assertFalse(both.isListOnly)
        assertFalse(both.isDetailOnly)

        // 一覧も詳細も無い（読み込み中）。
        val neither = ClassAttendanceListDomExtractor.parseScreenState(
            """{"ok":true,"error":null,"listReady":false,"detailPresent":false,
                "listError":"LIST_NOT_FOUND"}""",
        )
        assertFalse(neither.isListOnly)
        assertFalse(neither.isDetailOnly)
        assertEquals(
            ClassAttendanceListDomExtractor.ErrorCode.LIST_NOT_FOUND,
            neither.listError,
        )
    }

    @Test
    fun screenStateRejectsContradictoryOrMalformedResults() {
        listOf(
            null,
            "",
            "null",
            "not json",
            """[]""",
            """{"ok":false}""",
            """{"ok":false,"error":"UNEXPECTED_PAGE"}""",
            """{"ok":"true","detailPresent":true}""",
            """{"ok":true,"detailPresent":true}""",
            """{"ok":true,"listReady":"true","detailPresent":false}""",
            """{"ok":true,"listReady":true,"detailPresent":false,"listError":"DETAIL_VISIBLE"}""",
            """{"ok":true,"listReady":false,"detailPresent":false,"listError":"UNKNOWN"}""",
        ).forEach { raw ->
            val state = ClassAttendanceListDomExtractor.parseScreenState(raw)
            assertFalse("raw=$raw must not be valid", state.isValid)
            assertFalse("raw=$raw must not be list", state.isListOnly)
            assertFalse("raw=$raw must not be detail", state.isDetailOnly)
        }

        assertEquals(
            ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE,
            ClassAttendanceListDomExtractor.parseScreenState(
                """{"ok":false,"error":"UNEXPECTED_PAGE"}""",
            ).error,
        )
    }

    @Test
    fun clickScriptRequiresMatchingCountIndexAndToken() {
        val row = ClassAttendanceListDomExtractor.Row(1, "合成科目A", "0123abcd")
        val script = ClassAttendanceListDomExtractor.buildClickScript(row, 2)

        assertTrue(script.contains("info.rows.length !== 2"))
        assertTrue(script.contains("info.rows[i].index === 1"))
        assertTrue(script.contains("target.token !== '0123abcd'"))
        assertTrue(script.contains("target.link.click();"))
        // onclick の生文字列は返さない（照合用のハッシュだけを使う）。
        assertFalse(script.contains("onclick:"))
        assertFalse(script.contains("onclick=\""))

        listOf(
            { ClassAttendanceListDomExtractor.buildClickScript(row, 0) },
            {
                ClassAttendanceListDomExtractor.buildClickScript(
                    row,
                    ClassAttendanceListDomExtractor.MAX_ROWS + 1,
                )
            },
            {
                ClassAttendanceListDomExtractor.buildClickScript(
                    ClassAttendanceListDomExtractor.Row(2, "A", "0123abcd"),
                    1,
                )
            },
            {
                ClassAttendanceListDomExtractor.buildClickScript(
                    ClassAttendanceListDomExtractor.Row(1, "A", "not-a-token"),
                    1,
                )
            },
        ).forEach { build ->
            assertTrue(runCatching { build() }.isFailure)
        }
    }

    @Test
    fun scriptsGuardAgainstNonListPagesAndOnlyClickListLinksOrBackButton() {
        val scripts = listOf(
            ClassAttendanceListDomExtractor.LIST_VERIFY_SCRIPT,
            ClassAttendanceListDomExtractor.LIST_SCRIPT,
            ClassAttendanceListDomExtractor.SCREEN_STATE_SCRIPT,
            ClassAttendanceListDomExtractor.BACK_VERIFY_SCRIPT,
            ClassAttendanceListDomExtractor.BACK_SCRIPT,
            ClassAttendanceListDomExtractor.buildClickScript(
                ClassAttendanceListDomExtractor.Row(1, "合成科目A", "0123abcd"),
                1,
            ),
        )

        scripts.forEach { script ->
            // 完全一致ホスト・https・表示状態・一覧構造・見出し・詳細非表示のガードを共有する。
            assertTrue(script.contains("window.location.protocol !== 'https:'"))
            assertTrue(script.contains("window.location.hostname !== TARGET_HOST"))
            assertTrue(script.contains("var TARGET_HOST = 'class.admin.tus.ac.jp';"))
            assertTrue(script.contains("document.visibilityState"))
            assertTrue(script.contains("document.querySelectorAll('.mainContent')"))
            assertTrue(script.contains("LIST_HEADING_NOT_FOUND"))
            assertTrue(script.contains("DETAIL_VISIBLE"))
            assertTrue(script.contains("querySelector('.rishuArea')"))
            assertTrue(script.contains("PrimeFaces"))

            listOf(
                "document.cookie",
                "localStorage",
                "sessionStorage",
                "indexedDB",
                "document.forms",
                "FormData",
                ".value",
                "innerHTML",
                "outerHTML",
                "document.write",
                "eval(",
                "fetch(",
                "XMLHttpRequest",
                "sendBeacon",
                "WebSocket",
                "window.open",
                "console.",
            ).forEach { forbidden ->
                assertFalse(
                    "list script must not contain $forbidden",
                    script.contains(forbidden),
                )
            }
        }

        // クリック対象は一覧リンクと「戻る」だけ。出欠登録などのボタンは押さない。
        assertTrue(ClassAttendanceListDomExtractor.BACK_SCRIPT.contains("info.button.click();"))
        assertFalse(ClassAttendanceListDomExtractor.BACK_SCRIPT.contains(".submit("))
        assertTrue(
            ClassAttendanceListDomExtractor.BACK_VERIFY_SCRIPT.contains("var info = backButton();"),
        )
        assertFalse(
            ClassAttendanceListDomExtractor.BACK_VERIFY_SCRIPT.contains("info.button.click();"),
        )
        assertTrue(
            ClassAttendanceListDomExtractor.SCREEN_STATE_SCRIPT.contains(
                "var info = listCandidates();",
            ),
        )
    }

    @Test
    fun paginationNoticeExplainsThatOnlyTheVisiblePageIsVisited() {
        assertTrue(ClassAttendanceListDomExtractor.PAGINATION_NOTICE.contains("ページ送り"))
        assertTrue(ClassAttendanceListDomExtractor.PAGINATION_NOTICE.contains("現在表示中の一覧"))
    }
}
