package org.cabetus.ui.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * アプリ内 WebView の共通セキュリティ設定。
 *
 * LETUS / CLASS の SSO・Moodle は JavaScript と Cookie が必要なため有効にするが、
 * ローカルファイルアクセス、JS ブリッジ、混合コンテンツ、ポップアップ等は無効化する。
 * `addJavascriptInterface` は使用しない。`evaluateJavascript` は CLASS の表示補正
 * (ClassMenuPanelFix) と、時間割表・出欠詳細ページの DOM 検証・読み取り
 * (ClassTimetableDomExtractor / ClassAttendanceDetailDomExtractor) で固定スクリプトを
 * 実行する場合に限り、認証フォームの値は読まない。
 */
object SecureWebViewConfig {

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, allowThirdPartyCookies: Boolean = true) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, allowThirdPartyCookies)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true

            // 認証 Cookie を平文 HTTP へ漏らさない。
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true

            // パスワード等のフォーム値を WebView に保存させない。
            @Suppress("DEPRECATION")
            saveFormData = false
        }
    }

    fun flushCookies() {
        CookieManager.getInstance().flush()
    }
}
