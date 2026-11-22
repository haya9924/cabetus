package org.cabetus

import org.cabetus.domain.LetusClassLinkResolver
import org.cabetus.domain.LetusClassLinkResolver.MoodleCourseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ホームの実施中授業 → LETUS コースの解決。
 *
 * - 明示マッピングがあれば最優先で使う（不正なら推測しない）
 * - 次に LETUS コース名の末尾の括弧書き（科目コード）と CLASS の科目コードの完全一致を調べる
 * - コード候補が無いときだけ、正規化した科目名の完全一致が**一意**なとき
 *   `course/view.php?id=ID` を返す
 * - 不一致・曖昧・不正 URL は LETUS トップへ逃がし、別の授業を開かない
 */
class LetusClassLinkResolverTest {

    private fun course(id: String, name: String, host: String = "letus.ed.tus.ac.jp") =
        MoodleCourseRef(
            id = id,
            name = name,
            url = "https://$host/course/view.php?id=$id",
        )

    private fun resolve(
        code: String = "C1",
        name: String = "情報リテラシー",
        explicit: Map<String, String> = emptyMap(),
        courses: List<MoodleCourseRef>,
    ) = LetusClassLinkResolver.resolve(
        courseCode = code,
        courseName = name,
        explicitUrlByCourseCode = explicit,
        moodleCourses = courses,
    )

    // --- 明示マッピング ---

