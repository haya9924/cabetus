package org.cabetus

import kotlinx.serialization.json.JsonPrimitive
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.CapturedDate
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.ConfirmationWindow
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.ObservedCourse
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.SlotLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * モバイル出席登録の DOM 抽出器について、実データ・Cookie・個人情報を使わず、
 * 固定スクリプトと合成 JSON だけで次を固定する。
 *
 * - 汎用の label 走査や IP・入力欄の読み取りを行わないこと
 * - 出席は実機で確認済みの成功 class と完全一致ラベルだけで判定すること
 * - 受付時間は「出席確認時間：」接頭辞付きの表記だけを読むこと
 * - 曜日時限を確定できない授業は出席完了・未提出のどちらにもしないこと
 * - [SlotLabel] の一部だけが不正な表記を切り捨てず、全体を拒否すること
 */
class ClassMobileAttendanceDomExtractorTest {

    private fun evaluatedString(json: String): String = JsonPrimitive(json).toString()

    // 2026-07-06 は月曜日。
    private val pageDate = CapturedDate.parse("2026/07/06(月)")!!
    private val window = ConfirmationWindow(LocalTime.of(10, 20), LocalTime.of(12, 0))

    private fun courseJson(
        courseNames: String = """["合成科目A"]""",
        slots: String = """["月1"]""",
        dates: String = """["2026/07/06(月)"]""",
        windows: String = """["10:20～12:00"]""",
        successCount: Int = 1,
        confirmationOpen: Boolean = false,
        negative: Boolean = false,
    ): String = """
        {
          "courseNames": $courseNames,
          "slots": $slots,
          "dates": $dates,
          "windows": $windows,
          "successCount": $successCount,
          "confirmationOpen": $confirmationOpen,
          "negative": $negative
        }
    """.trimIndent()

    private fun payload(
        courses: List<String>,
        pageDates: String = """["2026/07/06(月)"]""",
    ): String = """
        {
          "ok": true,
          "error": null,
          "titleLabel": "モバイル出席登録",
          "pageDates": $pageDates,
          "campusNetworkLabelVisible": false,
          "courses": [${courses.joinToString(",")}],
          "warnings": []
        }
    """.trimIndent()

