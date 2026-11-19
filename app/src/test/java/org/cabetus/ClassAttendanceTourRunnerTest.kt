package org.cabetus

import kotlinx.coroutines.test.runTest
import org.cabetus.data.classweb.ClassAttendanceDetailDomExtractor
import org.cabetus.data.classweb.ClassAttendanceImportPreview
import org.cabetus.data.classweb.ClassAttendanceListDomExtractor
import org.cabetus.data.classweb.ClassAttendanceTour
import org.cabetus.ui.browser.ClassAttendanceTourRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出欠一覧の前景巡回ランナーを、WebView を使わない合成 DOM シミュレータで検証する。
 *
 * 実データ・Cookie・個人情報は使わない。固定スクリプトごとに合成 JSON を返し、
 * 「一覧 → 詳細 → 一覧」の非同期な DOM 遷移、ユーザー中止、一覧以外の誤発火防止、
 * タイムアウト、WebView 破棄、一覧変化時の停止、部分結果の保持を確認する。
 * 読み取った内容はプレビューにだけ使い、保存は行わない（ランナーは State を返すだけ）。
 */
class ClassAttendanceTourRunnerTest {

    private val consoleUrl = "https://class.admin.tus.ac.jp/uprx/up/bs/bsa500/Bsa50101.xhtml"

