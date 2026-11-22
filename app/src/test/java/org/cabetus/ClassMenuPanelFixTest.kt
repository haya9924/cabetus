package org.cabetus

import org.cabetus.ui.browser.ClassMenuPanelFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassMenuPanelFixTest {

    @Test
    fun target_isLimitedToExactClassHttpsHost() {
        assertTrue(ClassMenuPanelFix.isTarget("https://class.admin.tus.ac.jp/"))
        assertTrue(ClassMenuPanelFix.isTarget("https://class.admin.tus.ac.jp/portal/top"))

        assertFalse(ClassMenuPanelFix.isTarget("https://sub.class.admin.tus.ac.jp/"))
        assertFalse(ClassMenuPanelFix.isTarget("http://class.admin.tus.ac.jp/"))
        assertFalse(ClassMenuPanelFix.isTarget("https://class.admin.tus.ac.jp:8443/"))
        assertFalse(ClassMenuPanelFix.isTarget("https://class.admin.tus.ac.jp.evil.example/"))
        assertFalse(ClassMenuPanelFix.isTarget("https://letus.ed.tus.ac.jp/"))
        assertFalse(ClassMenuPanelFix.isTarget(null))
    }

    @Test
    fun script_setsOnlyMenuPanelHeightPropertiesWithImportant() {
        val script = ClassMenuPanelFix.SCRIPT

        assertTrue(script.contains("document.getElementById(PANEL_ID)"))
        assertTrue(script.contains("var PANEL_ID = 'menuPanel';"))
        assertTrue(script.contains("window.innerHeight"))

        assertEquals(3, Regex("""setProperty\(""", RegexOption.IGNORE_CASE).findAll(script).count())
        assertTrue(script.contains("panel.style.setProperty('height', height, 'important');"))
        assertTrue(script.contains("panel.style.setProperty('min-height', height, 'important');"))
        assertTrue(script.contains("panel.style.setProperty('max-height', height, 'important');"))
    }

    @Test
    fun script_reappliesOnResizeAndSameDocumentUpdates() {
        val script = ClassMenuPanelFix.SCRIPT

        assertTrue(script.contains("addEventListener('resize'"))
        assertTrue(script.contains("addEventListener('orientationchange'"))
        assertTrue(script.contains("visualViewport"))
        assertTrue(script.contains("MutationObserver"))
        // 二重登録を防ぐためのインストール済みフラグ。
        assertTrue(script.contains("INSTALLED_FLAG"))
    }

    @Test
    fun script_hasNoAndroidBridgeOrCredentialAccess() {
        val script = ClassMenuPanelFix.SCRIPT.lowercase()

        listOf(
            "addjavascriptinterface",
            "queryselector",
            "password",
            "cookie",
            "localstorage",
            "sessionstorage",
            "fetch(",
            "xmlhttprequest",
            "innerhtml",
            "outerhtml",
            "document.write",
            "eval(",
            ".value",
        ).forEach { forbidden ->
            assertFalse("script must not contain $forbidden", script.contains(forbidden))
        }
    }
}
