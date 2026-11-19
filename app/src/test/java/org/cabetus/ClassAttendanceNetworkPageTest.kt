package org.cabetus

import org.cabetus.data.classweb.ClassAttendanceNetworkPage
import org.junit.Assert.*
import org.junit.Test

class ClassAttendanceNetworkPageTest {
    private val path = "/uprx/up/km/kmd508/Kmd50801.xhtml"
    private fun context() = "<select name='pmPage:funcForm:kaikoNendoGakkiList'><option value='x'>2025年度 前期</option><option selected value='y'>2026年度 後期</option></select>"
    private fun page(content: String) = ClassAttendanceNetworkPage("<div class='mainContent'>$content</div>", path)
    @Test fun selectedContextOnly() { assertEquals(2026 to "後期", page(context()).context()) }
    @Test fun loginRequiresBothStructuralMarkers() {
        assertFalse(ClassAttendanceNetworkPage("<form id='loginForm'></form>", path).isLogin())
        assertTrue(ClassAttendanceNetworkPage("<form id='loginForm'></form><a href='/uprx/MobileShibbolethAuthServlet'>ログイン</a>", path).isLogin())
    }
    @Test fun hiddenAllowlistAndActualCommandKey() {
        val p = page("<form id='pmPage:funcForm' action='$path'><input type='hidden' name='javax.faces.ViewState' value='synthetic-not-real'>${context()}</form>")
        val fields = p.postFields("pmPage:funcForm:row")
        assertTrue(fields.contains("pmPage:funcForm:row" to "pmPage:funcForm:row"))
        assertFalse(fields.any { it.first == "component" })
        assertFalse(p.toString().contains("synthetic-not-real"))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectUnknownHidden() {
        page("<form id='pmPage:funcForm' action='$path'><input type='hidden' name='unknown' value='never-send'></form>").postFields("x")
    }
    @Test(expected=IllegalArgumentException::class) fun rejectPassword() {
        page("<form id='pmPage:funcForm' action='$path'><input type='password' name='password'></form>").postFields("x")
    }
    @Test(expected=IllegalArgumentException::class) fun rejectAmbiguousContext() { page(context()+context()).context() }
    @Test(expected=IllegalArgumentException::class) fun rejectPagination() { page("<div class='ui-paginator'></div>").roster() }
    @Test(expected=UnsupportedOperationException::class) fun unverifiedBackNeverGuessed() {
        page("<form id='pmPage:funcForm' action='$path'><button>戻る</button></form>").backComponent()
    }
}