    /**
     * 実機の CLASS を模した最小の DOM シミュレータ。
     * クリック直後に一覧と詳細が一時的に併存する状態を再現する。
     */
    private class FakeClassDom(
        rows: List<Pair<String, String>>,
        /** クリック後、一覧が消えて詳細だけになるまでの画面確認回数。 */
        private val pollsUntilDetailOnly: Int = 2,
        /** 「戻る」後、詳細が消えて一覧だけに戻るまでの画面確認回数。 */
        private val pollsUntilListOnly: Int = 2,
        /** 詳細が表示されない（タイムアウトさせる）場合に true。 */
        private val neverShowDetail: Boolean = false,
        /** 一覧ではなく別画面を表示している場合のエラーコード。 */
        private val notAListReason: String? = null,
        /** 巡回中に一覧の並び・リンク先が変わったことにする。 */
        private val rosterChangesAfterBack: Boolean = false,
        /** 行リンクのクリック結果を失敗させる場合のエラーコード。 */
        private val clickError: String? = null,
        /** 「戻る」クリック結果を失敗させる場合のエラーコード。 */
        private val backClickError: String? = null,
        /** 詳細テーブルの抽出結果を失敗させる場合のエラーコード。 */
        private val detailError: String? = null,
    ) {
        private var currentRows: List<Pair<String, String>> = rows.toList()
        private var detailVisible = false
        private var listVisible = notAListReason == null
        private var detailPollsLeft = 0
        private var listPollsLeft = 0

        var evaluations = 0
            private set

        val clickedRowIndexes = mutableListOf<Int>()
        val executedScripts = mutableListOf<String>()

        /** 一覧と詳細が併存している遷移中に詳細スクリプトを実行した回数（0 であるべき）。 */
        var detailReadWhileListVisible = 0
            private set

        /** 行リンクがクリックされた直後に呼ばれる（テスト側で中止・破棄を注入する）。 */
        var onRowClick: (Int) -> Unit = {}

        private fun rosterJson(): String =
            currentRows.mapIndexed { position, (label, token) ->
                """{"index":${position + 1},"label":"$label","token":"$token"}"""
            }.joinToString(",", prefix = "[", postfix = "]")

        /** DOM の非同期遷移を 1 確認分だけ進める。 */
        private fun advanceTransitions() {
            if (detailVisible) {
                if (detailPollsLeft > 0) {
                    detailPollsLeft--
                    if (detailPollsLeft == 0) listVisible = false
                }
            } else if (listPollsLeft > 0) {
                listPollsLeft--
                if (listPollsLeft == 0) {
                    listVisible = true
                    if (rosterChangesAfterBack) {
                        currentRows = currentRows.mapIndexed { position, row ->
                            if (position == 1) row.first to "ffffffff" else row
                        }
                    }
                }
            }
        }

        fun screenStateJson(): String {
            advanceTransitions()
            if (notAListReason == "UNEXPECTED_PAGE") {
                return """{"ok":false,"error":"UNEXPECTED_PAGE","listReady":false,""" +
                    """"detailPresent":false,"listError":null}"""
            }
            if (notAListReason != null) {
                val detail = notAListReason == "DETAIL_VISIBLE"
                return """{"ok":true,"error":null,"listReady":false,""" +
                    """"detailPresent":$detail,"listError":"$notAListReason"}"""
            }
            // SCREEN_STATE の listReady は「詳細ガードを掛けない一覧判定」の可否。
            // 遷移中に一覧と詳細が併存する場合は listReady と detailPresent が
            // 両方 true になり、ランナーはどちらとも見なさず再試行する。
            val listReady = listVisible
            val listError = if (listVisible) null else "LIST_NOT_VISIBLE"
            val errorJs = if (listError == null) "null" else "\"$listError\""
            return """{"ok":true,"error":null,"listReady":$listReady,""" +
                """"detailPresent":$detailVisible,"listError":$errorJs}"""
        }

        fun listVerifyJson(): String = if (listVisible && !detailVisible) {
            """{"ok":true,"error":null}"""
        } else {
            """{"ok":false,"error":"DETAIL_VISIBLE"}"""
        }

        fun listJson(): String = if (listVisible && !detailVisible) {
            """{"ok":true,"error":null,"rows":${rosterJson()},"warnings":[]}"""
        } else {
            val code = if (detailVisible) "DETAIL_VISIBLE" else "LIST_NOT_VISIBLE"
            """{"ok":false,"error":"$code","rows":[],"warnings":[]}"""
        }

        fun clickJson(script: String): String {
            if (clickError != null) return """{"ok":false,"error":"$clickError"}"""
            if (!listVisible || detailVisible) {
                return """{"ok":false,"error":"LIST_CHANGED"}"""
            }
            val index = Regex("\\.index === (\\d+)").find(script)?.groupValues?.get(1)?.toInt()
            if (index == null || index !in 1..currentRows.size) {
                return """{"ok":false,"error":"LIST_CHANGED"}"""
            }
            clickedRowIndexes += index
            if (neverShowDetail) {
                // クリック後に一覧が消え、詳細も現れない状態を再現する。
                listVisible = false
                detailVisible = false
            } else {
                detailVisible = true
                detailPollsLeft = pollsUntilDetailOnly
            }
            onRowClick(index)
            return """{"ok":true,"error":null}"""
        }

        fun detailVerifyJson(): String {
            if (!detailVisible) return """{"ok":false,"error":"MAIN_CONTENT_NOT_FOUND"}"""
            if (listVisible) detailReadWhileListVisible++
            return """{"ok":true,"error":null}"""
        }

        val identityLabels = mutableListOf<String>()

        /**
         * 科目名の一致確認（identity）スクリプトへの応答。
         * 実機同様、一覧で開いた行の科目名と詳細の見出しが一致した場合だけ true を返す。
         */
        fun identityJson(script: String): String {
            val label = Regex("""var ROW_LABEL = ("(?:[^"\\]|\\.)*");""")
                .find(script)
                ?.groupValues
                ?.get(1)
                ?.removeSurrounding("\"")
                ?: return """{"ok":false,"error":"INVALID_RESULT","identity":false}"""
            identityLabels += label
            if (!detailVisible) {
                return """{"ok":false,"error":"MAIN_CONTENT_NOT_FOUND","identity":false}"""
            }
            val expected = clickedRowIndexes.lastOrNull()
                ?.let { index -> currentRows.getOrNull(index - 1)?.first }
            return """{"ok":true,"error":null,"identity":${label == expected}}"""
        }

        fun backVerifyJson(): String =
            if (detailVisible) """{"ok":true,"error":null}""" else
                """{"ok":false,"error":"BACK_NOT_FOUND"}"""

        fun backJson(): String {
            if (backClickError != null) return """{"ok":false,"error":"$backClickError"}"""
            if (!detailVisible) return """{"ok":false,"error":"BACK_NOT_FOUND"}"""
            detailVisible = false
            listVisible = false
            detailPollsLeft = 0
            listPollsLeft = pollsUntilListOnly
            return """{"ok":true,"error":null}"""
        }

        fun detailJson(): String = when {
            !detailVisible ->
                """{"ok":false,"error":"MAIN_CONTENT_NOT_FOUND","tableHtml":null,"warnings":[]}"""
            detailError != null ->
                """{"ok":false,"error":"$detailError","tableHtml":null,"warnings":[]}"""
            else ->
                """{"ok":true,"error":null,"heading":"出欠詳細","tableHtml":"<table></table>",""" +
                    """"warnings":[]}"""
        }

        fun enqueue(script: String, callback: (String?) -> Unit) {
            evaluations++
            executedScripts += script
            val raw = when {
                script == ClassAttendanceListDomExtractor.SCREEN_STATE_SCRIPT -> screenStateJson()
                script == ClassAttendanceListDomExtractor.LIST_VERIFY_SCRIPT -> listVerifyJson()
                script == ClassAttendanceListDomExtractor.LIST_SCRIPT -> listJson()
                script == ClassAttendanceDetailDomExtractor.VERIFY_SCRIPT -> detailVerifyJson()
                script == ClassAttendanceDetailDomExtractor.SCRIPT -> detailJson()
                script.contains("var ROW_LABEL =") -> identityJson(script)
                script == ClassAttendanceListDomExtractor.BACK_VERIFY_SCRIPT -> backVerifyJson()
                script == ClassAttendanceListDomExtractor.BACK_SCRIPT -> backJson()
                script.contains("target.link.click();") -> clickJson(script)
                else -> error("unexpected script")
            }
            callback(raw)
        }
    }

