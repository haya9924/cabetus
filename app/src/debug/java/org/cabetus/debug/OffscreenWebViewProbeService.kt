package org.cabetus.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import java.io.ByteArrayInputStream

/**
 * Debug-only probe: renders a synthetic page in a WebView on an offscreen virtual display.
 *
 * - No network, no cookies, no external storage, no JS interface, no screen capture.
 * - Only synthetic content is loaded; every URL is rejected.
 * - Logs only booleans / counters / visibility, never real URLs or pages.
 */
class OffscreenWebViewProbeService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null

    private var evalCount = 0
    private var lastImageAvailable = false

    private val evaluateRunnable = object : Runnable {
        override fun run() {
            evaluateProbe()
            handler.postDelayed(this, EVAL_INTERVAL_MS)
        }
    }

    private val autoStopRunnable = Runnable {
        Log.i(TAG, "autoStop=true")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundWithType()

        imageReader = ImageReader.newInstance(
            WIDTH,
            HEIGHT,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    null
                }
                lastImageAvailable = image != null
                image?.close()
            }, handler)
        }

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (displayManager == null) {
            Log.i(TAG, "displayManager=false")
            stopSelf()
            return
        }

        virtualDisplay = displayManager.createVirtualDisplay(
            "CabetusOffscreenProbe",
            WIDTH,
            HEIGHT,
            resources.displayMetrics.densityDpi,
            imageReader?.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        Log.i(TAG, "virtualDisplay=${virtualDisplay != null}")

        val display = virtualDisplay?.display
        if (display == null) {
            Log.i(TAG, "display=false")
            stopSelf()
            return
        }

        val content = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = request?.url?.scheme != "data"

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = url?.startsWith("data:text/html") != true

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? = if (request?.url?.scheme == "data") null else if (request?.isForMainFrame == true && request.url.toString() == BASE_URL) {
                    WebResourceResponse("text/html", "UTF-8", SYNTHETIC_HTML.byteInputStream())
                } else emptyResponse()
            }
            setBackgroundColor(0xFF000000.toInt())
        }
        content.addView(view)
        webView = view

        try {
            presentation = Presentation(this, display).apply {
                setContentView(content)
                show()
            }
            Log.i(TAG, "presentation=true")
        } catch (e: Exception) {
            Log.i(TAG, "presentation=false")
            stopSelf()
            return
        }

        view.loadData(android.util.Base64.encodeToString(SYNTHETIC_HTML.toByteArray(), android.util.Base64.NO_WRAP), "text/html", "base64")

        handler.postDelayed(evaluateRunnable, EVAL_INTERVAL_MS)
        handler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
        Log.i(TAG, "started=true")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stopCommand=true")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "command=true")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let { view ->
            try {
                view.stopLoading()
                view.loadUrl("about:blank")
                (view.parent as? FrameLayout)?.removeView(view)
                view.destroy()
            } catch (e: Exception) {
                Log.i(TAG, "webViewDestroy=false")
            }
        }
        webView = null
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.i(TAG, "presentationDismiss=false")
        }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        Log.i(TAG, "destroyed=true")
        super.onDestroy()
    }

    private fun evaluateProbe() {
        val view = webView ?: return
        view.evaluateJavascript(EVAL_JS) { result ->
            evalCount++
            val payload = parseJson(result)
            if (payload == null) {
                Log.i(TAG, "eval=$evalCount parsed=false image=$lastImageAvailable")
                return@evaluateJavascript
            }
            Log.i(
                TAG,
                "eval=$evalCount visible=${payload["v"]} sizeOk=${payload["s"]}" +
                    " domOk=${payload["d"]} geomOk=${payload["g"]} ticks=${payload["t"]}" +
                    " image=$lastImageAvailable",
            )
        }
    }

    private fun parseJson(raw: String?): Map<String, Any>? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val unescaped = raw.trim().removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val obj = org.json.JSONObject(unescaped)
            mapOf(
                "v" to (obj.optString("v") == "visible"),
                "s" to obj.optBoolean("s"),
                "d" to obj.optBoolean("d"),
                "g" to obj.optBoolean("g"),
                "t" to obj.optInt("t"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        204,
        "No Content",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cabetus offscreen probe",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Debug-only offscreen WebView probe" }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithType() {
        val stopIntent = Intent(this, OffscreenWebViewProbeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Cabetus probe")
            .setContentText("Offscreen WebView probe running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "STOP", stopPendingIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "CabetusOffscreenProbe"
        private const val CHANNEL_ID = "probe"
        private const val NOTIFICATION_ID = 74009
        private const val WIDTH = 720
        private const val HEIGHT = 1280
        private const val MAX_IMAGES = 2
        private const val EVAL_INTERVAL_MS = 3_000L
        private const val AUTO_STOP_MS = 30_000L
        private const val BASE_URL = "https://probe.invalid/"
        private const val ACTION_START = "org.cabetus.debug.probe.START"
        private const val ACTION_STOP = "org.cabetus.debug.probe.STOP"

        private const val SYNTHETIC_HTML = """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            #probe-span { display:inline-block; padding:8px; background:#123456; color:#fff; }
            </style></head>
            <body>
            <span id="probe-span">probe</span>
            <script>
            window.__probeTicks = 0;
            window.__probeTimer = setInterval(function () { window.__probeTicks++; }, 1000);
            </script>
            </body></html>
        """

        private const val EVAL_JS = """
            (function () {
              var el = document.getElementById('probe-span');
              var rect = el ? el.getBoundingClientRect() : null;
              return JSON.stringify({
                v: document.visibilityState,
                s: window.innerWidth > 0 && window.innerHeight > 0,
                d: !!el,
                g: !!rect && rect.width > 0 && rect.height > 0,
                t: window.__probeTicks || 0
              });
            })();
        """

        fun startIntent(context: Context): Intent =
            Intent(context, OffscreenWebViewProbeService::class.java).setAction(ACTION_START)
    }
}
