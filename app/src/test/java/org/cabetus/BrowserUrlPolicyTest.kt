package org.cabetus

import org.cabetus.ui.browser.BrowserUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlPolicyTest {

    // --- isAllowedInBrowser: アプリ内 WebView の遷移先 ---

    @Test
    fun browser_allowsUniversityAndSsoHosts() {
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://letus.ed.tus.ac.jp/my/"))
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://class.admin.tus.ac.jp/"))
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://idp.admin.tus.ac.jp/idp/shibboleth"))
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://login.microsoftonline.com/common/oauth2"))
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://login.live.com/"))
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://login.microsoft.com/"))
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://aadcdn.msftauth.net/"))
    }

    @Test
    fun browser_allowsSubdomainsOfTrustedHosts() {
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://sub.letus.ed.tus.ac.jp/"))
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://cdn.sub.microsoft.com/"))
    }

    @Test
    fun browser_rejectsUntrustedHosts() {
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("https://example.com/"))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("https://evil.example/?next=https://letus.ed.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("https://tus.ac.jp.evil.example/"))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("https://eviltus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("https://microsoft.com.evil.example/"))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("https://notmicrosoftonline.com/"))
    }

    @Test
    fun browser_rejectsNonHttpsAndMalformedUrls() {
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("http://letus.ed.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("javascript:alert(1)"))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("intent://letus.ed.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser(""))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("   "))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser(null))
    }

    @Test
    fun browser_rejectsUnexpectedPortAndUserInfo() {
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("https://letus.ed.tus.ac.jp:8443/"))
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://letus.ed.tus.ac.jp:443/"))
        assertFalse(BrowserUrlPolicy.isAllowedInBrowser("https://user:pass@letus.ed.tus.ac.jp/"))
    }

    @Test
    fun browser_normalizesHostCaseAndTrailingDot() {
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("HTTPS://LETUS.ED.TUS.AC.JP/"))
        assertTrue(BrowserUrlPolicy.isAllowedInBrowser("https://letus.ed.tus.ac.jp./my/"))
    }

    @Test
    fun browser_allowsPathsAndQueriesOnTrustedHosts() {
        assertTrue(
            BrowserUrlPolicy.isAllowedInBrowser(
                "https://letus.ed.tus.ac.jp/mod/assign/view.php?id=1&action=submit",
            ),
        )
    }

    // --- isAllowedStart: アプリから最初に開ける URL ---

    @Test
    fun start_allowsOnlyLetusAndClassRoots() {
        assertTrue(BrowserUrlPolicy.isAllowedStart("https://letus.ed.tus.ac.jp/"))
        assertTrue(BrowserUrlPolicy.isAllowedStart("https://class.admin.tus.ac.jp/"))
        // LETUS / CLASS の配下ページは開始 URL としても許可する。
        assertTrue(BrowserUrlPolicy.isAllowedStart("https://letus.ed.tus.ac.jp/my/"))
    }

    @Test
    fun start_rejectsSsoHostsSubdomainsAndOtherSchemes() {
        assertFalse(BrowserUrlPolicy.isAllowedStart("https://idp.admin.tus.ac.jp/idp/shibboleth"))
        assertFalse(BrowserUrlPolicy.isAllowedStart("https://sub.letus.ed.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isAllowedStart("https://login.microsoftonline.com/"))
        assertFalse(BrowserUrlPolicy.isAllowedStart("https://evil.example/"))
        assertFalse(BrowserUrlPolicy.isAllowedStart("http://letus.ed.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isAllowedStart("javascript:alert(1)"))
        assertFalse(BrowserUrlPolicy.isAllowedStart(null))
    }

    @Test
    fun start_rejectsUnexpectedPortAndUserInfo() {
        assertFalse(BrowserUrlPolicy.isAllowedStart("https://letus.ed.tus.ac.jp:8443/"))
        assertFalse(BrowserUrlPolicy.isAllowedStart("https://user:pass@letus.ed.tus.ac.jp/"))
    }

    // --- isClassConsole: CLASS 向け DOM 補正の対象判定 ---

    @Test
    fun classConsole_allowsOnlyExactHttpsClassHost() {
        assertTrue(BrowserUrlPolicy.isClassConsole("https://class.admin.tus.ac.jp/"))
        assertTrue(BrowserUrlPolicy.isClassConsole("https://class.admin.tus.ac.jp/portal/top"))
        assertTrue(BrowserUrlPolicy.isClassConsole("https://CLASS.ADMIN.TUS.AC.JP/path?q=1"))
        assertTrue(BrowserUrlPolicy.isClassConsole("https://class.admin.tus.ac.jp:443/"))
    }

    @Test
    fun classConsole_rejectsSubdomainsSimilarHostsAndOtherSchemes() {
        assertFalse(BrowserUrlPolicy.isClassConsole("https://sub.class.admin.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isClassConsole("https://class.admin.tus.ac.jp.evil.example/"))
        assertFalse(BrowserUrlPolicy.isClassConsole("https://evilclass.admin.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isClassConsole("https://class.admin.tus.ac.jp:8443/"))
        assertFalse(BrowserUrlPolicy.isClassConsole("https://user:pass@class.admin.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isClassConsole("http://class.admin.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isClassConsole("javascript:alert(1)"))
        assertFalse(BrowserUrlPolicy.isClassConsole(""))
        assertFalse(BrowserUrlPolicy.isClassConsole(null))
    }


    // --- isClassTimetablePage: Web 取り込みボタンの対象判定 ---

    @Test
    fun classTimetable_allowsOnlyExactHttpsPages() {
        // 実機で表示を確認済みの 2 パスだけを許可する。
        listOf(
            "https://class.admin.tus.ac.jp/uprx/up/at/ats501/Ats50101.xhtml",
            "https://class.admin.tus.ac.jp/uprx/up/bs/bsa501/Bsa50101.xhtml",
            "https://CLASS.ADMIN.TUS.AC.JP/uprx/up/bs/bsa501/Bsa50101.xhtml?windowId=1",
            "https://class.admin.tus.ac.jp:443/uprx/up/bs/bsa501/Bsa50101.xhtml",
        ).forEach { url ->
            assertTrue(url, BrowserUrlPolicy.isClassTimetablePage(url))
        }
        assertEquals(2, BrowserUrlPolicy.CLASS_TIMETABLE_PATHS.size)
    }

    @Test
    fun classTimetable_rejectsOtherPagesHostsAndSchemes() {
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp/uprx/up/km/kmd508/Kmd50801.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp/uprx/up/at/ats501/Ats50101.xhtml/",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp/uprx/up/at/ats501/",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp/uprx/up/at/ats501/Ats50102.xhtml",
            ),
        )
        // 追加した bsa501 でも、ファイル名・ディレクトリが違えば許可しない。
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp/uprx/up/bs/bsa501/Bsa50101.xhtml/",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp/uprx/up/bs/bsa501/",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp/uprx/up/bs/bsa501/Bsa50102.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp/uprx/up/bs/bsa502/Bsa50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://sub.class.admin.tus.ac.jp/uprx/up/at/ats501/Ats50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://sub.class.admin.tus.ac.jp/uprx/up/bs/bsa501/Bsa50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp.evil.example/uprx/up/at/ats501/Ats50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp.evil.example/uprx/up/bs/bsa501/Bsa50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp:8443/uprx/up/at/ats501/Ats50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://class.admin.tus.ac.jp:8443/uprx/up/bs/bsa501/Bsa50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "http://class.admin.tus.ac.jp/uprx/up/at/ats501/Ats50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "http://class.admin.tus.ac.jp/uprx/up/bs/bsa501/Bsa50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://user:pass@class.admin.tus.ac.jp/uprx/up/at/ats501/Ats50101.xhtml",
            ),
        )
        assertFalse(
            BrowserUrlPolicy.isClassTimetablePage(
                "https://user:pass@class.admin.tus.ac.jp/uprx/up/bs/bsa501/Bsa50101.xhtml",
            ),
        )
        assertFalse(BrowserUrlPolicy.isClassTimetablePage(null))
    }

    // --- isSafeExternalUrl: 外部ブラウザへ渡せる URL ---

    @Test
    fun external_allowsHttpAndHttps() {
        assertTrue(BrowserUrlPolicy.isSafeExternalUrl("https://example.com/"))
        assertTrue(BrowserUrlPolicy.isSafeExternalUrl("http://example.com/path?q=1"))
        assertTrue(BrowserUrlPolicy.isSafeExternalUrl("https://example.com:8080/"))
    }

    @Test
    fun external_rejectsDangerousOrMalformedSchemes() {
        assertFalse(BrowserUrlPolicy.isSafeExternalUrl("javascript:alert(1)"))
        assertFalse(BrowserUrlPolicy.isSafeExternalUrl("intent://example.com/#Intent;end"))
        assertFalse(BrowserUrlPolicy.isSafeExternalUrl("file:///etc/passwd"))
        assertFalse(BrowserUrlPolicy.isSafeExternalUrl("content://example/data"))
        assertFalse(BrowserUrlPolicy.isSafeExternalUrl("mailto:test@example.com"))
        assertFalse(BrowserUrlPolicy.isSafeExternalUrl("https://user:pass@example.com/"))
        assertFalse(BrowserUrlPolicy.isSafeExternalUrl("https:///no-host"))
        assertFalse(BrowserUrlPolicy.isSafeExternalUrl(""))
        assertFalse(BrowserUrlPolicy.isSafeExternalUrl(null))
    }

    // --- hostOf: ツールバー表示 ---

    @Test
    fun hostOf_returnsNormalizedHost() {
        assertEquals("letus.ed.tus.ac.jp", BrowserUrlPolicy.hostOf("https://LETUS.ed.tus.ac.jp./my/"))
        assertEquals("example.com", BrowserUrlPolicy.hostOf("http://example.com/"))
    }

    @Test
    fun hostOf_returnsNullForInvalidUrls() {
        assertNull(BrowserUrlPolicy.hostOf(null))
        assertNull(BrowserUrlPolicy.hostOf(""))
        assertNull(BrowserUrlPolicy.hostOf("not a url"))
    }
}