    private val preview = ClassAttendanceImportPreview(
        heading = "出欠詳細",
        rows = emptyList(),
        warnings = emptyList(),
        errors = emptyList(),
    )

    private fun runner(
        dom: FakeClassDom,
        currentUrl: () -> String? = { consoleUrl },
        isAlive: () -> Boolean = { true },
        previewLoader: suspend (ClassAttendanceDetailDomExtractor.ExtractedDetail) ->
            ClassAttendanceImportPreview = { preview },
        stepTimeoutMs: Long = 5_000L,
        totalTimeoutMs: Long = 30_000L,
    ) = ClassAttendanceTourRunner(
        enqueue = dom::enqueue,
        currentUrl = currentUrl,
        isPageLoading = { false },
        isAlive = isAlive,
        previewLoader = previewLoader,
        stepTimeoutMs = stepTimeoutMs,
        totalTimeoutMs = totalTimeoutMs,
        pollIntervalMs = 1L,
    )

    private val rows = listOf("合成科目A" to "0123abcd", "合成科目B" to "89abcdef")

    private fun started(outcome: ClassAttendanceTourRunner.Outcome): ClassAttendanceTour.State =
        (outcome as ClassAttendanceTourRunner.Outcome.Started).state

    @Test
    fun visitsEveryRowInOrderAndWaitsForRealDomTransitions() = runTest {
        val dom = FakeClassDom(rows, pollsUntilDetailOnly = 2, pollsUntilListOnly = 3)
        val states = mutableListOf<ClassAttendanceTour.State>()

        val state = started(
            runner(dom).run(onState = { states += it }, shouldCancel = { false }),
        )

        assertEquals(ClassAttendanceTour.Phase.COMPLETE, state.phase)
        assertEquals(2, state.doneCount)
        assertEquals(0, state.failedCount)
        assertEquals(0, state.skippedCount)
        assertTrue(state.isTerminal)
        assertFalse(state.isPartial)
        assertNull(state.stopReason)
        assertFalse(state.cancelled)

        // 一覧の並び順どおりに 1 回ずつだけリンクを開く。
        assertEquals(listOf(1, 2), dom.clickedRowIndexes)
        // 一覧と詳細が併存している遷移中に、古い DOM を詳細として読まない。
        assertEquals(0, dom.detailReadWhileListVisible)

        // 「開く → 詳細検証 → 抽出 → 戻る → 一覧再検証」の各段階を必ず通る。
        listOf(
            ClassAttendanceTour.Phase.CLICK_ROW,
            ClassAttendanceTour.Phase.VERIFY_DETAIL,
            ClassAttendanceTour.Phase.EXTRACT_DETAIL,
            ClassAttendanceTour.Phase.CLICK_BACK,
            ClassAttendanceTour.Phase.VERIFY_LIST,
            ClassAttendanceTour.Phase.COMPLETE,
        ).forEach { phase ->
            assertTrue("phase $phase", states.any { it.phase == phase })
        }
        assertTrue(states.all { it.stopReason == null })
        assertTrue(states.none { it.cancelled })

        // 読み取り済みの行はプレビューを保持し、読み取り回数は一覧の件数を超えない。
        assertTrue(state.items.all { it.status == ClassAttendanceTour.ItemStatus.DONE })
        assertTrue(state.items.all { it.preview == preview })
        assertTrue(dom.clickedRowIndexes.distinct().size == dom.clickedRowIndexes.size)
    }

