package org.cabetus.data.letus

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * OkHttp の Cookie を android.webkit.CookieManager に委譲する。
 * これにより WebView での初回ログインで得た Cookie を HTTP クライアントが共有し、
 * 認証必須ページへのアクセス時に SSO が自動再認証する挙動を利用できる。
 */
class WebViewCookieJar : CookieJar {

    private val cookieManager: CookieManager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()
        cookies.forEach { cookie ->
            cookieManager.setCookie(urlString, cookie.toString())
        }
        cookieManager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieHeader = cookieManager.getCookie(url.toString()) ?: return emptyList()
        return cookieHeader.split(";")
            .mapNotNull { pair ->
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) null else Cookie.parse(url, trimmed)
            }
    }
}
