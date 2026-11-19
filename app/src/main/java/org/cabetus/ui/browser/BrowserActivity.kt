package org.cabetus.ui.browser

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.cabetus.ui.theme.TusTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * アプリ内ブラウザ。通知・ウィジェット・各画面から explicit Intent でのみ起動する。
 * exported=false のため外部アプリから任意 URL を読み込ませることはできない。
 */
@AndroidEntryPoint
class BrowserActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var fetchScheduler: org.cabetus.work.FetchScheduler
    @javax.inject.Inject lateinit var backgroundTour: ClassAttendanceBackgroundController
    private var browserLease = false
    private var pipMode by androidx.compose.runtime.mutableStateOf(false)
    private val pipAvailable get() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun minimizeBrowser() {
        try {
            val entered = pipAvailable && enterPictureInPictureMode(android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(9, 16)).build())
            if (!entered) android.widget.Toast.makeText(this, "小窓表示を利用できません。端末の権限設定を確認してください。", android.widget.Toast.LENGTH_LONG).show()
        } catch (_: IllegalStateException) {
            android.widget.Toast.makeText(this, "現在は小窓表示にできません。", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            android.widget.Toast.makeText(this, "小窓表示を利用できません。", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(this, "端末の設定で小窓表示を許可してください。", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode
    }

    private var visitedLetus = false
    private var syncRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browserLease = backgroundTour.browserOpened()
        if (!browserLease) {
            android.widget.Toast.makeText(this, "出欠の更新中です。中止してからCLASSを開いてください。", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val initialUrl = intent?.getStringExtra(EXTRA_URL)
        visitedLetus = savedInstanceState?.getBoolean("visitedLetus") ?: false
        setContent {
            TusTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InAppBrowserScreen(
                        initialUrl = initialUrl,
                        attendanceSyncRequested = intent?.getBooleanExtra(EXTRA_ATTENDANCE_SYNC, false) == true,
                        onClose = { finish() },
                        inPictureInPicture = pipMode,
                        pictureInPictureAvailable = pipAvailable,
                        onMinimize = { minimizeBrowser() },
                        onLetusVisited = { visitedLetus = true },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("visitedLetus", visitedLetus)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        SecureWebViewConfig.flushCookies()
        if (visitedLetus && !syncRequested) {
            syncRequested = true
            fetchScheduler.fetchAfterBrowsing()
        }
        super.finish()
    }

    override fun onPause() {
        // SSO の Cookie を確実に永続化する。削除はしない（ログイン状態を維持するため）。
        SecureWebViewConfig.flushCookies()
        super.onPause()
    }

    override fun onDestroy() {
        if (browserLease) { backgroundTour.browserClosed(); browserLease = false }
        SecureWebViewConfig.flushCookies()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ATTENDANCE_SYNC = "org.cabetus.extra.ATTENDANCE_SYNC"
        const val EXTRA_URL = "org.cabetus.extra.BROWSER_URL"

        fun intent(context: android.content.Context, url: String): Intent =
            Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