    @Test
    fun explicit_mapping_wins_over_name_matching() {
        val result = resolve(
            name = "情報リテラシー",
            explicit = mapOf("C1" to "https://letus.ed.tus.ac.jp/course/view.php?id=42"),
            courses = listOf(course("1", "情報リテラシー"), course("2", "別の科目"), course("42", "情報リテラシー")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        val courseResult = result as LetusClassLinkResolver.Result.Course
        assertEquals("42", courseResult.courseId)
        assertEquals(LetusClassLinkResolver.Source.EXPLICIT_MAPPING, courseResult.source)
        assertEquals("https://letus.ed.tus.ac.jp/course/view.php?id=42", result.url)
        assertTrue(result.isCourseSpecific)
        // 開く先を利用者に示す（同じ ID のコース名を優先する）。
        assertTrue(result.detailText.contains("情報リテラシー"))
    }

    @Test
    fun explicit_mapping_is_used_even_when_name_does_not_match() {
        val result = resolve(
            name = "時間割の表記",
            explicit = mapOf("C1" to "https://letus.ed.tus.ac.jp/course/view.php?id=7"),
            courses = listOf(course("7", "LETUS側の正式名")),
        )

        assertEquals("https://letus.ed.tus.ac.jp/course/view.php?id=7", result.url)
        assertTrue(result.detailText.contains("LETUS側の正式名"))
    }

    @Test
    fun explicit_mapping_is_ignored_for_other_course_codes() {
        val result = resolve(
            code = "C2",
            name = "情報リテラシー",
            explicit = mapOf("C1" to "https://letus.ed.tus.ac.jp/course/view.php?id=42"),
            courses = listOf(course("9", "情報リテラシー")),
        )

        assertEquals("https://letus.ed.tus.ac.jp/course/view.php?id=9", result.url)
        assertEquals(
            LetusClassLinkResolver.Source.NAME_MATCH,
            (result as LetusClassLinkResolver.Result.Course).source,
        )
    }

    @Test
    fun invalid_explicit_mapping_does_not_fall_back_to_name_guessing() {
        val result = resolve(
            explicit = mapOf("C1" to "https://example.com/course/view.php?id=42"),
            courses = listOf(course("1", "情報リテラシー")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.LetusTop)
        assertEquals(
            LetusClassLinkResolver.FallbackReason.INVALID_EXPLICIT_MAPPING,
            (result as LetusClassLinkResolver.Result.LetusTop).reason,
        )
        assertEquals(LetusClassLinkResolver.LETUS_TOP_URL, result.url)
        assertFalse(result.isCourseSpecific)
    }

    @Test
    fun malformed_explicit_mapping_is_rejected() {
        val result = resolve(
            explicit = mapOf("C1" to "javascript:alert(1)"),
            courses = emptyList(),
        )

        assertTrue(result is LetusClassLinkResolver.Result.LetusTop)
    }

    // --- コース名末尾の科目コード（実データ） ---

    @Test
    fun course_code_in_trailing_parentheses_matches_real_letus_name() {
        // 実機 DB の実例: LETUS 側は末尾にコードと余分な空白、CLASS 側は詰まった名前。
        val result = resolve(
            code = "9943116",
            name = "Listening&SpeakingⅠab組",
            courses = listOf(
                course("101", "Listening & SpeakingⅠab組 (9943116)"),
                course("102", "Listening & SpeakingⅠab組"),
            ),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        val courseResult = result as LetusClassLinkResolver.Result.Course
        assertEquals("101", courseResult.courseId)
        assertEquals(LetusClassLinkResolver.Source.COURSE_CODE, courseResult.source)
        assertEquals("https://letus.ed.tus.ac.jp/course/view.php?id=101", result.url)
        assertTrue(result.detailText.contains("科目コード"))
    }

    @Test
    fun alphanumeric_course_code_matches_and_is_not_numeric_only() {
        val result = resolve(
            code = "994321G",
            name = "情報リテラシー",
            courses = listOf(course("111", "情報リテラシー (994321G)")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        val courseResult = result as LetusClassLinkResolver.Result.Course
        assertEquals("111", courseResult.courseId)
        assertEquals(LetusClassLinkResolver.Source.COURSE_CODE, courseResult.source)
    }

    @Test
    fun full_width_parentheses_and_spaces_are_normalized_for_code_matching() {
        val result = resolve(
            code = "9943116",
            name = "Listening&SpeakingⅠab組",
            courses = listOf(course("121", "Listening & SpeakingⅠab組（9943116）")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        assertEquals("121", (result as LetusClassLinkResolver.Result.Course).courseId)
    }

    @Test
    fun code_match_outranks_name_matching() {
        // 名前が完全一致するコース（別の組）があっても、コードが一致する方を選ぶ。
        val result = resolve(
            code = "9943116",
            name = "Listening & SpeakingⅠab組",
            courses = listOf(
                course("131", "Listening & SpeakingⅠab組"),
                course("132", "Listening & SpeakingⅠab組 (9943116)"),
            ),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        val courseResult = result as LetusClassLinkResolver.Result.Course
        assertEquals("132", courseResult.courseId)
        assertEquals(LetusClassLinkResolver.Source.COURSE_CODE, courseResult.source)
    }

    @Test
    fun duplicate_rows_with_same_code_are_not_ambiguous() {
        val result = resolve(
            code = "9943116",
            courses = listOf(
                course("141", "Listening & SpeakingⅠab組 (9943116)"),
                course("141", "Listening & SpeakingⅠab組 (9943116)"),
            ),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        assertEquals("141", (result as LetusClassLinkResolver.Result.Course).courseId)
    }

    @Test
    fun same_code_on_different_courses_is_ambiguous() {
        val result = resolve(
            code = "9943116",
            courses = listOf(
                course("151", "Listening & SpeakingⅠab組 (9943116)"),
                course("152", "Listening & SpeakingⅠab組 (9943116)"),
            ),
        )

        assertTrue(result is LetusClassLinkResolver.Result.LetusTop)
        val top = result as LetusClassLinkResolver.Result.LetusTop
        assertEquals(LetusClassLinkResolver.FallbackReason.AMBIGUOUS_COURSE_NAME, top.reason)
        assertEquals(LetusClassLinkResolver.LETUS_TOP_URL, result.url)
    }

    @Test
    fun other_code_substring_is_not_matched() {
        // 部分一致はしない: 9943116 は 99431160 や "x9943116" に一致しない。
        val longer = resolve(
            code = "9943116",
            courses = listOf(course("161", "Listening & SpeakingⅠab組 (99431160)")),
        )
        assertTrue(longer is LetusClassLinkResolver.Result.LetusTop)
        assertEquals(
            LetusClassLinkResolver.FallbackReason.NO_MATCHING_COURSE,
            (longer as LetusClassLinkResolver.Result.LetusTop).reason,
        )

        val embedded = resolve(
            code = "9943116",
            courses = listOf(course("162", "Listening 9943116 ab組")),
        )
        assertTrue(embedded is LetusClassLinkResolver.Result.LetusTop)
        assertEquals(
            LetusClassLinkResolver.FallbackReason.NO_MATCHING_COURSE,
            (embedded as LetusClassLinkResolver.Result.LetusTop).reason,
        )
    }

    @Test
    fun course_code_match_with_unverifiable_url_falls_back_to_top() {
        val result = resolve(
            code = "9943116",
            courses = listOf(
                MoodleCourseRef(
                    id = "9943116",
                    name = "Listening & SpeakingⅠab組 (9943116)",
                    url = "https://letus.ed.tus.ac.jp/mod/assign/view.php?id=9943116",
                ),
            ),
        )

        assertTrue(result is LetusClassLinkResolver.Result.LetusTop)
        assertEquals(
            LetusClassLinkResolver.FallbackReason.INVALID_COURSE_URL,
            (result as LetusClassLinkResolver.Result.LetusTop).reason,
        )
    }

    @Test
    fun name_matching_is_used_when_no_code_candidate_exists() {
        val result = resolve(
            code = "9943116",
            name = "情報リテラシー",
            courses = listOf(course("171", "情報リテラシー")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        val courseResult = result as LetusClassLinkResolver.Result.Course
        assertEquals("171", courseResult.courseId)
        assertEquals(LetusClassLinkResolver.Source.NAME_MATCH, courseResult.source)
    }

    // --- 科目名の完全一致 ---

    @Test
    fun unique_normalized_name_match_opens_course_view_with_id() {
        val result = resolve(
            name = "情報リテラシー",
            courses = listOf(course("11", "情報リテラシー"), course("12", "線形代数")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        val courseResult = result as LetusClassLinkResolver.Result.Course
        assertEquals("11", courseResult.courseId)
        assertEquals(LetusClassLinkResolver.Source.NAME_MATCH, courseResult.source)
        assertEquals("https://letus.ed.tus.ac.jp/course/view.php?id=11", result.url)
        assertEquals(LetusClassLinkResolver.COURSE_BUTTON_LABEL, result.buttonLabel)
    }

    @Test
    fun normalization_is_nfkc_plus_whitespace_only() {
        // 全角英数字・全角空白・連続空白だけを揃える。
        val result = resolve(
            name = "ＡＩ　演習   Ⅰ",
            courses = listOf(course("21", "AI 演習 I")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        assertEquals("21", (result as LetusClassLinkResolver.Result.Course).courseId)
    }

    @Test
    fun name_matching_is_case_sensitive_and_not_partial() {
        val lowercase = resolve(
            name = "ai",
            courses = listOf(course("31", "AI")),
        )
        assertTrue(lowercase is LetusClassLinkResolver.Result.LetusTop)

        val partial = resolve(
            name = "情報",
            courses = listOf(course("32", "情報リテラシー")),
        )
        assertTrue(partial is LetusClassLinkResolver.Result.LetusTop)
        assertEquals(
            LetusClassLinkResolver.FallbackReason.NO_MATCHING_COURSE,
            (partial as LetusClassLinkResolver.Result.LetusTop).reason,
        )
    }

    @Test
    fun duplicate_rows_of_the_same_course_are_not_ambiguous() {
        val result = resolve(
            courses = listOf(
                course("41", "情報リテラシー"),
                course("41", "情報リテラシー"),
            ),
        )

        assertTrue(result is LetusClassLinkResolver.Result.Course)
        assertEquals("41", (result as LetusClassLinkResolver.Result.Course).courseId)
    }

    @Test
    fun multiple_courses_with_same_name_are_ambiguous_and_fall_back_to_top() {
        val result = resolve(
            courses = listOf(course("51", "情報リテラシー"), course("52", "情報リテラシー")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.LetusTop)
        val top = result as LetusClassLinkResolver.Result.LetusTop
        assertEquals(LetusClassLinkResolver.FallbackReason.AMBIGUOUS_COURSE_NAME, top.reason)
        assertEquals(LetusClassLinkResolver.LETUS_TOP_URL, result.url)
        assertEquals(LetusClassLinkResolver.TOP_BUTTON_LABEL, result.buttonLabel)
        assertFalse(result.isCourseSpecific)
        // 「別の授業を開く」ことを避けた理由が明示される。
        assertTrue(result.detailText.contains("複数"))
        assertTrue(result.detailText.contains("トップ"))
    }

    @Test
    fun no_matching_course_falls_back_to_top() {
        val result = resolve(courses = listOf(course("61", "線形代数")))

        assertTrue(result is LetusClassLinkResolver.Result.LetusTop)
        assertEquals(
            LetusClassLinkResolver.FallbackReason.NO_MATCHING_COURSE,
            (result as LetusClassLinkResolver.Result.LetusTop).reason,
        )
        assertEquals(LetusClassLinkResolver.LETUS_TOP_URL, result.url)
    }

    @Test
    fun empty_course_list_falls_back_to_top() {
        val result = resolve(courses = emptyList())

        assertEquals(LetusClassLinkResolver.LETUS_TOP_URL, result.url)
        assertFalse(result.isCourseSpecific)
    }

    @Test
    fun blank_course_name_is_not_guessed() {
        val result = resolve(
            name = "   ",
            courses = listOf(course("71", "")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.LetusTop)
        assertEquals(
            LetusClassLinkResolver.FallbackReason.UNKNOWN_COURSE_NAME,
            (result as LetusClassLinkResolver.Result.LetusTop).reason,
        )
    }

    @Test
    fun name_that_is_just_the_course_code_is_not_guessed() {
        // 時間割セルに名前が無く、コードが名前として使われている場合。
        val result = resolve(
            code = "C1",
            name = "C1",
            courses = listOf(course("81", "C1")),
        )

        assertTrue(result is LetusClassLinkResolver.Result.LetusTop)
        assertEquals(
            LetusClassLinkResolver.FallbackReason.UNKNOWN_COURSE_NAME,
            (result as LetusClassLinkResolver.Result.LetusTop).reason,
        )
    }

    @Test
    fun matching_name_with_unverifiable_url_falls_back_to_top() {
        val result = resolve(
            courses = listOf(
                MoodleCourseRef(
                    id = "weird",
                    name = "情報リテラシー",
                    url = "https://letus.ed.tus.ac.jp/mod/assign/view.php?id=91",
                ),
            ),
        )

        assertTrue(result is LetusClassLinkResolver.Result.LetusTop)
        assertEquals(
            LetusClassLinkResolver.FallbackReason.INVALID_COURSE_URL,
            (result as LetusClassLinkResolver.Result.LetusTop).reason,
        )
    }

    // --- URL 検証 ---

    @Test
    fun course_view_id_accepts_https_letus_course_page() {
        assertEquals(
            "12345",
            LetusClassLinkResolver.courseViewId(
                "https://letus.ed.tus.ac.jp/course/view.php?id=12345",
            ),
        )
        assertEquals(
            "12345",
            LetusClassLinkResolver.courseViewId(
                "https://letus.ed.tus.ac.jp/course/view.php?id=12345&section=2",
            ),
        )
        // ホストの大文字・末尾ドット、既定ポート 443 は許可する。
        assertEquals(
            "1",
            LetusClassLinkResolver.courseViewId("https://LETUS.ED.TUS.AC.JP.:443/course/view.php?id=1"),
        )
    }

    @Test
    fun course_view_id_rejects_other_hosts_paths_and_id_forms() {
        assertNull(LetusClassLinkResolver.courseViewId(null))
        assertNull(LetusClassLinkResolver.courseViewId(""))
        assertNull(
            LetusClassLinkResolver.courseViewId("http://letus.ed.tus.ac.jp/course/view.php?id=1"),
        )
        assertNull(
            LetusClassLinkResolver.courseViewId("https://evil.example/course/view.php?id=1"),
        )
        assertNull(
            LetusClassLinkResolver.courseViewId("https://letus.ed.tus.ac.jp.evil.example/course/view.php?id=1"),
        )
        assertNull(
            LetusClassLinkResolver.courseViewId("https://letus.ed.tus.ac.jp:8443/course/view.php?id=1"),
        )
        assertNull(
            LetusClassLinkResolver.courseViewId("https://user:pass@letus.ed.tus.ac.jp/course/view.php?id=1"),
        )
        // パスは完全一致のみ（前方一致・別スクリプトは不可）。
        assertNull(
            LetusClassLinkResolver.courseViewId("https://letus.ed.tus.ac.jp/course/view.php/extra?id=1"),
        )
        assertNull(
            LetusClassLinkResolver.courseViewId("https://letus.ed.tus.ac.jp/mod/assign/view.php?id=1"),
        )
        // ID は数字のみ。
        assertNull(
            LetusClassLinkResolver.courseViewId("https://letus.ed.tus.ac.jp/course/view.php?id=abc"),
        )
        assertNull(
            LetusClassLinkResolver.courseViewId("https://letus.ed.tus.ac.jp/course/view.php?section=1"),
        )
        assertNull(
            LetusClassLinkResolver.courseViewId("https://letus.ed.tus.ac.jp/course/view.php?id="),
        )
    }

    @Test
    fun course_url_is_built_from_verified_id() {
        assertEquals(
            "https://letus.ed.tus.ac.jp/course/view.php?id=999",
            LetusClassLinkResolver.courseUrl("999"),
        )
        assertEquals(LetusClassLinkResolver.LETUS_TOP_URL, "https://letus.ed.tus.ac.jp/")
    }

    @Test
    fun resolve_never_returns_non_letus_url() {
        val results = listOf(
            resolve(courses = emptyList()),
            resolve(courses = listOf(course("1", "情報リテラシー"))),
            resolve(courses = listOf(course("1", "情報リテラシー"), course("2", "情報リテラシー"))),
            resolve(explicit = mapOf("C1" to "https://example.com/x"), courses = emptyList()),
        )

        assertTrue(results.all { it.url.startsWith("https://letus.ed.tus.ac.jp/") })
        assertTrue(results.all { LetusClassLinkResolver.courseViewId(it.url) != null || it.url == LetusClassLinkResolver.LETUS_TOP_URL })
    }
}
