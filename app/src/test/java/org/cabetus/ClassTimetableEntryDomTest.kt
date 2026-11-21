package org.cabetus

import org.cabetus.data.classweb.ClassTimetableEntryDom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassTimetableEntryDomTest {
    @Test fun onlyTheSmartphoneEntranceAndStudentTimetableCanBeOpened() {
        val script = ClassTimetableEntryDom.SCRIPT
        assertTrue(script.contains("location.protocol !== 'https:'"))
        assertTrue(script.contains("location.hostname !== 'class.admin.tus.ac.jp'"))
        assertTrue(script.contains("location.port !== '443'"))
        assertTrue(script.contains("label(link) === 'スマートフォンENTER'"))
        assertTrue(script.contains("MobileShibbolethAuthServlet"))
        assertTrue(script.contains("学生時間割表"))
        assertTrue(script.contains("entrances.length !== 1"))
        assertTrue(script.contains("targets.length > 1"))
        assertFalse(script.contains("学生出欠状況確認"))
        assertFalse(script.contains("モバイル出席登録"))
        assertFalse(script.contains(".submit("))
    }

    @Test fun timetableMenuUsesPanelLinksWithoutJqmEnhancerClasses() {
        val script = ClassTimetableEntryDom.SCRIPT
        assertTrue(script.contains("document.getElementById('menuPanel')"))
        assertTrue(script.contains("panel.contains(link) && isTimetableLabel"))
        assertTrue(script.contains("link.closest('[aria-hidden=\"true\"]')"))
    }

    @Test fun menuAlsoAcceptsLinksThatTargetAKnownTimetablePath() {
        val script = ClassTimetableEntryDom.SCRIPT
        assertTrue(script.contains("var timetablePaths = ["))
        assertTrue(script.contains("'/uprx/up/at/ats501/Ats50101.xhtml'"))
        assertTrue(script.contains("'/uprx/up/bs/bsa501/Bsa50101.xhtml'"))
        assertTrue(script.contains("url.origin !== location.origin"))
        assertTrue(script.contains("url.username || url.password || url.hash"))
        assertTrue(script.contains("raw.charAt(0) === '#'"))
        assertTrue(script.contains("timetablePaths.indexOf(url.pathname) >= 0"))
        assertTrue(script.contains("if (link.getAttribute('href') !== '#') return false;"))
        assertTrue(script.contains("['学生時間割表', '学生時間割', '時間割表']"))
        // 任意の href を辿ったり、パスを推測したりしない。
        assertFalse(script.contains("location.assign"))
        assertFalse(script.contains("location.replace"))
        assertFalse(script.contains("window.open"))
        assertFalse(script.contains("startsWith("))
        assertFalse(script.contains("indexOf(url.pathname) === 0"))
    }

    @Test fun closedMenuPanelIsOpenedOnceAndDoesNotCountAsNavigation() {
        val script = ClassTimetableEntryDom.SCRIPT
        assertTrue(script.contains("!panel.classList.contains('ui-panel-open')"))
        assertTrue(script.contains("panel.getAttribute('aria-hidden') === 'true'"))
        assertTrue(script.contains("a[href=\"#menuPanel\"]"))
        assertTrue(script.contains("__cabetusTimetableMenuPanel"))
        assertTrue(script.contains("openers.length !== 1"))
        // パネルを開くだけの場合は false（サービスが同じ document で再試行できる）。
        assertTrue(script.contains("openers[0].click();"))
        assertFalse(script.contains("openers[0].click();\n  return true;"))
        // 時間割リンクを実際にクリックしたときだけ true。
        assertTrue(script.contains("targets[0].click();"))
        assertTrue(script.contains("targets[0].click();\n    return true;"))
    }

    @Test fun timetablePathsStayInSyncWithTheExtractor() {
        val script = ClassTimetableEntryDom.SCRIPT
        org.cabetus.data.classweb.ClassTimetableDomExtractor.TARGET_PATHS.forEach { path ->
            assertTrue("missing $path", script.contains("'$path'"))
        }
    }
}
