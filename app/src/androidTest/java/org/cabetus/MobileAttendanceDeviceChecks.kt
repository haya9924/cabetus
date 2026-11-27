package org.cabetus

import android.annotation.SuppressLint
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor
import org.cabetus.data.classweb.ClassMobileAttendanceEntryDom
import org.cabetus.data.classweb.MobileAttendanceRepository
import org.cabetus.data.local.ClassCourseEntity
import org.cabetus.data.local.TusDatabase
import org.cabetus.data.network.NetworkMonitor
import org.cabetus.domain.MobileAttendanceEvidence
import org.cabetus.ui.browser.ClassAttendanceVirtualDisplayHost
import java.net.URI
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 実機/エミュレータで、**本番の** [ClassAttendanceVirtualDisplayHost]（VirtualDisplay + Presentation）上の
 * WebView に対して、モバイル出席の入口スクリプト・検証・抽出・保存・表示評価を通しで確認する。
 *
 * CLASS へは接続しない。`blockNetworkLoads` と `shouldInterceptRequest` で通信を遮断し、
 * 合成 HTML を基点 URL 付きで読み込むだけ。アカウント・アプリ本体の DB・UI は使わず、
 * 出席コードの入力値・送信も一切扱わない（読み取り専用）。
 */
internal object MobileAttendanceDeviceChecks {
    private const val MENU = "https://class.admin.tus.ac.jp/uprx/up/bs/bsa501/Bsa50101.xhtml"
    private const val MOBILE = "https://class.admin.tus.ac.jp/uprx/up/pk/pky501/Pky50102.xhtml"

    private const val PENDING_COURSE = "物理学 2"
    private const val CONFIRMED_COURSE = "芸術"

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd(E)", Locale.JAPANESE)

    private val menuHtml = """
        <!doctype html><html><head><meta charset="UTF-8"></head><body>
        <div data-role="page"><div class="ui-content">
          <a class="ui-link ui-btn ui-icon-carat-r" href="#mobile">モバイル出席登録</a>
        </div></div></body></html>
    """.trimIndent()

