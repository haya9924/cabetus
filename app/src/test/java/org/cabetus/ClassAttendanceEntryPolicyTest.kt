package org.cabetus

import org.cabetus.ui.browser.ClassAttendanceEntryPolicy as P
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassAttendanceEntryPolicyTest {
    @Test fun sharedSessionIntermediatesAreAllowedWithoutImplyingAuthentication() {
        assertEquals(P.Page.CLASS, P.classify("https://class.admin.tus.ac.jp/"))
        assertEquals(P.Page.SSO, P.classify("https://idp.admin.tus.ac.jp/idp/profile/SAML2/Redirect/SSO"))
        assertEquals(P.Page.SSO, P.classify("https://login.microsoftonline.com/common/oauth2/authorize"))
    }
    @Test fun unknownAndImpersonatedOriginsAreBlocked() {
        listOf(null, "invalid", "http://class.admin.tus.ac.jp/", "https://class.admin.tus.ac.jp.evil.test/",
            "https://evil.class.admin.tus.ac.jp/", "https://user@class.admin.tus.ac.jp/",
            "https://class.admin.tus.ac.jp:444/", "https://login.live.com/", "https://letus.ed.tus.ac.jp/")
            .forEach { assertEquals(it, P.Page.BLOCKED, P.classify(it)) }
    }
}
