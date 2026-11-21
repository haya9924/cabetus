package org.cabetus

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View.MeasureSpec
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.cabetus.data.classweb.ClassScheduleNoticesDom
import org.cabetus.domain.ClassScheduleNoticeOrganizer
import org.cabetus.domain.ClassScheduleNoticeOrganizer.Course
import org.cabetus.domain.ClassScheduleNoticeOrganizer.Kind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the production DOM script, parser, and organizer in Android WebView without contacting CLASS. */
internal object ClassScheduleNoticesDeviceChecks {
    private const val BOARD = "https://class.admin.tus.ac.jp/uprx/up/bs/bsd507/Bsd50701.xhtml"
    private val fixtureTitles = listOf(
        "11/21 【休講】（ＡＢＣ１２３４）5限",
        "11月21日 物理学の教室変更について",
        "11月21日 物理学実験の教室変更について",
        "11月21日 時間割変更のお知らせ",
    )
    private val html = """
        <!doctype html><meta charset="UTF-8"><h2 class="ui-title">掲示一覧</h2>
        <main class="mainContent"><div class="ui-tabs">
          <ul class="ui-tabs-nav"><li role="tab" aria-expanded="true">
            <a class="ui-tabs-anchor ui-btn-active" href="#schedule">時間割変更</a>
          </li></ul>
          <div id="schedule" role="tabpanel"><ul class="ui-listview" data-role="listview">
            ${fixtureTitles.joinToString("\n") { "<li class=\"listIndent\"><a class=\"ui-commandlink ui-icon-carat-r\" href=\"#detail\">$it</a></li>" }}
          </ul></div>
        </div></main>
    """.trimIndent()

    fun run(instrumentation: Instrumentation): Bundle {
        val ready = CountDownLatch(1)
        var webView: WebView? = null
        try {
            instrumentation.runOnMainSync {
                val view = WebView(instrumentation.targetContext)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.blockNetworkLoads = true
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())

                    override fun onPageFinished(view: WebView, url: String) {
                        if (url == BOARD) ready.countDown()
                    }
                }
                view.measure(MeasureSpec.makeMeasureSpec(480, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY))
                view.layout(0, 480, 800)
                // The HTTP(S) base URL supplies document.origin/path; HTML is local and network loads are blocked.
                view.loadDataWithBaseURL(BOARD, html, "text/html", "UTF-8", BOARD)
            }
            check(ready.await(15, TimeUnit.SECONDS)) { "Synthetic CLASS page did not finish loading" }
            val view = checkNotNull(webView)
            val deadline = SystemClock.elapsedRealtime() + 8_000
            var snapshot: ClassScheduleNoticesDom.Snapshot? = null
            while (snapshot == null && SystemClock.elapsedRealtime() < deadline) {
                snapshot = ClassScheduleNoticesDom.parse(evaluate(instrumentation, view, ClassScheduleNoticesDom.SCRIPT))
                if (snapshot == null) Thread.sleep(100)
            }
            val originAndPath = evaluate(instrumentation, view, "location.origin + location.pathname")
            val captured = checkNotNull(snapshot) { "Production script did not recognize synthetic CLASS list: $originAndPath" }
            check(captured.titles == fixtureTitles && !captured.hasMore) { "CLASS titles did not round-trip" }
            val result = ClassScheduleNoticeOrganizer.organize(
                captured.titles,
                listOf(Course("ABC1234", "数学"), Course("PHY0001", "物理学"), Course("PHY0002", "物理学実験")),
            )
            fun assertNotice(code: String, titleIndex: Int, kind: Kind) {
                val notice = result.byCourse[code]?.singleOrNull()
                check(notice != null && notice.title == fixtureTitles[titleIndex] && notice.kind == kind) { "Wrong notice assignment for $code" }
            }
            assertNotice("ABC1234", 0, Kind.CANCELLATION)
            assertNotice("PHY0001", 1, Kind.ROOM_CHANGE)
            assertNotice("PHY0002", 2, Kind.ROOM_CHANGE)
            check(result.unmatched.map { it.title } == listOf(fixtureTitles[3]))
            check(evaluate(instrumentation, view, "location.hash") == "\"\"") { "Notice link was opened" }
            return Bundle().apply { putString("schedule_notices", "PASS") }
        } finally {
            instrumentation.runOnMainSync { webView?.destroy() }
        }
    }

    private fun evaluate(instrumentation: Instrumentation, view: WebView, script: String): String? {
        val finished = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) { raw ->
                result = raw
                finished.countDown()
            }
        }
        check(finished.await(5, TimeUnit.SECONDS)) { "WebView evaluation timed out" }
        return result
    }
}