    fun run(instrumentation: Instrumentation): Bundle {
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, TusDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var host: ClassAttendanceVirtualDisplayHost? = null
        try {
            host = onMain(instrumentation) { ClassAttendanceVirtualDisplayHost(instrumentation.targetContext) }
            // 3) の評価に使う「今日（JST）」は、実際の読み取りと同じ瞬間に合わせて組み立てる。
            val now = LocalDateTime.now(MobileAttendanceEvidence.ZONE)
            val pageDate = now.format(dateFormatter)
            val window = confirmationWindow(now)

            val view = onMain(instrumentation) { checkNotNull(host).create() }
            blockNetworkLoads(instrumentation, view)

            // 1) 入口メニュー: 一意な可視リンクだけを押して "opened" を返すこと。
            check(
                load(instrumentation, view, MENU, menuHtml, ".ui-link.ui-btn.ui-icon-carat-r") { it == "\"1\"" },
            ) { "Menu fixture did not render" }
            val entry = evaluate(instrumentation, view, ClassMobileAttendanceEntryDom.SCRIPT)
            check(entry == "\"opened\"") { "Entry script expected \"opened\", was $entry" }
            val path = evaluate(instrumentation, view, "location.pathname")
            check(path == "\"${pathOf(MENU)}\"") {
                "Entry script navigated away from the synthetic menu page: $path"
            }

            // 2) 出席登録ページ: 本番の仮想ディスプレイ上で可視判定と抽出が通ること。
            check(
                load(
                    instrumentation, view, MOBILE, mobileHtml(pageDate, window), ".mainContent .dataAll",
                ) { it == "\"2\"" },
            ) { "Mobile attendance fixture did not render" }

            val visibility = evaluate(instrumentation, view, "document.visibilityState")
            check(visibility == "\"visible\"") { "Virtual display document is not visible: $visibility" }

            val verification = ClassMobileAttendanceDomExtractor.parseVerificationResult(
                evaluate(instrumentation, view, ClassMobileAttendanceDomExtractor.VERIFY_SCRIPT),
            )
            check(verification == null) { "Verification failed on the virtual display: ${verification?.name}" }

            val parsed = ClassMobileAttendanceDomExtractor.parseEvaluationResult(
                evaluate(instrumentation, view, ClassMobileAttendanceDomExtractor.SCRIPT),
            )
            check(parsed.error == null) { "Extraction error: ${parsed.error?.name}" }
            val observation = checkNotNull(parsed.observation) { "Extraction returned no observation" }
            check(observation.campusNetworkLabelVisible) { "Campus network label was not visible" }
            check(observation.courses.size == 2) { "Expected 2 courses, was ${observation.courses.size}" }
            check(observation.pageDate?.matches(now.toLocalDate()) == true) {
                "Page date did not match today: ${observation.pageDate}"
            }

            // 3) 保存（メモリ + インメモリ Room）と表示評価。
            // repository は読み取り時の回線と現在の回線が一致しないとスナップショットを破棄するため、
            // 実際の端末回線を 1 度読んで、同じ回線で capture する（端末状態を送信はしない）。
            val monitor = NetworkMonitor(instrumentation.targetContext)
            val network = runBlocking { monitor.status.first() }
            check(network.isKnown && network.isConnected && network.transport != null) {
                "Device has no connected network; the freshness check cannot be validated."
            }
            val repository = MobileAttendanceRepository(
                timetableDao = database.timetableDao(),
                classCourseDao = database.classCourseDao(),
                clock = Clock.system(MobileAttendanceEvidence.ZONE),
                networkMonitor = monitor,
                confirmations = database.mobileAttendanceConfirmationDao(),
            )
            database.classCourseDao().upsertAll(
                listOf(
                    ClassCourseEntity("ART1", CONFIRMED_COURSE, "", null, null),
                    ClassCourseEntity("PHY2", PENDING_COURSE, "", null, null),
                ),
            )
            val result = runBlocking { repository.capture(observation, network) }
            check(result.saved) { "Capture was not saved" }
            check(repository.snapshot.value != null) { "Snapshot was not kept in memory" }

            // 端末の日付が変わっていないことを前提に、受付時間内の時刻で評価する
            // （23:59 の直後だけは、その日の受付終了ちょうどの時刻に寄せる）。
            val evaluationNow = window.evaluationInstant(LocalDateTime.now(MobileAttendanceEvidence.ZONE))
            val rows = MobileAttendanceEvidence.evaluatePage(
                snapshot = repository.snapshot.value,
                now = evaluationNow,
                currentTransport = network.transport,
                currentNetworkKnown = true,
            )
            check(rows.map { it.courseName } == listOf(PENDING_COURSE, CONFIRMED_COURSE)) {
                "Unexpected rows: ${rows.map { it.courseName }}"
            }
            check(rows[0].attendance.status == MobileAttendanceEvidence.Status.PENDING) {
                "Expected PENDING for $PENDING_COURSE, was ${rows[0].attendance.status}"
            }
            check(rows[1].attendance.status == MobileAttendanceEvidence.Status.CONFIRMED) {
                "Expected CONFIRMED for $CONFIRMED_COURSE, was ${rows[1].attendance.status}"
            }
            check(rows.none { it.attendance.status == MobileAttendanceEvidence.Status.NOT_APPLICABLE }) {
                "A row fell back to ${MobileAttendanceEvidence.STATUS_UNKNOWN_LABEL}"
            }

            return Bundle().apply {
                putString("mobile_attendance", "PASS")
                putString("page_date", pageDate)
                putString("window", window.label)
                putString("entry", entry)
                putString("courses", rows.joinToString(",") { "${it.courseName}:${it.attendance.status}" })
            }
        } finally {
            val created = host
            if (created != null) {
                runCatching { instrumentation.runOnMainSync { created.close() } }
            }
            database.close()
        }
    }

