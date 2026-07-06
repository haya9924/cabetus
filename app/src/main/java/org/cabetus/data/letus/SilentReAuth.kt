package org.cabetus.data.letus

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 非表示 WebView で LETUS の認証必須ページを読み込み、SSO による自動再認証を行う。
 * WebView はメインスレッドでの生成が必須なため Main で実行する。
 * 成功すれば Cookie が更新され、以降の HTTP アクセスが通るようになる。
 */
object SilentReAuth {

    /** @return 再認証に成功（=ログインページに留まらず）したら true。 */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun tryReAuth(context: Context, timeoutMillis: Long = 60_000): Boolean =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { cont ->
                    val webView = WebView(context)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true

                    var settled = false
                    fun finish(result: Boolean) {
                        if (settled) return
                        settled = true
                        CookieManager.getInstance().flush()
                        webView.stopLoading()
                        webView.destroy()
                        if (cont.isActive) cont.resume(result)
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val current = url ?: return
                            // letus ホストの認証系以外のページへ到達したら再認証成功。
                            // 途中の IdP（idp.admin.tus.ac.jp）・Microsoft ログイン・
                            // /auth/shibboleth 等では待ち続け、SSO の自動遷移に任せる
                            // （完了しなければタイムアウトで false に倒れる）。
                            if (LetusParsing.isAuthenticatedLetusUrl(current)) {
                                finish(true)
                            }
                        }
                    }
                    cont.invokeOnCancellation { finish(false) }
                    webView.loadUrl(LetusConstants.MY_URL)
                }
            } ?: false
        }
}