    @Test
    fun refusesToStartWhenTheVisiblePageIsNotTheAttendanceList() = runTest {
        listOf(
            "DETAIL_VISIBLE" to
                ClassAttendanceListDomExtractor.ErrorCode.DETAIL_VISIBLE.userMessage,
            "LIST_HEADING_NOT_FOUND" to
                ClassAttendanceListDomExtractor.ErrorCode.LIST_HEADING_NOT_FOUND.userMessage,
            "UNEXPECTED_PAGE" to
                ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE.userMessage,
        ).forEach { (reason, expectedMessage) ->
            val dom = FakeClassDom(rows, notAListReason = reason)
            val states = mutableListOf<ClassAttendanceTour.State>()

            val outcome = runner(dom).run(
                onState = { states += it },
                shouldCancel = { false },
            )

            val notStarted = outcome as ClassAttendanceTourRunner.Outcome.NotStarted
            assertEquals(reason, expectedMessage, notStarted.message)
            // 一覧を読めないまま行のリンクをクリックしない。
            assertTrue(reason, dom.clickedRowIndexes.isEmpty())
            assertTrue(reason, states.isEmpty())
        }
    }

    @Test
    fun doesNotStartWhenTheBrowserIsNotOnTheClassConsole() = runTest {
        listOf(
            "https://class.admin.tus.ac.jp.evil.example/uprx/up/bs/bsa500/Bsa50101.xhtml",
            "http://class.admin.tus.ac.jp/uprx/up/bs/bsa500/Bsa50101.xhtml",
            "https://example.com/",
            "",
        ).forEach { url ->
            val dom = FakeClassDom(rows)
            val published = mutableListOf<ClassAttendanceTour.State>()

            val outcome = runner(dom, currentUrl = { url }).run(
                onState = { published += it },
                shouldCancel = { false },
            )

            val notStarted = outcome as ClassAttendanceTourRunner.Outcome.NotStarted
            assertEquals(
                url,
                ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE.userMessage,
                notStarted.message,
            )
            // CLASS の完全一致 https ホストでなければ、行のクリックも巡回開始もしない。
            assertTrue(url, dom.clickedRowIndexes.isEmpty())
            assertTrue(url, published.isEmpty())
        }
    }

