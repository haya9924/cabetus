package org.cabetus.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import org.cabetus.data.letus.LetusConstants
import org.cabetus.data.letus.LetusParsing

/**
 * LETUS ログイン画面。内蔵 WebView で認証し、Cookie を保存する。
 * ログイン完了（LETUS のログインページ以外に到達）で「次へ進む」が有効になる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onDone: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    var progress by remember { mutableStateOf(0) }
    var loggedIn by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LETUS ログイン") },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再読み込み")
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = onDone,
                enabled = loggedIn,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(if (loggedIn) "ログイン完了 — 次へ進む" else "ログインしてください")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                CookieManager.getInstance().flush()
                                val current = url ?: return
                                // letus ホストの認証系以外のページに到達 = ログイン完了
                                if (LetusParsing.isAuthenticatedLetusUrl(current)) {
                                    if (!loggedIn) {
                                        loggedIn = true
                                        viewModel.markLoggedIn()
                                    }
                                }
                            }
                        }
                        webChromeClientProgress { progress = it }
                        loadUrl(LetusConstants.MY_URL)
                        webViewRef = this
                    }
                },
            )
        }
    }
}

/** 進捗コールバックを付けるための拡張。 */
private fun WebView.webChromeClientProgress(onProgress: (Int) -> Unit) {
    webChromeClient = object : android.webkit.WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }
    }
}
