package org.cabetus.ui.browser

import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.webkit.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.cabetus.data.classweb.*
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.notification.AlarmScheduler
import org.cabetus.notification.ClassAttendanceSyncNotifications
import org.cabetus.widget.WidgetUpdater
import org.cabetus.work.ClassAttendanceSyncContract as C
import javax.inject.Inject
import kotlin.coroutines.resume

/** Owns one rendered browser on a private display; no physical browser window or overlay. */
@AndroidEntryPoint
class ClassAttendanceTourService : Service() {
    @Inject lateinit var controller: ClassAttendanceBackgroundController
    @Inject lateinit var previews: ClassAttendanceDetailImportRepository
    @Inject lateinit var repository: ClassAttendanceSyncRepository
    @Inject lateinit var notifications: ClassAttendanceSyncNotifications
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var host: ClassAttendanceVirtualDisplayHost? = null
    private var view: WebView? = null
    private var alive = false
    private var loading = false
    private var generation = 0L
    private var failure: String? = null
    private var saved = 0
    private var latest: ClassAttendanceTour.State? = null
    private var terminal = false
    private var auth = false
    private var awaitingAuth = false
    private var mobile = false
    private var mobileResult: MobileAttendanceRepository.CaptureResult? = null
    private var schedule = false
    private var scheduleSnapshot: ClassScheduleNoticesDom.Snapshot? = null
    private var timetable = false
    /** 時間割モードの結果。null のまま終わった場合は失敗として扱う。 */
    private var timetableOutcome: ClassAttendanceBackgroundController.TimetableStatus? = null
    /** 同一 document で入口リンクを二度押さないための世代。 */
    private var timetableEntryGeneration = -1L
    private var diagnosticLogging = false
    @Inject lateinit var mobileRepository: MobileAttendanceRepository
    @Inject lateinit var networkMonitor: org.cabetus.data.network.NetworkMonitor
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var timetableRepository: ClassTimetableImportRepository
    @Inject lateinit var timetableDao: TimetableDao
    @Inject lateinit var alarmScheduler: AlarmScheduler
    @Inject lateinit var widgetUpdater: WidgetUpdater

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            settings.settings.collect { diagnosticLogging = it.diagnosticLogging }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { job?.cancel(); if (job == null) stopSelf(); return START_NOT_STICKY }
        if (intent?.action != START && intent?.action != MOBILE && intent?.action != SCHEDULE && intent?.action != TIMETABLE) { stopSelf(); return START_NOT_STICKY }
        if (job?.isCompleted == false) return START_NOT_STICKY
        terminal = false
        failure = null
        auth = false
        awaitingAuth = false
        loading = false
        saved = 0
        latest = null
        mobile = intent?.action == MOBILE
        schedule = intent?.action == SCHEDULE
        timetable = intent?.action == TIMETABLE
        mobileResult = null
        scheduleSnapshot = null
        timetableOutcome = null
        timetableEntryGeneration = -1L
        job = scope.launch(start = CoroutineStart.LAZY) {
            var reason: String? = null
            try {
                foreground()
                val deadline = if (schedule) 45_000L else if (timetable) 55_000L else if (mobile) 60_000L else 600_000L
                withTimeout(deadline) { executeTour() }
            } catch (_: TimeoutCancellationException) {
                auth = awaitingAuth
                reason = "自動接続が完了しませんでした。保存済み記録は保持しています。"
            }
              catch (_: CancellationException) { reason = failure ?: "更新を中止しました。保存済み記録は保持しています。" }
              catch (e: Exception) {
                  if (mobile && diagnosticLogging) android.util.Log.d("ClassMobileRead", "failed type=${e.javaClass.simpleName} stage=${if (view == null) "host" else "read"}")
                  reason = failure ?: "CLASSの画面を確認できませんでした。保存済み記録は保持しています。"
              }
            finally {
                if (timetable) {
                    // 出欠の保存・通知は行わない。取り込めた時だけ結果を成功として公開する。
                    val prior = controller.timetableStatus.value
                    val outcome = timetableOutcome
                    try {
                        release()
                    } finally {
                        terminal = true
                        try {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } finally {
                            stopSelf()
                            controller.publishTimetable(
                                outcome ?: prior.copy(
                                    running = false,
                                    failed = true,
                                    authRequired = auth,
                                    completedAt = System.currentTimeMillis(),
                                    message = reason
                                        ?: "時間割を取り込めませんでした。既存の時間割は変更していません。",
                                ))
                        }
                    }
                } else if (schedule) {
                    // 出欠の保存・結果通知は行わない。成功時だけ新しい一覧で差し替える。
                    val prior = controller.scheduleStatus.value
                    val parsed = scheduleSnapshot
                    try {
                        release()
                    } finally {
                        terminal = true
                        try {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } finally {
                            stopSelf()
                            controller.publishSchedule(
                                if (reason == null && parsed != null) {
                                    ClassAttendanceBackgroundController.ScheduleStatus(
                                        snapshot = parsed, refreshedAt = System.currentTimeMillis())
                                } else {
                                    prior.copy(running = false, failed = true, authRequired = auth)
                                })
                        }
                    }
                } else if (mobile) {
                    withContext(NonCancellable) {
                        if (reason != null || mobileResult?.saved != true) mobileRepository.clear()
                    }
                    terminal = true
                    if (diagnosticLogging) android.util.Log.d("ClassMobileRead", "complete success=${reason == null} auth=$auth")
                    release()
                    controller.publishMobile(ClassAttendanceBackgroundController.MobileStatus(
                        message = mobileAttendanceResultMessage(mobileResult, auth, reason != null),
                        authRequired = auth, completedAt = System.currentTimeMillis()))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                val state = latest
                if (reason == null && (state == null || state.phase != ClassAttendanceTour.Phase.COMPLETE || state.items.any { it.status != ClassAttendanceTour.ItemStatus.DONE }))
                    reason = state?.stopReason ?: "一部の科目を取得できませんでした。"
                terminal = true
                val total = state?.items?.size ?: 0
                release()
                controller.publish(ClassAttendanceBackgroundController.Status(message = reason ?: "$saved/$total 科目の出欠を更新しました。", saved = saved, total = total, authRequired = auth, result = state))
                try {
                    notifications.finish(saved, total, if (auth) C.Reason.AUTH.key else if (reason == null) null else C.Reason.VALIDATION.key)
                } finally {
                    release()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                }
            }
        }
        job?.start()
        return START_NOT_STICKY
    }
    private fun foreground() {
        val cancel = PendingIntent.getService(this, 74005, Intent(this, javaClass).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Factory creates channels; clone adds the actual service cancel action (not WorkManager).
        val builder = android.app.Notification.Builder.recoverBuilder(this, notifications.progress(saved, latest?.items?.size ?: 0))
        if (mobile) builder.setContentTitle("出席状況を確認中").setContentText("CLASSの受付表示を読み取っています")
        if (schedule) builder.setContentTitle("変更通知を確認中").setContentText("CLASSの変更通知一覧を確認しています")
        if (timetable) builder.setContentTitle("時間割を取り込み中").setContentText("CLASSの学生時間割表を確認しています")
        val n = builder.addAction(0, "中止", cancel).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(C.PROGRESS_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(C.PROGRESS_NOTIFICATION_ID, n)
    }
    private fun fail(message: String, authentication: Boolean = false) {
        failure = message; auth = authentication; alive = false; job?.cancel()
    }
    private suspend fun executeTour() {
        if (timetable) {
            // 既存の時間割があるなら WebView を開かずに終える（UI 側の絞り込みと同じ条件）。
            val existing = withContext(Dispatchers.IO) { timetableDao.getAll() }
            if (existing.isNotEmpty()) {
                timetableOutcome = ClassAttendanceBackgroundController.TimetableStatus(
                    running = false,
                    failed = false,
                    completedAt = System.currentTimeMillis(),
                    message = "既存の時間割があるため取り込みませんでした。" +
                        "CLASS の時間割画面から年度・学期を確認できないため、" +
                        "既存の時間割は変更していません。",
                )
                return
            }
        }
        host = ClassAttendanceVirtualDisplayHost(this)
        val browser = host!!.create(); view = browser; alive = true
        // Responsive CLASS layouts require a normal phone-width CSS viewport.
        browser.settings.textZoom = 100
        browser.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onShowFileChooser(v: WebView?, callback: ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean { callback?.onReceiveValue(null); return true }
        }
        browser.setDownloadListener { _, _, _, _, _ -> fail("資料のダウンロード画面では更新できません。") }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (ClassAttendanceEntryPolicy.classify(request.url.toString()) == ClassAttendanceEntryPolicy.Page.BLOCKED) { fail("許可されていない画面への移動を停止しました。"); return true }
                return false
            }
            override fun onPageStarted(v: WebView, url: String?, icon: android.graphics.Bitmap?) {
                generation++; loading = true
                awaitingAuth = ClassAttendanceEntryPolicy.classify(url) == ClassAttendanceEntryPolicy.Page.SSO
                if (ClassAttendanceEntryPolicy.classify(url) == ClassAttendanceEntryPolicy.Page.BLOCKED) fail("許可されていない画面への移動を停止しました。")
            }
            override fun onPageFinished(v: WebView, url: String?) {
                loading = false
                if (ClassAttendanceEntryPolicy.classify(v.url) == ClassAttendanceEntryPolicy.Page.CLASS) ClassMenuPanelFix.applyIfNeeded(v, url)
                SecureWebViewConfig.flushCookies()
            }
            override fun onPageCommitVisible(v: WebView, url: String?) {
                if (ClassAttendanceEntryPolicy.classify(v.url) == ClassAttendanceEntryPolicy.Page.CLASS) ClassMenuPanelFix.applyIfNeeded(v, url)
            }
            override fun onReceivedSslError(v: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); fail("安全な接続を確認できません。") }
            override fun onReceivedHttpAuthRequest(v: WebView, handler: HttpAuthHandler, host: String?, realm: String?) { handler.cancel(); fail("CLASSへのログインが必要です。", true) }
            override fun onReceivedClientCertRequest(v: WebView, request: ClientCertRequest) { request.cancel(); fail("証明書の確認が必要です。") }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) fail("CLASSとの通信に失敗しました。") }
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean { fail("ブラウザを維持できないため更新を停止しました。"); return true }
        }
        if (mobile) mobileRepository.clear()
        browser.loadUrl(BrowserUrlPolicy.CLASS_URL)
        var ready = false
        val entryDeadline = android.os.SystemClock.elapsedRealtime() + 60_000
        while (!ready) {
            check(android.os.SystemClock.elapsedRealtime() < entryDeadline) { "CLASS entry timeout" }
            currentCoroutineContext().ensureActive()
            delay(500)
            if (loading) continue
            val url = browser.url.orEmpty()
            when (ClassAttendanceEntryPolicy.classify(url)) {
                ClassAttendanceEntryPolicy.Page.SSO -> {
                    // Shared cookies may finish SSO without any user input. Never inject here.
                    awaitingAuth = true
                    continue
                }
                ClassAttendanceEntryPolicy.Page.BLOCKED -> error("Unapproved CLASS origin")
                ClassAttendanceEntryPolicy.Page.CLASS -> Unit
            }
            val invalid = evaluate("(function(){var m=document.querySelector('.mainContent');return !!m && m.textContent.indexOf('別の画面で操作されたため')>=0;})()")
            if (invalid == "true") { fail("CLASSが別画面との同時利用を検出しました。他のCLASS画面を閉じ、ログインを確認して再試行してください。"); currentCoroutineContext().ensureActive() }
            if (timetable) {
                if (captureInitialTimetable(browser, url)) return
                continue
            }
            val login = evaluate("(function(){return !!document.querySelector('form#loginForm') && !!document.querySelector('a[href*=ShibbolethAuthServlet]');})()")
            if (login == "true") { awaitingAuth = true; continue }
            awaitingAuth = false
            if (schedule) {
                scheduleSnapshot = ClassScheduleNoticesDom.parse(evaluate(ClassScheduleNoticesDom.SCRIPT))
                if (scheduleSnapshot != null) return
                continue
            }
            if (mobile) {
                val verification = ClassMobileAttendanceDomExtractor.parseVerificationResult(
                    evaluate(ClassMobileAttendanceDomExtractor.VERIFY_SCRIPT),
                )
                if (verification == null) {
                    if (captureMobile()) return
                    continue
                }
                // 一覧がまだ描画中でもメニューを再操作しない。NO_COURSES は「受付なし」の証拠ではない。
                if (verification == ClassMobileAttendanceDomExtractor.ErrorCode.NO_COURSES) continue
                evaluate(ClassMobileAttendanceEntryDom.SCRIPT)
                continue
            }
            ready = ClassAttendanceListDomExtractor.parseActionResult(evaluate(ClassAttendanceListDomExtractor.LIST_VERIFY_SCRIPT)) == null
            if (!ready) evaluate(ClassAttendanceEntryDom.SCRIPT)
        }
        val runner = ClassAttendanceTourRunner(
            enqueue = { script, callback ->
                val doc = generation
                browser.evaluateJavascript(script) { raw -> callback(if (alive && view === browser && doc == generation) raw else null) }
            }, currentUrl = { browser.url }, isPageLoading = { loading }, isAlive = { alive && view === browser },
            previewLoader = { previews.preview(it) },
            syncPreviewLoader = { row, detail, context ->
                repository.save(row, detail, context) { !alive || job?.isActive != true }.also { saved++ }
            }, stepTimeoutMs = 30_000, totalTimeoutMs = 550_000,
        )
        when (val outcome = runner.run(onState = { state ->
            latest = state
            controller.publish(ClassAttendanceBackgroundController.Status(running = true, message = "更新中・${state.doneCount + state.failedCount}/${state.items.size} 科目", saved = saved, total = state.items.size))
            foreground()
        }, shouldCancel = { !alive || job?.isActive != true })) {
            is ClassAttendanceTourRunner.Outcome.Started -> latest = outcome.state
            is ClassAttendanceTourRunner.Outcome.NotStarted -> { failure = outcome.message; error("CLASS tour could not start") }
        }
    }
    private suspend fun captureInitialTimetable(browser: WebView, url: String): Boolean {
        val doc = generation
        fun isCurrent() = alive && !loading && view === browser && generation == doc && browser.url == url
        if (!isCurrent()) return false
        if (BrowserUrlPolicy.isClassTimetablePage(url) &&
            ClassTimetableDomExtractor.parseVerificationResult(evaluate(ClassTimetableDomExtractor.VERIFY_SCRIPT)) == null
        ) {
            if (!isCurrent()) return false
            awaitingAuth = false
            val parsed = ClassTimetableDomExtractor.parseEvaluationResult(evaluate(ClassTimetableDomExtractor.SCRIPT))
            if (!isCurrent() || parsed.error != null || parsed.slots.isEmpty()) return false
            currentCoroutineContext().ensureActive()
            val result = timetableRepository.save(parsed.slots.map {
                ClassTimetableSlot(it.dayOfWeek, it.period, it.courseName, it.courseCode, it.room)
            })
            if (result.insertedCells == 0 && !result.blocked) return false
            timetableOutcome = ClassAttendanceBackgroundController.TimetableStatus(
                insertedCells = result.insertedCells,
                createdCourses = result.createdCourses,
                completedAt = System.currentTimeMillis(),
                message = if (result.blocked) "既存の時間割は変更していません。"
                    else "時間割を${result.insertedCells}コマ取り込みました。",
            )
            if (result.insertedCells > 0) {
                alarmScheduler.rescheduleClassAlarms()
                widgetUpdater.updateAll()
            }
            return true
        }
        if (!isCurrent()) return false
        if (timetableEntryGeneration != doc && evaluate(ClassTimetableEntryDom.SCRIPT) == "true") {
            timetableEntryGeneration = doc
            return false
        }
        if (isCurrent()) {
            val login = evaluate("(function(){return !!document.querySelector('form#loginForm') && !!document.querySelector('a[href*=ShibbolethAuthServlet]');})()")
            if (isCurrent()) awaitingAuth = login == "true"
        }
        return false
    }

    private suspend fun captureMobile(): Boolean {
        val initial = networkMonitor.status.first()
        check(initial.isKnown && initial.isConnected)
        mobileRepository.clear()
        val doc = generation
        val parsed = ClassMobileAttendanceDomExtractor.parseEvaluationResult(evaluate(ClassMobileAttendanceDomExtractor.SCRIPT))
        if (diagnosticLogging) android.util.Log.d("ClassMobileRead", "parse error=${parsed.error?.name}")
        // JSF can expose the title/list before the date and rows have settled.
        // Wait within the outer 60s deadline; never substitute the device date.
        val observation = parsed.observation ?: return false
        check(parsed.error == null && !loading && alive)
        check(!loading && generation == doc)
        val result = mobileRepository.capture(observation, initial) {
            alive && !loading && generation == doc
        }
        val after = networkMonitor.status.first()
        if (after != initial || !alive || loading || generation != doc) {
            mobileRepository.clear()
            error("Network changed")
        }
        if (!result.saved) mobileRepository.clear()
        mobileResult = result
        if (diagnosticLogging) android.util.Log.d("ClassMobileRead", "capture saved=${result.saved} confirmed=${result.confirmedCount} pending=${result.pendingCount}")
        return true
    }
    private suspend fun evaluate(script: String): String? = suspendCancellableCoroutine { c ->
        val browser = view
        if (browser == null || !alive || ClassAttendanceEntryPolicy.classify(browser.url) != ClassAttendanceEntryPolicy.Page.CLASS) { c.resume(null); return@suspendCancellableCoroutine }
        val doc = generation
        browser.evaluateJavascript(script) { raw -> if (c.isActive) c.resume(if (alive && doc == generation) raw else null) }
    }
    private fun release() { alive = false; view = null; host?.close(); host = null; SecureWebViewConfig.flushCookies() }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) fail("メモリ不足のため更新を中止しました。")
    }
    override fun onDestroy() {
        scope.cancel(); release()
        if (!terminal && mobile) controller.publishMobile(ClassAttendanceBackgroundController.MobileStatus(message = "取得できません"))
        if (!terminal && schedule) controller.publishSchedule(
            controller.scheduleStatus.value.copy(running = false, failed = true, authRequired = auth))
        if (!terminal && timetable) controller.publishTimetable(
            controller.timetableStatus.value.copy(running = false, failed = true, authRequired = auth))
        if (!terminal && !mobile && !schedule && !timetable) controller.publish(ClassAttendanceBackgroundController.Status(message = "更新が停止しました。保存済み記録は保持しています。", saved = saved))
        super.onDestroy()
    }
    companion object {
        const val MOBILE = "org.cabetus.classTour.MOBILE"
        const val SCHEDULE = "org.cabetus.classTour.SCHEDULE"
        const val TIMETABLE = "org.cabetus.classTour.TIMETABLE"
        const val START = "org.cabetus.classTour.START"
        const val CANCEL = "org.cabetus.classTour.CANCEL"
    }
}