    @Test
    fun doesNotStartWhenCancelledBeforeTheFirstRead() = runTest {
        val dom = FakeClassDom(rows)

        val outcome = runner(dom).run(onState = { }, shouldCancel = { true })

        val notStarted = outcome as ClassAttendanceTourRunner.Outcome.NotStarted
        assertTrue(notStarted.message.contains("中止"))
        assertTrue(dom.clickedRowIndexes.isEmpty())
        // 中止時は一覧の読み取りすら行わない（画面確認だけ）。
        assertFalse(
            dom.executedScripts.contains(ClassAttendanceListDomExtractor.LIST_SCRIPT),
        )
    }

    @Test
    fun userCancellationDuringDetailTransitionStopsAsCancelledWithPartialResults() = runTest {
        // クリック後の遷移が終わらない状態で、数回の確認後にユーザーが中止する。
        val dom = FakeClassDom(rows, neverShowDetail = true)
        var cancelled = false
        var evaluationsAtCancel = -1
        dom.onRowClick = {
            cancelled = false
            evaluationsAtCancel = dom.evaluations + 3
        }
        val states = mutableListOf<ClassAttendanceTour.State>()

        val state = started(
            runner(dom).run(
                onState = { states += it },
                shouldCancel = { cancelled || (evaluationsAtCancel > 0 && dom.evaluations >= evaluationsAtCancel) },
            ),
        )

        assertTrue(state.cancelled)
        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertTrue(state.isTerminal)
        assertNotNull(state.stopReason)
        assertTrue(state.stopReason!!.contains("中止"))
        assertEquals(1, dom.clickedRowIndexes.size)
        // 中止時は処理中の行を失敗扱いにせず、未処理として残す。
        assertEquals(ClassAttendanceTour.ItemStatus.SKIPPED, state.items[0].status)
        assertEquals(0, state.failedCount)
        assertEquals(2, state.skippedCount)
        assertTrue(state.isPartial)
        assertEquals(-1, state.currentIndex)
        assertTrue(states.last().cancelled)
        assertTrue(states.last().isTerminal)

        // 中止後は追加のリンクを開かない。
        val clicksAtStop = dom.clickedRowIndexes.size
        cancelled = true
        assertEquals(clicksAtStop, dom.clickedRowIndexes.size)
    }

    @Test
    fun userCancellationAfterACompletedRowKeepsThePreviewAndDoesNotFailTheNextRow() = runTest {
        val dom = FakeClassDom(rows)
        var cancelled = false
        // 2 行目のリンクを開いた直後にユーザーが中止する。
        dom.onRowClick = { index -> if (index == 2) cancelled = true }

        val state = started(
            runner(dom).run(onState = { }, shouldCancel = { cancelled }),
        )

        assertTrue(state.cancelled)
        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        // 1 行目は読み取り済みのまま保持し、2 行目だけを未処理にする。
        assertEquals(ClassAttendanceTour.ItemStatus.DONE, state.items[0].status)
        assertEquals(preview, state.items[0].preview)
        assertEquals(ClassAttendanceTour.ItemStatus.SKIPPED, state.items[1].status)
        assertEquals(1, state.doneCount)
        assertEquals(0, state.failedCount)
        assertEquals(1, state.skippedCount)
        assertTrue(state.isPartial)
        assertEquals(listOf(1, 2), dom.clickedRowIndexes)
    }

    @Test
    fun stopsWithTimeoutWhenTheDetailNeverAppears() = runTest {
        val dom = FakeClassDom(rows, neverShowDetail = true)

        val state = started(
            runner(dom, stepTimeoutMs = 50L).run(onState = { }, shouldCancel = { false }),
        )

        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertEquals(
            ClassAttendanceListDomExtractor.ErrorCode.TIMEOUT.userMessage,
            state.stopReason,
        )
        assertEquals(1, dom.clickedRowIndexes.size)
        assertEquals(1, state.failedCount)
        assertEquals(1, state.skippedCount)
        assertTrue(state.isPartial)
        assertFalse(state.cancelled)
    }

