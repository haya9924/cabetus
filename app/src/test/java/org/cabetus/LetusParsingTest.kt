package org.cabetus

import org.cabetus.data.letus.LetusParsing
import org.cabetus.data.letus.ScanLevel
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LetusParsingTest {

    private val jst = ZoneId.of("Asia/Tokyo")

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(jst).toInstant().toEpochMilli()

    @Test
    fun parseDeadline_fullDateWithTime() {
        val text = "提出期限 2026年7月10日 (金) 23:59"
        val result = LetusParsing.parseDeadline(LetusParsing.extractDeadlineText(text))
        assertEquals(epoch(2026, 7, 10, 23, 59), result)
    }

    @Test
    fun parseDeadline_fullDateNoTime_defaultsTo2359() {
        val text = "締切日時 2026年12月1日"
        val result = LetusParsing.parseDeadline(LetusParsing.extractDeadlineText(text))
        assertEquals(epoch(2026, 12, 1, 23, 59), result)
    }

    @Test
    fun parseDeadline_fullWidthColon() {
        val text = "提出期限 2026年3月5日 9：05"
        val result = LetusParsing.parseDeadline(LetusParsing.extractDeadlineText(text))
        assertEquals(epoch(2026, 3, 5, 9, 5), result)
    }

    @Test
    fun parseDeadline_japaneseHourMarker() {
        val text = "終了日時 2026年4月20日 17時"
        val result = LetusParsing.parseDeadline(LetusParsing.extractDeadlineText(text))
        assertEquals(epoch(2026, 4, 20, 17, 0), result)
    }

    @Test
    fun parseDeadline_noYear_usesProvidedNow() {
        val now = LocalDateTime.of(2027, 1, 1, 0, 0)
        val text = "期限 7月10日 15:30"
        val result = LetusParsing.parseDeadline(LetusParsing.extractDeadlineText(text), now)
        assertEquals(epoch(2027, 7, 10, 15, 30), result)
    }

    @Test
    fun extractDeadlineText_picksEarliestKeyword() {
        val text = "何かの説明 開始日時 2026年1月1日 提出期限 2026年7月10日 23:59"
        val slice = LetusParsing.extractDeadlineText(text)
        assertTrue(slice.startsWith("提出期限"))
    }

    @Test
    fun extractDeadlineText_noKeyword_returnsEmpty() {
        assertEquals("", LetusParsing.extractDeadlineText("この課題の説明文だけがあります"))
    }

    @Test
    fun parseDeadline_garbage_returnsNull() {
        assertNull(LetusParsing.parseDeadline(""))
        assertNull(LetusParsing.parseDeadline("no date here"))
    }

    @Test
    fun isAssignmentLikeLink_assignModule() {
        assertTrue(
            LetusParsing.isAssignmentLikeLink(
                "レポート課題1",
                "https://letus.ed.tus.ac.jp/mod/assign/view.php?id=123",
                ScanLevel.STANDARD,
            ),
        )
    }

    @Test
    fun isAssignmentLikeLink_resourceExcluded() {
        assertFalse(
            LetusParsing.isAssignmentLikeLink(
                "配布資料",
                "https://letus.ed.tus.ac.jp/mod/resource/view.php?id=1",
                ScanLevel.STANDARD,
            ),
        )
    }

    @Test
    fun isAssignmentLikeLink_forumOnlyBroad() {
        val url = "https://letus.ed.tus.ac.jp/mod/forum/view.php?id=9"
        assertFalse(LetusParsing.isAssignmentLikeLink("ディスカッション課題", url, ScanLevel.STANDARD))
        assertTrue(LetusParsing.isAssignmentLikeLink("ディスカッション課題", url, ScanLevel.BROAD))
    }

    @Test
    fun submissionStatus_assign() {
        assertEquals(
            SubmissionStatus.SUBMITTED,
            LetusParsing.extractSubmissionStatus("提出ステータス 提出済み", "/mod/assign/view.php"),
        )
        assertEquals(
            SubmissionStatus.NOT_SUBMITTED,
            LetusParsing.extractSubmissionStatus("提出ステータス 未提出", "/mod/assign/view.php"),
        )
    }

    @Test
    fun submissionStatus_quiz() {
        assertEquals(
            SubmissionStatus.COMPLETED,
            LetusParsing.extractSubmissionStatus("受験済み", "/mod/quiz/view.php"),
        )
    }

    @Test
    fun lifecycle_beforeStart() {
        val text = "この小テストは 開始予定 で現在は 利用できません"
        val status = LetusParsing.resolveLifecycle(text, SubmissionStatus.UNKNOWN, null)
        assertEquals(LifecycleStatus.BEFORE_START, status)
    }

    @Test
    fun lifecycle_passed() {
        val past = epoch(2000, 1, 1, 0, 0)
        val status = LetusParsing.resolveLifecycle("", SubmissionStatus.NOT_SUBMITTED, past)
        assertEquals(LifecycleStatus.PASSED, status)
    }

    @Test
    fun isCourseUrl() {
        assertTrue(LetusParsing.isCourseUrl("https://letus.ed.tus.ac.jp/course/view.php?id=100"))
        assertFalse(LetusParsing.isCourseUrl("https://letus.ed.tus.ac.jp/mod/assign/view.php?id=1"))
    }

    // ─── ログイン判定（Firefoxログで観測した実URL） ─────────────────

    @Test
    fun isAuthenticatedLetusUrl_realFlow() {
        // 認証済みで到達するページ
        assertTrue(
            LetusParsing.isAuthenticatedLetusUrl("https://letus.ed.tus.ac.jp/course/view.php?id=214759"),
        )
        assertTrue(LetusParsing.isAuthenticatedLetusUrl("https://letus.ed.tus.ac.jp/my/"))

        // 未ログイン時に経由する認証系URL（すべて未認証と判定すべき）
        assertFalse(
            LetusParsing.isAuthenticatedLetusUrl("https://letus.ed.tus.ac.jp/auth/shibboleth/index.php"),
        )
        assertFalse(
            LetusParsing.isAuthenticatedLetusUrl(
                "https://idp.admin.tus.ac.jp/idp/profile/SAML2/Redirect/SSO?execution=e1s1",
            ),
        )
        assertFalse(
            LetusParsing.isAuthenticatedLetusUrl(
                "https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize?scope=openid",
            ),
        )
        assertFalse(
            LetusParsing.isAuthenticatedLetusUrl("https://letus.ed.tus.ac.jp/Shibboleth.sso/SAML2/POST"),
        )
        assertFalse(
            LetusParsing.isAuthenticatedLetusUrl("https://letus.ed.tus.ac.jp/login/index.php"),
        )
    }

    @Test
    fun isLoggedInResponse_bodyMarker() {
        assertFalse(
            LetusParsing.isLoggedInResponse(
                "https://letus.ed.tus.ac.jp/my/",
                "<html>… あなたはログインしていません …</html>",
            ),
        )
        assertTrue(
            LetusParsing.isLoggedInResponse(
                "https://letus.ed.tus.ac.jp/my/",
                "<html>マイコース</html>",
            ),
        )
    }

    // ─── Moodle AJAX API ──────────────────────────────────────────

    @Test
    fun extractSesskey_fromMoodleConfig() {
        // 実ログの sesskey 形式（lib/ajax/service.php?sesskey=x04EJYVwCH）に合わせた M.cfg 断片
        val html = """<script>var M = {}; M.cfg = {"wwwroot":"https:\/\/letus.ed.tus.ac.jp","sesskey":"x04EJYVwCH","themerev":1771737057};</script>"""
        assertEquals("x04EJYVwCH", LetusParsing.extractSesskey(html))
        assertEquals(null, LetusParsing.extractSesskey("<html>no config</html>"))
    }

    @Test
    fun parseEnrolledCourses_success() {
        val body = """
            [{"error":false,"data":{"nextoffset":3,"courses":[
              {"id":214759,"fullname":"2026年度 線形代数１","viewurl":"https://letus.ed.tus.ac.jp/course/view.php?id=214759"},
              {"id":214760,"fullname":"物理学実験 A組 &amp; 補講","viewurl":"https://letus.ed.tus.ac.jp/course/view.php?id=214760"}
            ]}}]
        """.trimIndent()
        val courses = LetusParsing.parseEnrolledCourses(body)
        assertEquals(2, courses.size)
        assertEquals("2026年度 線形代数１", courses[0].title)
        assertEquals("https://letus.ed.tus.ac.jp/course/view.php?id=214759", courses[0].url)
        // HTMLエンティティが展開される
        assertEquals("物理学実験 A組 & 補講", courses[1].title)
    }

    @Test
    fun parseEnrolledCourses_errorOrGarbage() {
        assertTrue(LetusParsing.parseEnrolledCourses("""[{"error":true,"exception":{}}]""").isEmpty())
        assertTrue(LetusParsing.parseEnrolledCourses("not json").isEmpty())
        assertTrue(LetusParsing.parseEnrolledCourses("[]").isEmpty())
    }
}
