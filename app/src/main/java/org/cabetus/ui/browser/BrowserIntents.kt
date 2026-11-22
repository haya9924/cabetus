package org.cabetus.ui.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 課題リンク・CLASS への導線をアプリ内ブラウザへつなぐヘルパー。
 *
 * アプリ内で開く対象（LETUS / CLASS）は [BrowserActivity] へ、
 * それ以外の http(s) URL は従来どおり外部ブラウザへ渡す。
 * javascript: / intent: / file: などは外部にも渡さない。
 */
object BrowserIntents {

    fun createOpenIntent(context: Context, rawUrl: String?): Intent? {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return null

        val intent = if (BrowserUrlPolicy.isAllowedStart(url)) {
            Intent(context, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url)
        } else {
            createExternalIntent(url)
        } ?: return null

        // Application / widget / notification から起動する場合に必要。
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }

    fun open(context: Context, url: String?): Boolean {
        val intent = createOpenIntent(context, url) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun createExternalIntent(rawUrl: String?): Intent? {
        val url = rawUrl?.trim().orEmpty()
        if (!BrowserUrlPolicy.isSafeExternalUrl(url)) return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    fun openExternal(context: Context, url: String?): Boolean {
        val intent = createExternalIntent(url) ?: return false
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