    @Test
    fun stopsWhenTheListChangesAfterReturningFromDetail() = runTest {
        val dom = FakeClassDom(rows, rosterChangesAfterBack = true)

        val state = started(
            runner(dom).run(onState = { }, shouldCancel = { false }),
        )

        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertEquals(
            ClassAttendanceListDomExtractor.ErrorCode.LIST_CHANGED.userMessage,
            state.stopReason,
        )
        // 最初の 1 行だけを読み、並びが変わった後は別の科目を開かない。
        assertEquals(listOf(1), dom.clickedRowIndexes)
        assertEquals(1, state.doneCount)
        assertTrue(state.isPartial)
    }

    @Test
    fun preservesPartialResultsWhenTheTotalTimeLimitIsReached() = runTest {
        val dom = FakeClassDom(rows, neverShowDetail = true)

        val state = started(
            runner(dom, stepTimeoutMs = 10_000L, totalTimeoutMs = 100L).run(
                onState = { },
                shouldCancel = { false },
            ),
        )

        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertNotNull(state.stopReason)
        assertTrue(state.stopReason!!.contains("制限時間"))
        // 全体の制限時間切れでは処理中の行を失敗扱いにせず、部分結果として残す。
        assertEquals(0, state.failedCount)
        assertEquals(2, state.skippedCount)
        assertEquals(1, dom.clickedRowIndexes.size)
        assertFalse(state.cancelled)
        assertTrue(state.isPartial)
    }

    @Test
    fun stopsWithoutClickingAgainWhenTheWebViewIsDestroyed() = runTest {
        val dom = FakeClassDom(rows)
        var alive = true
        dom.onRowClick = { alive = false }

        val state = started(
            runner(dom, isAlive = { alive }).run(onState = { }, shouldCancel = { false }),
        )

        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertNotNull(state.stopReason)
        assertTrue(state.stopReason!!.contains("画面を破棄"))
        assertEquals(1, dom.clickedRowIndexes.size)
        assertTrue(state.isPartial)
        assertFalse(state.cancelled)
    }

    @Test
    fun stopsWhenTheDetailExtractionReportsAnUnsupportedStructure() = runTest {
        val dom = FakeClassDom(rows, detailError = "UNSUPPORTED_CELL_SPAN")

        val state = started(
            runner(dom).run(onState = { }, shouldCancel = { false }),
        )

        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertEquals(
            ClassAttendanceDetailDomExtractor.ErrorCode.UNSUPPORTED_CELL_SPAN.userMessage,
            state.stopReason,
        )
        // 読み取りに失敗した行は DONE にせず、後続の科目も開かない。
        assertEquals(ClassAttendanceTour.ItemStatus.FAILED, state.items[0].status)
        assertEquals(ClassAttendanceTour.ItemStatus.SKIPPED, state.items[1].status)
        assertNull(state.items[0].preview)
        assertEquals(listOf(1), dom.clickedRowIndexes)
        assertEquals(0, state.doneCount)
        assertTrue(state.isPartial)
    }

    @Test
    fun stopsWhenTheBackButtonClickFailsWithoutOpeningFurtherRows() = runTest {
        val dom = FakeClassDom(rows, backClickError = "BACK_FAILED")

        val state = started(
            runner(dom).run(onState = { }, shouldCancel = { false }),
        )

        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertEquals(
            ClassAttendanceListDomExtractor.ErrorCode.BACK_FAILED.userMessage,
            state.stopReason,
        )
        // 一覧へ戻れないまま次の行を開かない。
        assertEquals(listOf(1), dom.clickedRowIndexes)
        assertTrue(state.isPartial)
    }

    @Test
    fun stopsWhenTheRowLinkCannotBeClicked() = runTest {
        val dom = FakeClassDom(rows, clickError = "LIST_CHANGED")

        val state = started(
            runner(dom).run(onState = { }, shouldCancel = { false }),
        )

        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertEquals(
            ClassAttendanceListDomExtractor.ErrorCode.LIST_CHANGED.userMessage,
            state.stopReason,
        )
        assertTrue(dom.clickedRowIndexes.isEmpty())
        assertEquals(0, state.doneCount)
        assertEquals(1, state.failedCount)
        assertEquals(1, state.skippedCount)
    }

