package org.cabetus.ui.browser

import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
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
    /** この実行中に SSO 画面を経由したか。ログイン完了の検出に使う。 */
    private var sawSsoThisRun = false
    /** ログイン完了を 1 実行につき 1 回だけ通知するためのフラグ。 */
    private var authUpdateNotified = false
    private var mobile = false
    private var mobileResult: MobileAttendanceRepository.CaptureResult? = null
    /** 現在の document でモバイル出席メニューを押した世代。同じ document で二度押さない。 */
    private var mobileEntryGeneration = -1L
    /** 同じ document で NO_COURSES（授業なし）を最初に観測した時刻。未観測なら null。 */
    private var mobileNoCoursesSince: Long? = null
    /** [mobileNoCoursesSince] を観測した document 世代。document が変われば計測をやり直す。 */
    private var mobileNoCoursesGeneration = -1L
    /** NO_COURSES が安定して「受付対象なし」と確定したか。確定時だけ既存スナップショットを消す。 */
    private var mobileNoCoursesConfirmed = false
    /** VERIFY が確定できない状態（capture 失敗・パース不能）が続き始めた時刻。未観測なら null。 */
    private var mobileNotReadySince: Long? = null
    /** [mobileNotReadySince] を観測した document 世代。 */
    private var mobileNotReadyGeneration = -1L
    /** 失敗が確定したときのユーザー向け理由。finally の message に使う。 */
    private var mobileFailureMessage: String? = null
    /** 直近の capture 失敗理由（抽出器の [ClassMobileAttendanceDomExtractor.ErrorCode.userMessage]）。 */
    private var mobileCaptureFailureMessage: String? = null
    private var schedule = false
    private var scheduleSnapshot: ClassScheduleNoticesDom.Snapshot? = null
    private var timetable = false
    /** 時間割モードの結果。null のまま終わった場合は失敗として扱う。 */
    private var timetableOutcome: ClassAttendanceBackgroundController.TimetableStatus? = null
    /** 同一 document で入口リンクを二度押さないための世代。 */
    private var timetableEntryGeneration = -1L
    private var timetableStage = "時間割の取得を開始できませんでした"
    private var diagnosticLogging = false
    /** onStartCommand ごとの世代。古い finally が新しい job の状態を壊さないために使う。 */
    private var runGenerationCounter = 0L
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
        if (intent?.action == CANCEL) {
            val running = job
            running?.cancel()
            if (running == null || running.isCompleted) {
                // 実行中の job が無い＝誰も最終状態を公開しない。予約が残っていれば解除する。
                releaseStaleReservations()
                job = null
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val action = intent?.action
        if (action != START && action != MOBILE && action != SCHEDULE && action != TIMETABLE) { stopSelf(); return START_NOT_STICKY }
        if (job?.isCompleted == false) {
            // 既に実行中のモードがある。二重に WebView を持たない。
            // ただし startForegroundService で起こされた以上 startForeground は必ず履行する。
            // 実行中の job がまだ running=true を公開している間だけ通知を維持し、
            // 既に最終状態を公開済みなら通知を再作成しない。
            if (stillRunningPublished()) runCatching { foreground() }
            releaseDuplicateReservation(requireNotNull(action))
            return START_NOT_STICKY
        }
        terminal = false
        failure = null
        auth = false
        awaitingAuth = false
        sawSsoThisRun = false
        authUpdateNotified = false
        loading = false
        saved = 0
        latest = null
        mobile = action == MOBILE
        schedule = action == SCHEDULE
        timetable = action == TIMETABLE
        mobileResult = null
        mobileFailureMessage = null
        mobileCaptureFailureMessage = null
        mobileEntryGeneration = -1L
        mobileNoCoursesSince = null
        mobileNoCoursesConfirmed = false
        mobileNoCoursesGeneration = -1L
        mobileNotReadySince = null
        mobileNotReadyGeneration = -1L
        scheduleSnapshot = null
        timetableOutcome = null
        timetableEntryGeneration = -1L
        timetableStage = "時間割の取得を開始できませんでした"
        val runGeneration = ++runGenerationCounter
        job = scope.launch(start = CoroutineStart.LAZY) {
            var reason: String? = null
            try {
                foreground()
                val deadline = if (schedule) 45_000L else if (timetable) 55_000L else if (mobile) 60_000L else 600_000L
                withTimeout(deadline) { executeTour() }
            } catch (_: TimeoutCancellationException) {
                auth = awaitingAuth
                reason = mobileFailureMessage.takeIf { mobile }
                    ?: if (timetable) timetableStage
                    else "自動接続が完了しませんでした。保存済み記録は保持しています。"
            }
              catch (_: CancellationException) { reason = mobileFailureMessage.takeIf { mobile } ?: failure ?: "更新を中止しました。保存済み記録は保持しています。" }
              catch (e: Exception) {
                  if (mobile && diagnosticLogging) android.util.Log.d("ClassMobileRead", "failed type=${e.javaClass.simpleName} stage=${if (view == null) "host" else "read"}")
                  reason = mobileFailureMessage.takeIf { mobile }
                      ?: failure
                      ?: if (timetable) timetableStage else "CLASSの画面を確認できませんでした。保存済み記録は保持しています。"
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
                        // running=false を公開した直後に Home が同じサービスへ開始要求しても、
                        // onStartCommand が「まだ終わっていない job」を見ないよう先に参照を外す。
                        if (runGeneration == runGenerationCounter) job = null
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
                        if (runGeneration == runGenerationCounter) job = null
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
                    val failed = reason != null || mobileResult?.saved == false
                    // 既存のスナップショットは、認証切れか「受付対象なし」が確定したときだけ捨てる。
                    // タイムアウト・中止・一時的な読み取り失敗では保持し、古い情報は
                    // MobileAttendanceEvidence の鮮度判定に任せる。
                    withContext(NonCancellable) {
                        if (auth || mobileNoCoursesConfirmed) mobileRepository.clear()
                    }
                    terminal = true
                    if (diagnosticLogging) android.util.Log.d(
                        "ClassMobileRead",
                        "complete success=${reason == null} auth=$auth failed=$failed noCourses=$mobileNoCoursesConfirmed",
                    )
                    release()
                    if (runGeneration == runGenerationCounter) job = null
                    // 「受付対象なし」が確定したときだけ、抽出側の短い説明をそのまま出す。
                    // 通常の成功時は件数の内訳（mobileAttendanceResultMessage）を優先する。
                    val settledMessage = mobileResult
                        ?.takeIf { mobileNoCoursesConfirmed && it.saved }
                        ?.message
                        ?.takeIf { it.isNotBlank() }
                    controller.publishMobile(ClassAttendanceBackgroundController.MobileStatus(
                        message = mobileFailureMessage
                            ?: settledMessage
                            ?: mobileAttendanceResultMessage(mobileResult, auth, failed),
                        authRequired = auth, failed = failed,
                        completedAt = System.currentTimeMillis()))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                val state = latest
                if (reason == null && (state == null || state.phase != ClassAttendanceTour.Phase.COMPLETE || state.items.any { it.status != ClassAttendanceTour.ItemStatus.DONE }))
                    reason = state?.stopReason ?: "一部の科目を取得できませんでした。"
                terminal = true
                if (runGeneration == runGenerationCounter) job = null
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

    /**
     * このサービスがまだ「実行中」を公開しているか。二重起動要求のときに
     * フォアグラウンド通知を維持すべきかの判定に使う。
     */
    private fun stillRunningPublished(): Boolean = when {
        mobile -> controller.mobileStatus.value.running
        schedule -> controller.scheduleStatus.value.running
        timetable -> controller.timetableStatus.value.running
        else -> controller.status.value.running
    }

    /**
     * 実行中の job が無いのに残っている予約（running=true）をすべて解除する。
     * サービスが最終状態を公開できずに終わった場合の取り残しを防ぐ。
     */
    private fun releaseStaleReservations() {
        val mobileNow = controller.mobileStatus.value
        if (mobileNow.running) controller.publishMobile(
            mobileNow.copy(
                running = false,
                failed = true,
                message = "取得できません",
                completedAt = System.currentTimeMillis(),
            ),
        )
        val scheduleNow = controller.scheduleStatus.value
        if (scheduleNow.running) controller.publishSchedule(scheduleNow.copy(running = false, failed = true))
        val timetableNow = controller.timetableStatus.value
        if (timetableNow.running) controller.publishTimetable(
            timetableNow.copy(
                running = false,
                failed = true,
                completedAt = System.currentTimeMillis(),
                message = "時間割の取り込みを中止しました。",
            ),
        )
        val tourNow = controller.status.value
        if (tourNow.running) controller.publish(
            tourNow.copy(running = false, message = "更新を中止しました。保存済み記録は保持しています。"),
        )
    }

    /**
     * 実行中のモードがある状態で `startForegroundService` が届いたときの後始末。
     *
     * 二重起動はしない。問題は呼び出し側（`ClassAttendanceBackgroundController`）が
     * 同期的に予約した `running = true` を誰も消さないと、Home の自動確認が永久に止まること。
     *
     * - 要求されたモードが実行中モードと同じなら、その job の `finally` が必ず最終状態を
     *   公開するので何もしない（進行中の結果を壊さない）。
     * - 異なるモードなら、そのモードの job は存在しないので予約をここで解除する。
     *
     * いずれの場合も `running = true` を取り残さない。
     */
    private fun releaseDuplicateReservation(action: String) {
        val activeMode = when {
            mobile -> MOBILE
            schedule -> SCHEDULE
            timetable -> TIMETABLE
            else -> START
        }
        if (action == activeMode) return
        when (action) {
            MOBILE -> {
                val current = controller.mobileStatus.value
                if (current.running) controller.publishMobile(
                    current.copy(
                        running = false,
                        failed = true,
                        message = "取得できません",
                        completedAt = System.currentTimeMillis(),
                    ),
                )
            }
            SCHEDULE -> {
                val current = controller.scheduleStatus.value
                if (current.running) controller.publishSchedule(current.copy(running = false, failed = true))
            }
            TIMETABLE -> {
                val current = controller.timetableStatus.value
                if (current.running) controller.publishTimetable(
                    current.copy(
                        running = false,
                        failed = true,
                        completedAt = System.currentTimeMillis(),
                        message = "時間割を取り込み中です。完了してから再試行してください。",
                    ),
                )
            }
            else -> {
                val current = controller.status.value
                if (current.running) controller.publish(
                    current.copy(
                        running = false,
                        message = "更新を開始できませんでした。完了してから再試行してください。",
                    ),
                )
            }
        }
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
        if (timetable) timetableStage = "読み取り用ブラウザを起動できませんでした"
        host = ClassAttendanceVirtualDisplayHost(this)
        val browser = host!!.create(); view = browser; alive = true
        // Responsive CLASS layouts require a normal phone-width CSS viewport.
        browser.settings.textZoom = 100
        if (timetable) {
            // Use the same viewport settings as the browser that displays the manual preview.
            browser.settings.useWideViewPort = true
            browser.settings.loadWithOverviewMode = true
            timetableStage = "CLASSの入口を開けませんでした"
        }
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
                if (awaitingAuth) sawSsoThisRun = true
                if (ClassAttendanceEntryPolicy.classify(url) == ClassAttendanceEntryPolicy.Page.BLOCKED) fail("許可されていない画面への移動を停止しました。")
            }
            override fun onPageFinished(v: WebView, url: String?) {
                if (timetable && (v !== view || v.url != url)) return
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
        // 読み取り開始時にキャッシュを消さない。失敗・中止時に有効なスナップショットを
        // 失わないため、破棄は「認証切れ」「受付対象なしの確定」だけに限定する（finally 参照）。
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
            if (sawSsoThisRun && !authUpdateNotified) {
                // SSO を経由して CLASS に到達した = 新しいログインが成立した可能性が高い。
                // 「要再ログイン」の抑止を解除し、次の自動確認を許可する。
                authUpdateNotified = true
                runCatching { controller.notifyAuthUpdated() }
            }
            if (schedule) {
                scheduleSnapshot = ClassScheduleNoticesDom.parse(evaluate(ClassScheduleNoticesDom.SCRIPT))
                if (scheduleSnapshot != null) return
                continue
            }
            if (mobile) {
                if (mobileEntryStep(url)) return
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
        val timetablePage = BrowserUrlPolicy.isClassTimetablePage(url)
        if (timetablePage) {
            val verificationError = ClassTimetableDomExtractor.parseVerificationResult(
                evaluate(ClassTimetableDomExtractor.VERIFY_SCRIPT),
            )
            if (!isCurrent()) return false
            timetableStage = verificationError?.userMessage ?: "時間割表の科目を読み取れませんでした"
            if (verificationError == null) {
                awaitingAuth = false
                return saveInitialTimetable(::isCurrent)
            }
        } else if (url != BrowserUrlPolicy.CLASS_URL) {
            timetableStage = "CLASSの学生時間割表への移動を確認できませんでした"
        }
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

    private suspend fun saveInitialTimetable(isCurrent: () -> Boolean): Boolean {
        val parsed = ClassTimetableDomExtractor.parseEvaluationResult(evaluate(ClassTimetableDomExtractor.SCRIPT))
        if (!isCurrent()) return false
        if (parsed.error != null || parsed.slots.isEmpty()) {
            timetableStage = parsed.error?.userMessage ?: "時間割表に取り込める科目が見つかりませんでした"
            return false
        }
        currentCoroutineContext().ensureActive()
        timetableStage = "読み取った時間割を端末に保存できませんでした"
        val result = timetableRepository.save(parsed.slots.map {
            ClassTimetableSlot(it.dayOfWeek, it.period, it.courseName, it.courseCode, it.room)
        })
        if (result.insertedCells == 0 && !result.blocked) {
            timetableStage = "取り込めるコマがありませんでした。CLASSで時間割表を確認してください。"
            error("No accepted timetable cells")
        }
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

    /**
     * モバイル出席確認モードの 1 ステップ。true を返したらこのモードは完了。
     *
     * - 検証済みのモバイル出席登録ページで読み取れたら完了。
     * - `NO_COURSES` が同一 document で [MOBILE_SETTLE_MS] 以上続いたら「受付対象なし」の確定。
     * - `VERIFY` は通るのに capture が失敗し続けたら、[MOBILE_SETTLE_MS] 経過時点で
     *   抽出器の理由（例: 対象日付を確認できない）を付けて失敗させる。60 秒のタイムアウトまで
     *   回し続けない。
     * - それ以外は入口メニューを 1 document につき 1 回だけ押して待つ（連打しない）。
     */
    private suspend fun mobileEntryStep(url: String): Boolean {
        val doc = generation
        val onMobilePage = BrowserUrlPolicy.isClassMobileAttendancePage(url)
        val verification = ClassMobileAttendanceDomExtractor.parseVerificationResult(
            evaluate(ClassMobileAttendanceDomExtractor.VERIFY_SCRIPT),
        )

        if (verification == null) {
            // 検証は通った = 検証済みのモバイル出席登録ページ。読み取る。
            mobileNoCoursesSince = null
            mobileNoCoursesGeneration = -1L
            if (captureMobile()) return true
            // JSF が日付・行を描画し終えるのを待つ。モバイル出席ページに既に居るので
            // 入口リンクは押し直さない（CLASS の同時操作検出を誘発する）。
            return settleOrFailOnMobilePage(doc, onMobilePage) {
                mobileCaptureFailureMessage
                    ?: "出席登録の内容を確認できませんでした。CLASS で出席状況を確認してください。"
            }
        }

        // パース不能（INVALID_RESULT / SCRIPT_FAILED）は待っても直らないことが多い。
        // 検証済みモバイル出席ページ上で [MOBILE_SETTLE_MS] 続いたら理由付きで確定する。
        if (verification == ClassMobileAttendanceDomExtractor.ErrorCode.INVALID_RESULT ||
            verification == ClassMobileAttendanceDomExtractor.ErrorCode.SCRIPT_FAILED
        ) {
            mobileNoCoursesSince = null
            mobileNoCoursesGeneration = -1L
            return settleOrFailOnMobilePage(doc, onMobilePage) { verification.userMessage }
        }

        // 有効な検証結果が返ったので、未確定タイマーは解除する。
        mobileNotReadySince = null
        mobileNotReadyGeneration = -1L

        // NO_COURSES を「受付対象なし」と確定してよいのは、検証済みのモバイル出席登録ページ
        // （Pky50102.xhtml）で安定して続いた場合だけ。別ページの NO_COURSES は入口リンクを押す。
        if (verification == ClassMobileAttendanceDomExtractor.ErrorCode.NO_COURSES && onMobilePage) {
            val since = settledSince(mobileNoCoursesSince, mobileNoCoursesGeneration, doc)
            mobileNoCoursesSince = since
            mobileNoCoursesGeneration = doc
            if (SystemClock.elapsedRealtime() - since >= MOBILE_SETTLE_MS) {
                mobileNoCoursesConfirmed = true
                mobileResult = MobileAttendanceRepository.CaptureResult(
                    saved = true,
                    confirmedCount = 0,
                    pendingCount = 0,
                    unboundCount = 0,
                    ambiguousCount = 0,
                    message = "出席登録の対象授業はありません",
                )
                if (diagnosticLogging) android.util.Log.d("ClassMobileRead", "settled no-courses after ${MOBILE_SETTLE_MS}ms")
                return true
            }
            return false
        }

        mobileNoCoursesSince = null
        mobileNoCoursesGeneration = -1L
        // 既にモバイル出席登録ページなら入口リンクは押さず、4秒続いたら理由付きで確定する。
        if (onMobilePage) {
            return settleOrFailOnMobilePage(doc, true) { verification.userMessage }
        }
        if (mobileEntryGeneration != doc) {
            val entry = evaluate(ClassMobileAttendanceEntryDom.SCRIPT)
            if (entry == "\"enter\"" || entry == "\"opened\"" || entry == "enter" || entry == "opened") {
                mobileEntryGeneration = doc
            }
        }
        return false
    }

    /**
     * モバイル出席ページで「まだ確定できない」状態が [MOBILE_SETTLE_MS] 続いたら、
     * [reason] を付けて失敗として確定させる。それまでは false（継続）を返す。
     *
     * 同じ document での経過時間だけを見るため、JSF の再描画（generation の変化）では
     * タイマーがリセットされる。60 秒の外側タイムアウトまで無言で回し続けない。
     */
    private fun settleOrFailOnMobilePage(
        doc: Long,
        onMobilePage: Boolean,
        reason: () -> String,
    ): Boolean {
        if (!onMobilePage) {
            // モバイル出席ページ以外（入口・メニュー）では確定させない。
            mobileNotReadySince = null
            mobileNotReadyGeneration = -1L
            return false
        }
        val since = settledSince(mobileNotReadySince, mobileNotReadyGeneration, doc)
        mobileNotReadySince = since
        mobileNotReadyGeneration = doc
        if (SystemClock.elapsedRealtime() - since < MOBILE_SETTLE_MS) return false
        val message = reason()
        mobileFailureMessage = message
        error(message)
    }

    /**
     * 同一 document での観測開始時刻を返す。document が変わっていれば現在時刻からやり直す。
     * 呼び出し側は返した時刻を [mobileNoCoursesSince] 等へ保存する。
     */
    private fun settledSince(since: Long?, sinceGeneration: Long, doc: Long): Long =
        if (sinceGeneration == doc && since != null) since else SystemClock.elapsedRealtime()

    private suspend fun captureMobile(): Boolean {
        val initial = networkMonitor.status.first()
        check(initial.isKnown && initial.isConnected)
        val doc = generation
        val parsed = ClassMobileAttendanceDomExtractor.parseEvaluationResult(evaluate(ClassMobileAttendanceDomExtractor.SCRIPT))
        if (diagnosticLogging) android.util.Log.d("ClassMobileRead", "parse error=${parsed.error?.name}")
        if (parsed.error != null) mobileCaptureFailureMessage = parsed.error.userMessage
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
            // ネットワークが変わっただけでは、既存のスナップショットを消さない。
            // 保存可否は MobileAttendanceEvidence の鮮度・回線一致判定に任せる。
            error("Network changed")
        }
        if (!result.saved) mobileRepository.clear()
        mobileResult = result
        if (result.saved) mobileFailureMessage = null
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
        if (!terminal) {
            // サービスが最終状態を公開できずに終了した。予約が残っていると
            // Home の自動確認・CLASS ブラウザが永久に開けなくなるため、必ず解除する。
            releaseStaleReservations()
        }
        super.onDestroy()
    }
    companion object {
        const val MOBILE = "org.cabetus.classTour.MOBILE"
        const val SCHEDULE = "org.cabetus.classTour.SCHEDULE"
        const val TIMETABLE = "org.cabetus.classTour.TIMETABLE"
        const val START = "org.cabetus.classTour.START"
        const val CANCEL = "org.cabetus.classTour.CANCEL"
        /**
         * JSF の描画・メニュー遷移が落ち着くまで待つ時間。
         * NO_COURSES・capture 失敗がこの時間だけ安定して続いたら結果を確定する。
         */
        const val MOBILE_SETTLE_MS = 4_000L
    }
}
