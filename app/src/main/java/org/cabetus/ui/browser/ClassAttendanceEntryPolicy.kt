package org.cabetus.ui.browser

import java.net.URI

/** Exact origins only. Provider paths/queries stay opaque; no injected provider scripts. */
object ClassAttendanceEntryPolicy {
    enum class Page { CLASS, SSO, BLOCKED }
    fun classify(url: String?): Page = runCatching {
        val uri = URI(url ?: return Page.BLOCKED)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return Page.BLOCKED
        when (uri.host?.lowercase()) {
            "class.admin.tus.ac.jp" -> Page.CLASS
            "idp.admin.tus.ac.jp", "login.microsoftonline.com" -> Page.SSO
            else -> Page.BLOCKED
        }
    }.getOrDefault(Page.BLOCKED)
}