    @Test
    fun separateDateHeadingIsReadWithoutSearchingBodyOrForms() {
        val script = ClassMobileAttendanceDomExtractor.SCRIPT
        assertTrue(script.contains("var headings = document.querySelectorAll(TITLE_SELECTOR)"))
        assertTrue(script.contains("if (!visible(headings[i])) continue"))
        assertTrue(script.contains("var dates = headingDates(headings[i])"))
        assertTrue(script.contains("if (!DATE_PATTERN.test(value)) return"))
        assertFalse(script.contains("document.body.textContent"))
        val invalid = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
            evaluatedString(payload(emptyList(), pageDates = "[]")),
        )
        assertEquals(ClassMobileAttendanceDomExtractor.ErrorCode.PAGE_DATE_NOT_FOUND, invalid.error)
    }

    @Test
    fun scripts_doNotScanGenericLabelsOrReadIpAndInputValues() {
        listOf(
            ClassMobileAttendanceDomExtractor.VERIFY_SCRIPT,
            ClassMobileAttendanceDomExtractor.SCRIPT,
        ).forEach { script ->
            // 完全一致ホスト・https・表示状態・既知の見出しだけを見る。
            assertTrue(script.contains("window.location.protocol !== 'https:'"))
            assertTrue(script.contains("window.location.hostname !== TARGET_HOST"))
            assertTrue(script.contains("var TARGET_HOST = 'class.admin.tus.ac.jp';"))
            assertTrue(script.contains("document.visibilityState"))
            assertTrue(
                script.contains(
                    "var TITLE_SELECTOR = 'h1, h2, h3, h4, h5, h6, .ui-title';",
                ),
            )

            // 授業一覧は .mainContent 内の #…dataList、授業はその直接の子 .dataAll だけ。
            assertTrue(script.contains("MAIN_CONTENT_CLASS = 'mainContent'"))
            assertTrue(script.contains("DATALIST_ID_SUFFIX = 'dataList'"))
            assertTrue(script.contains("DATA_ALL_CLASS = 'dataAll'"))
            assertTrue(script.contains("main.querySelectorAll"))
            assertTrue(script.contains("list.children"))

            // label は授業ブロック内だけで走査し、ページ全体の label は走査しない。
            assertTrue(script.contains("block.querySelectorAll(LABEL_SELECTOR)"))
            assertFalse(script.contains("document.querySelectorAll(LABEL_SELECTOR)"))
            assertFalse(script.contains("document.querySelectorAll('label')"))
            assertFalse(script.contains("getElementsByTagName('label')"))
            assertEquals(
                1,
                Regex(Regex.escape("querySelectorAll(LABEL_SELECTOR)")).findAll(script).count(),
            )

            // ページ全体のテキストや任意要素をまとめて読まない。
            listOf(
                "document.body.textContent",
                "document.documentElement.textContent",
                "document.querySelectorAll('*')",
                "getElementsByTagName('*')",
            ).forEach { forbidden ->
                assertFalse("script must not contain $forbidden", script.contains(forbidden))
            }

            // 入力欄（出席コード）・IP アドレス・Cookie・ストレージ・フォーム値を読まない。
            listOf(
                "'input'",
                "input[",
                ".value",
                "document.cookie",
                "localStorage",
                "sessionStorage",
                "indexedDB",
                "document.forms",
                "FormData",
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
                "addJavascriptInterface",
                "ipAddress",
                "ip_address",
                "IP_ADDRESS",
                "\\d{1,3}\\.\\d{1,3}",
            ).forEach { forbidden ->
                assertFalse("script must not contain $forbidden", script.contains(forbidden))
            }

            // 学内アクセスは `.mainContent span` の葉の完全一致ラベル 1 件だけを根拠にし、
            // IP 等の label や学外を推測しない。
            assertTrue(script.contains("function campusNetworkVisible()"))
            assertTrue(script.contains("CAMPUS_LABEL = '学内ネットワークからのアクセス'"))
        }
    }

    @Test
    fun campusLabelIsOnlyTheExactLeafInsideMainContent() {
        val script = ClassMobileAttendanceDomExtractor.SCRIPT

        assertEquals(
            "学内ネットワークからのアクセス",
            ClassMobileAttendanceDomExtractor.CAMPUS_LABEL,
        )

        // 走査対象は `.mainContent span` だけ（`.dataAll` など他の範囲は見ない）。
        assertTrue(script.contains("document.querySelectorAll('.mainContent span')"))
        assertEquals(
            1,
            Regex(Regex.escape("document.querySelectorAll('.mainContent span')"))
                .findAll(script).count(),
        )
        // 学内ラベルは可視・子要素なし（葉）・直接テキストの完全一致の 3 条件だけを見る。
        assertTrue(script.contains("if (!visible(element)) continue;"))
        assertTrue(script.contains("if (element.children.length !== 0) continue;"))
        assertTrue(script.contains("if (directText(element) !== CAMPUS_LABEL) continue;"))
        // 1 件だけ確認できたときだけ true。0 件・複数件・非表示は false（不明）のまま。
        assertTrue(script.contains("return matches === 1;"))
        // 学外や IP アドレスの推測は行わない。
        listOf("ipAddress", "ip_address", "IP_ADDRESS", "\\d{1,3}\\.\\d{1,3}").forEach {
            assertFalse("script must not contain $it", script.contains(it))
        }
        // 見出しの日付読み取りなど、他の用途で `span` を走査しない。
        assertFalse(script.contains("querySelectorAll('span')"))
        assertFalse(script.contains("getElementsByTagName('span')"))
        assertFalse(script.contains("document.querySelectorAll('*')"))

        // false は学外ではなく不明を表す（既存の bool は「確認済み学内 / 不明」だけ）。
        val unknown = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
            evaluatedString(payload(listOf(courseJson()))),
        )
        assertNull(unknown.error)
        assertFalse(unknown.observation!!.campusNetworkLabelVisible)
        val confirmed = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
            evaluatedString(
                payload(listOf(courseJson())).replace(
                    "\"campusNetworkLabelVisible\": false",
                    "\"campusNetworkLabelVisible\": true",
                ),
            ),
        )
        assertNull(confirmed.error)
        assertTrue(confirmed.observation!!.campusNetworkLabelVisible)
    }

    @Test
    fun successIsOnlyTheConfirmedClassAndExactLabel() {
        val script = ClassMobileAttendanceDomExtractor.SCRIPT

        assertEquals("attendSuc", ClassMobileAttendanceDomExtractor.SUCCESS_CLASS)
        assertEquals("出席", ClassMobileAttendanceDomExtractor.SUCCESS_LABEL)
        assertEquals("signSize", ClassMobileAttendanceDomExtractor.WINDOW_CLASS)
        assertEquals("signFlging", ClassMobileAttendanceDomExtractor.OPEN_CLASS)
        assertTrue(script.contains("SUCCESS_CLASS = 'attendSuc'"))
        assertTrue(script.contains("SUCCESS_LABEL = '出席'"))
        assertTrue(script.contains("var isSuccess = hasClassToken(element, SUCCESS_CLASS);"))
        // 見た目の class（sizeBig 等）から出席を推測しない。科目名の判定とは分離する。
        assertTrue(script.contains("var isCourse = hasClassToken(element, COURSE_CLASS) &&"))
        assertTrue(script.contains("if (text === SUCCESS_LABEL) successCount++;"))
        assertFalse(script.contains("if (isCourse && text === SUCCESS_LABEL)"))

        // 完全一致トークンだけを受け付け、部分一致の class を拾わない。
        assertTrue(script.contains("tokens[i].toLowerCase() === expected"))
        assertTrue(script.contains("NEGATIVE_PATTERN = /欠席|未出席|出席停止/;"))

        val confirmed = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
            evaluatedString(payload(listOf(courseJson()))),
        )
        assertNull(confirmed.error)
        assertTrue(confirmed.observation!!.courses.single().successLabelVisible)

        val notConfirmed = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
            evaluatedString(payload(listOf(courseJson(successCount = 0, confirmationOpen = true)))),
        )
        assertNull(notConfirmed.error)
        assertFalse(notConfirmed.observation!!.courses.single().successLabelVisible)
    }

    @Test
    fun confirmationWindowRequiresTheExactPrefixAndAValidTimeRange() {
        val script = ClassMobileAttendanceDomExtractor.SCRIPT

        assertEquals("出席確認時間：", ClassMobileAttendanceDomExtractor.WINDOW_PREFIX)
        assertTrue(script.contains("WINDOW_PREFIX = '出席確認時間：'"))
        assertTrue(script.contains("if (text.indexOf(WINDOW_PREFIX) !== 0) continue;"))
        assertTrue(script.contains("if (TIME_PATTERN.test(timeText)) pushUnique(windows, timeText);"))

        val accepted = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
            evaluatedString(payload(listOf(courseJson(windows = """["10:20～12:00"]""")))),
        )
        assertNull(accepted.error)
        assertEquals(window, accepted.observation!!.courses.single().confirmationWindow)

        // スクリプトが接頭辞を除かずに返した値は、時刻として解釈せず破棄する。
        val prefixed = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
            evaluatedString(
                payload(listOf(courseJson(windows = """["出席確認時間：10:20～12:00"]"""))),
            ),
        )
        assertNull(prefixed.error)
        assertNull(prefixed.observation!!.courses.single().confirmationWindow)

        // 逆転した範囲・不正な時刻は受付時間として採用しない。
        listOf("""["12:00～10:20"]""", """["25:00～26:00"]""", """["10:20"]""").forEach { windows ->
            val invalid = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
                evaluatedString(payload(listOf(courseJson(windows = windows)))),
            )
            assertNull(invalid.error)
            assertNull(invalid.observation!!.courses.single().confirmationWindow)
        }
    }

    @Test
    fun blockDateIsOnlyThePageDateAndNeverFilledOrDiscardedSilently() {
        fun courseFor(dates: String): ObservedCourse {
            val result = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
                evaluatedString(payload(listOf(courseJson(dates = dates)))),
            )
            assertNull(result.error)
            return result.observation!!.courses.single()
        }

        // 日付表記が無い授業は、日付も ambiguous も増やさない（ページ日付で補完しない）。
        val absent = courseFor("[]")
        assertNull(absent.date)
        assertFalse(absent.ambiguous)
        // ページ日付は確認できているが、ブロックの日付として流用しない。
        assertFalse(absent.isAttendanceConfirmed(pageDate))

        // ページ日付と一致する表記だけを採用する。
        val matching = courseFor("""["2026/07/06(月)"]""")
        assertEquals(pageDate, matching.date)
        assertFalse(matching.ambiguous)

        // 日が食い違う表記は日付として採用せず、判定もしない。
        val otherDay = courseFor("""["2026/07/07(火)"]""")
        assertNull(otherDay.date)
        assertTrue(otherDay.ambiguous)

        // 年だけが食い違う表記も同一日付として扱わない（2020/07/06 は月曜日）。
        val otherYear = courseFor("""["2020/07/06(月)"]""")
        assertNull(otherYear.date)
        assertTrue(otherYear.ambiguous)

        // 異なる日付が複数ある場合は、どれも採用せず判定しない。
        val multiple = courseFor("""["2026/07/06(月)", "2026/07/07(火)"]""")
        assertNull(multiple.date)
        assertTrue(multiple.ambiguous)

        // 解釈できない非空の表記は、黙って捨てずに判定不能として扱う。
        listOf("""["13/40"]""", """["2026/07/06(月)", "日付不明"]""").forEach { dates ->
            val invalid = courseFor(dates)
            assertNull("dates=$dates", invalid.date)
            assertTrue("dates=$dates", invalid.ambiguous)
        }

        // 判定不能な授業は出席完了・未提出のどちらにもしない。
        assertFalse(otherDay.isAttendanceConfirmed(pageDate))
        assertFalse(
            multiple.copy(successLabelVisible = false, confirmationOpen = true)
                .isAttendancePending(pageDate),
        )
    }

    @Test
    fun unknownSlotCannotBeTreatedAsConfirmedOrPending() {
        val script = ClassMobileAttendanceDomExtractor.SCRIPT
        // 曜日時限のセレクタは実機で未確認のため、スクリプトは slots を空のまま返す。
        assertTrue(script.contains("var slots = [];"))
        assertTrue(script.contains("slots: slots,"))

        // 仮に画面や呼び出し側が slots を返しても、抽出結果パーサは時限の根拠にしない。
        val result = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
            evaluatedString(
                payload(
                    listOf(
                        courseJson(slots = """["月1"]"""),
                        courseJson(
                            slots = """["月1"]""",
                            successCount = 0,
                            confirmationOpen = true,
                        ),
                    ),
                ),
            ),
        )
        assertNull(result.error)
        val courses = result.observation!!.courses
        assertEquals(2, courses.size)
        assertTrue(courses.all { it.slot == null })
        // 科目名・日付・受付時間が揃っていても、時限が不明なら確定しない。
        assertFalse(courses[0].isAttendanceConfirmed(pageDate))
        assertFalse(courses[1].isAttendancePending(pageDate))

        // モデル側でも slot が無い（または複数時限で単一に定まらない）場合は確定不可。
        val noSlotConfirmed = ObservedCourse(
            courseName = "合成科目A",
            slot = null,
            successLabelVisible = true,
            date = pageDate,
            confirmationWindow = window,
            confirmationOpen = false,
            ambiguous = false,
        )
        val noSlotPending = noSlotConfirmed.copy(
            successLabelVisible = false,
            confirmationOpen = true,
        )
        val multiplePeriods = SlotLabel(4, listOf(1, 2), "木1・2")
        val ambiguousSlotConfirmed = noSlotConfirmed.copy(slot = multiplePeriods)

        assertFalse(noSlotConfirmed.isAttendanceConfirmed(pageDate))
        assertFalse(noSlotPending.isAttendancePending(pageDate))
        assertFalse(ambiguousSlotConfirmed.isAttendanceConfirmed(pageDate))
        assertNull(multiplePeriods.singlePeriod)
    }

    @Test
    fun slotLabelRejectsAnyInvalidEntryAsAWhole() {
        assertEquals(SlotLabel(4, listOf(1), "木1"), SlotLabel.parse("木1"))
        assertEquals(SlotLabel(4, listOf(1, 2), "木1・2"), SlotLabel.parse("木1・2"))
        assertEquals(SlotLabel(1, listOf(1, 20), "月1,20"), SlotLabel.parse("月1,20"))

        listOf(
            // 1 つでも解釈できない時限があれば、先頭だけを採用せず表記全体を拒否する。
            "木1・25",
            "木1・0",
            "木1・2・21",
            "木1・2・abc",
            "木1・2・",
            // 曜日・時限の形式外。
            "木0",
            "木25",
            "木",
            "木abc",
            "1",
            "月曜",
            "月1.2",
            "月1〜2",
            "月１",
            "",
            "   ",
        ).forEach { raw ->
            assertNull("SlotLabel.parse('$raw') must be rejected", SlotLabel.parse(raw))
        }
    }
}