    @Test
    fun stopsWhenThePreviewCannotBeValidatedWithoutSavingAnything() = runTest {
        val dom = FakeClassDom(rows)

        val state = started(
            runner(dom, previewLoader = { error("preview failed") }).run(
                onState = { },
                shouldCancel = { false },
            ),
        )

        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertNotNull(state.stopReason)
        assertEquals(ClassAttendanceTour.ItemStatus.FAILED, state.items[0].status)
        assertNull(state.items[0].preview)
        assertEquals(listOf(1), dom.clickedRowIndexes)
        // プレビューを作れない行を DONE にせず、部分結果として残す。
        assertEquals(0, state.doneCount)
        assertTrue(state.isPartial)
    }

    @Test
    fun stopsWhenNavigatingAwayFromClassDuringTheTour() = runTest {
        val dom = FakeClassDom(rows)
        var url: String? = consoleUrl
        // 1 行目を開いた直後に別ホストへ遷移したことにする。
        dom.onRowClick = { url = "https://example.com/" }

        val state = started(
            runner(dom, currentUrl = { url }).run(onState = { }, shouldCancel = { false }),
        )

        assertEquals(ClassAttendanceTour.Phase.STOPPED, state.phase)
        assertEquals(
            ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE.userMessage,
            state.stopReason,
        )
        assertEquals(listOf(1), dom.clickedRowIndexes)
        assertTrue(state.isPartial)
    }

    @Test
    fun onlyRunsWhitelistedFixedScriptsAndNeverSubmitsOrReadsCredentials() = runTest {
        val dom = FakeClassDom(rows)

        started(runner(dom).run(onState = { }, shouldCancel = { false }))

        val allowed = setOf(
            ClassAttendanceListDomExtractor.SCREEN_STATE_SCRIPT,
            ClassAttendanceListDomExtractor.LIST_VERIFY_SCRIPT,
            ClassAttendanceListDomExtractor.LIST_SCRIPT,
            ClassAttendanceListDomExtractor.BACK_VERIFY_SCRIPT,
            ClassAttendanceListDomExtractor.BACK_SCRIPT,
            ClassAttendanceDetailDomExtractor.VERIFY_SCRIPT,
            ClassAttendanceDetailDomExtractor.SCRIPT,
            ClassAttendanceDetailDomExtractor.buildIdentityScript(rows[0].first),
            ClassAttendanceDetailDomExtractor.buildIdentityScript(rows[1].first),
            ClassAttendanceListDomExtractor.buildClickScript(rows.asRows()[0], rows.size),
            ClassAttendanceListDomExtractor.buildClickScript(rows.asRows()[1], rows.size),
        )
        dom.executedScripts.forEach { script ->
            assertTrue("unexpected script", script in allowed)
        }

        // 副作用のある操作・認証情報の読み出しを含まない。
        dom.executedScripts.forEach { script ->
            listOf(
                ".submit(",
                "addJavascriptInterface",
                "document.cookie",
                "localStorage",
                "sessionStorage",
                "type=\"password\"",
                "input[type=password]",
                ".value",
            ).forEach { forbidden ->
                assertFalse("script must not contain $forbidden", script.contains(forbidden))
            }
        }
        // 「戻る」だけを文字完全一致で押し、他のボタンは押さない。
        assertTrue(ClassAttendanceListDomExtractor.BACK_SCRIPT.contains("info.button.click();"))
        assertTrue(
            dom.executedScripts.none {
                it.contains("button.click();") &&
                    it != ClassAttendanceListDomExtractor.BACK_SCRIPT
            },
        )
    }

    private fun List<Pair<String, String>>.asRows(): List<ClassAttendanceListDomExtractor.Row> =
        mapIndexed { position, (label, token) ->
            ClassAttendanceListDomExtractor.Row(position + 1, label, token)
        }
}
