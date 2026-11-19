package org.cabetus.ui.browser

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * CLASS 出席読み取り用のオフスクリーン WebView ホスト。
 *
 * 画面に出さない仮想ディスプレイ (720x1280 / RGBA_8888 / 2 バッファ) の上に
 * WebView を載せた Presentation を 1 つだけ生成する。ページの読み込み・
 * JavaScript 実行・クライアント設定は行わない（呼び出し側の責務）。
 *
 * 使い方:
 * ```
 * val host = ClassAttendanceVirtualDisplayHost(context)
 * val view = try { host.create() } catch (t: Throwable) { host.close(); throw t }
 * ...
 * host.close()
 * ```
 *
 * - すべてメインスレッドで呼ぶ。`create()` は 1 回だけ、`close()` は何度呼んでも安全。
 * - 途中で失敗した場合も、確保済みのリソースは個別に解放してから例外を投げ直す。
 */
class ClassAttendanceVirtualDisplayHost(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var content: FrameLayout? = null
    private var webView: WebView? = null
    private var closed = false

    /**
     * 仮想ディスプレイを作成し、セキュア設定済みの WebView を返す。
     * ページの読み込みは行わない。
     */
    fun create(): WebView {
        ensureMainThread()
        check(!closed) { "ClassAttendanceVirtualDisplayHost is already closed." }
        check(webView == null) { "ClassAttendanceVirtualDisplayHost.create() must not be called twice." }

        try {
            val reader = ImageReader.newInstance(
                WIDTH,
                HEIGHT,
                PixelFormat.RGBA_8888,
                MAX_IMAGES,
            )
            imageReader = reader
            // 2 バッファ固定で運用するため、常に最新フレームを取得して即時解放し滞留させない。
            reader.setOnImageAvailableListener({ source ->
                try {
                    source.acquireLatestImage()?.close()
                } catch (_: IllegalStateException) {
                    // 取得できない場合は解放済み。何もしない。
                }
            }, handler)

            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displayHandle = displayManager.createVirtualDisplay(
                DISPLAY_NAME,
                WIDTH,
                HEIGHT,
                context.resources.displayMetrics.densityDpi,
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: error("Failed to create the private virtual display.")
            virtualDisplay = displayHandle
            val display = displayHandle.display
                ?: error("The private virtual display is not available.")

            val root = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            val view = WebView(context)
            webView = view // Own immediately so configuration/show failures also destroy it.
            SecureWebViewConfig.configure(view)
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            content = root

            val window = Presentation(context, display)
            presentation = window
            window.setContentView(root)
            window.show()

            return view
        } catch (t: Throwable) {
            // 失敗した host は再利用させない。
            closed = true
            closeResources()
            throw t
        }
    }

    /** 生成したリソースを解放する。部分的にしか生成できていない場合も安全。 */
    fun close() {
        ensureMainThread()
        if (closed) return
        closed = true
        closeResources()
    }

    private fun closeResources() {
        // 各リソースは独立して解放する（1 つの失敗で残りを飛ばさない）。
        val view = webView
        webView = null
        if (view != null) {
            runCatching { view.stopLoading() }
            runCatching { (view.parent as? FrameLayout)?.removeView(view) }
            runCatching { view.destroy() }
        }

        val root = content
        content = null
        if (root != null) {
            runCatching { root.removeAllViews() }
        }

        val window = presentation
        presentation = null
        if (window != null) {
            runCatching { window.dismiss() }
        }

        val displayHandle = virtualDisplay
        virtualDisplay = null
        if (displayHandle != null) {
            runCatching { displayHandle.release() }
        }

        val reader = imageReader
        imageReader = null
        if (reader != null) {
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ClassAttendanceVirtualDisplayHost must be used on the main thread."
        }
    }

    private companion object {
        const val DISPLAY_NAME = "CabetusClassAttendance"
        const val WIDTH = 720
        const val HEIGHT = 1280

        /** ImageReader のメモリ上限（2 バッファ）。 */
        const val MAX_IMAGES = 2
    }
}