    /** 本番の仮想ディスプレイホストは main thread での生成・破棄を要求する。 */
    private fun <T : Any> onMain(instrumentation: Instrumentation, block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        return checkNotNull(value)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun blockNetworkLoads(instrumentation: Instrumentation, view: WebView) {
        instrumentation.runOnMainSync {
            view.settings.javaScriptEnabled = true
            view.settings.blockNetworkLoads = true
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse = blocked()
            }
        }
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())

    /**
     * HTTP(S) の基点 URL を付けて合成 HTML を読み込み、[selector] の件数が [expected] を満たすまで待つ。
     * ネットワークは遮断しているため CLASS へは接続しない。
     */
    private fun load(
        instrumentation: Instrumentation,
        view: WebView,
        baseUrl: String,
        html: String,
        selector: String,
        expected: (String?) -> Boolean,
    ): Boolean {
        val finished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse = blocked()

                override fun onPageFinished(view: WebView, url: String) {
                    if (url.substringBefore('#') == baseUrl) finished.countDown()
                }
            }
            view.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", baseUrl)
        }
        finished.await(15, TimeUnit.SECONDS)
        val probe = "document.querySelectorAll('$selector').length"
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (expected(evaluate(instrumentation, view, probe))) return true
            Thread.sleep(100)
        }
        return false
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

    private fun pathOf(url: String): String = URI(url).path

    /**
     * 出席確認の受付時間。既定は「今から10分前〜80分後」を同じ JST 日の 00:00..23:59 に収める。
     * 日跨ぎになる場合も同じ日の中へ寄せる（23:59 の直後だけは受付終了扱いになる）。
     */
    private fun confirmationWindow(now: LocalDateTime): Window {
        val maxMinute = 23 * 60 + 59
        val nowMinute = now.hour * 60 + now.minute
        val start = (nowMinute - 10).coerceIn(0, maxMinute - 1)
        val end = (nowMinute + 80).coerceIn(start + 1, maxMinute)
        return Window(now.toLocalDate(), start, end)
    }

    /** 同じ JST 日の受付時間（分単位）。 */
    private data class Window(val date: java.time.LocalDate, val startMinute: Int, val endMinute: Int) {
        val label: String get() = "${format(startMinute)}～${format(endMinute)}"

        /** 受付中（開始ちょうど〜終了ちょうど）か。 */
        fun contains(actual: LocalDateTime): Boolean =
            !actual.isBefore(at(startMinute)) && !actual.isAfter(at(endMinute))

        /** 受付中なら [actual] を、23:59 の直後だけは受付終了ちょうどの時刻を返す。 */
        fun evaluationInstant(actual: LocalDateTime): LocalDateTime =
            if (contains(actual)) actual else at(endMinute)

        private fun at(minuteOfDay: Int): LocalDateTime =
            LocalDateTime.of(date, java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))

        private fun format(minuteOfDay: Int): String =
            "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
    }

    private fun mobileHtml(pageDate: String, window: Window): String {
        val label = window.label
        return """
            <!doctype html><html><head><meta charset="UTF-8"></head><body>
            <h1><span>$pageDate</span></h1>
            <h2 class="ui-title">モバイル出席登録</h2>
            <h3>出席登録</h3>
            <main class="mainContent">
              <div id="pmPage:funcForm:dataList">
                <span>学内ネットワークからのアクセス</span>
                <div class="dataAll">
                  <label class="textOf sizeBig">$PENDING_COURSE</label>
                  <label class="signSize">出席確認時間：$label</label>
                  <label class="signFlging">出席確認中</label>
                </div>
                <div class="dataAll">
                  <label class="textOf sizeBig">$CONFIRMED_COURSE</label>
                  <label class="attendSuc">出席</label>
                </div>
              </div>
            </main></body></html>
        """.trimIndent()
    }
}
