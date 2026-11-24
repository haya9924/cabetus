package org.cabetus.ui.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.webkit.ClientCertRequest
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.cabetus.data.classweb.ClassAttendanceDetailDomExtractor
import org.cabetus.data.classweb.ClassAttendanceListDomExtractor
import org.cabetus.data.classweb.ClassAttendanceTour
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor
import org.cabetus.data.classweb.ClassTimetableDomExtractor

/** 表示中の DOM を再検証する間隔。URL が対象でも DOM が違う画面ではボタンを出さない。 */
private const val TIMETABLE_VERIFICATION_INTERVAL_MS = 300L

/** 出欠詳細は URL パスを実機確認していないため、DOM（見出し・表）の再検証を少し長めに行う。 */
private const val ATTENDANCE_VERIFICATION_INTERVAL_MS = 500L

/** 出欠一覧（ul[id$=_list]）の表示判定も URL パスを実機確認していないため DOM で行う。 */
private const val ATTENDANCE_LIST_VERIFICATION_INTERVAL_MS = 500L

/** モバイル出席登録の表示判定。読み取り専用のため、他より短い間隔で見出しを確認する。 */
private const val MOBILE_ATTENDANCE_VERIFICATION_INTERVAL_MS = 500L

/**
 * 安全なアプリ内ブラウザ。
 * - CookieManager を共有し、SSO のセッションを保持する（削除はしない）。
 * - 大学 / Microsoft SSO の許可ホスト以外は WebView 内に読み込まず、外部ブラウザへ案内する。
 * - SSL エラー・HTTP 認証・クライアント証明書・位置情報・カメラ等は拒否する。
 * - 戻る / 進む / 再読み込み / 外部ブラウザ / 現在のホスト表示を提供する。
 *
 * @param onLetusVisited LETUS（完全一致ホスト）のページを実際に読み込み終えたときに呼ばれる。
 *   呼び出し側が「LETUS を開いた」ことを記録し、終了時の安全な同期に使う。
 *   この画面はコールバックを呼ぶだけで、保存・同期は一切行わない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserScreen(
    initialUrl: String?,
    onClose: () -> Unit,
    onLetusVisited: () -> Unit = {},
    attendanceSyncRequested: Boolean = false,
    inPictureInPicture: Boolean = false,
    pictureInPictureAvailable: Boolean = false,
    onMinimize: () -> Unit = {},
) {
    if (!BrowserUrlPolicy.isAllowedStart(initialUrl)) {
        InvalidUrlScreen(onClose = onClose)
        return
    }

    val context = LocalContext.current
    val importViewModel: ClassTimetableImportViewModel = hiltViewModel()
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val attendanceViewModel: ClassAttendanceDetailImportViewModel = hiltViewModel()
    val attendanceState by attendanceViewModel.state.collectAsStateWithLifecycle()
    val tourViewModel: ClassAttendanceTourViewModel = hiltViewModel()
    val tourState by tourViewModel.state.collectAsStateWithLifecycle()
    val tourResult by tourViewModel.result.collectAsStateWithLifecycle()
    val mappingChoices by tourViewModel.mappingChoices.collectAsStateWithLifecycle()
    val mappingMessage by tourViewModel.mappingMessage.collectAsStateWithLifecycle()
    // CLASS モバイル出席登録の読み取り（明示ボタンのみ）。登録・送信は行わない。
    val mobileAttendanceViewModel: MobileAttendanceCaptureViewModel = hiltViewModel()
    val mobileAttendanceState by mobileAttendanceViewModel.state.collectAsStateWithLifecycle()
    // 出欠一覧の自動巡回は未完成・実機未検証のため、既定では完全に無効にする。
    // 無効時は開始ボタン・進捗・中止・結果ダイアログを一切出さず、
    // ClassAttendanceTourRunner も生成しない（tourViewModel.start を呼ばない）。
    val tourEnabled = ClassAttendanceTourFeature.available() || attendanceSyncRequested
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) tourViewModel.requestCancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var syncStarted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var syncEntryAttempted by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf(initialUrl.orEmpty()) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var blockedUrl by remember { mutableStateOf<String?>(null) }
    var fatalError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isTimetableVerified by remember { mutableStateOf(false) }
    var isImportVerifying by remember { mutableStateOf(false) }
    var isAttendanceVerified by remember { mutableStateOf(false) }
    var isAttendanceVerifying by remember { mutableStateOf(false) }
    var isAttendanceListVerified by remember { mutableStateOf(false) }
    var isMobileAttendanceVerified by remember { mutableStateOf(false) }
    var isPageLoading by remember { mutableStateOf(true) }
    // 表示中の document（読み込み・遷移）ごとに増える世代番号。
    // onPageStarted でだけ増やし、evaluateJavascript の結果が古い document のものなら捨てる。
    var documentGeneration by remember { mutableIntStateOf(0) }
    // onPageFinished で確定した「読み込みが終わった document」の世代。
    var pageLoadGeneration by remember { mutableIntStateOf(0) }
    // 時間割の自動取り込みを開始した document の世代。同じ document では 1 回だけ実行する。
    var autoImportGeneration by remember { mutableIntStateOf(-1) }

    // LETUS の課題提出など `<input type="file">` の選択を SAF に委譲する。
    // - 端末のファイル一覧・ストレージ権限は要求せず、SAF でユーザーが明示選択した
    //   content:// URI だけを WebView に返す（LetusFileChooser で再検証）。
    // - カメラ権限は要求しない（onPermissionRequest は従来どおり deny）。
    // - callback は「選択完了・キャンセル・画面離脱・WebView 破棄・新規要求」のいずれでも
    //   必ず 1 回解放する。未解放だと同じ input のファイル選択が二度と開かなくなる。
    val downloadHandler = rememberLetusDownloadHandler()
    val fileChooserSlot = remember { LetusFileChooserSlot() }
    var fileChooserError by remember { mutableStateOf<String?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        // キャンセル（空）でも必ず callback を解放する（slot 側で null を返す）。
        fileChooserSlot.complete(uris.map { it.toString() })
    }
    // 画面破棄時（戻る・ホーム・構成変更）に未完了の要求を必ず解放する。
    DisposableEffect(fileChooserSlot) {
        onDispose { fileChooserSlot.release() }
    }

    val host = remember(currentUrl) { BrowserUrlPolicy.hostOf(currentUrl) }
    val canOpenExternal = BrowserUrlPolicy.isSafeExternalUrl(currentUrl)

    // LETUS の完全一致 HTTPS ホストのページに限ってファイル選択を受け付ける。
    // 起動を許可する SAF は MIME で絞ったうえで、選択結果をさらに同じ MIME で検証する。
    val requestLetusFileChooser:
        (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean = request@{ callback, params ->
            val pageUrl = webViewRef?.url
            if (!BrowserUrlPolicy.isLetusPage(pageUrl)) {
                // LETUS 以外では要求を受理せず、WebView に「未対応」を返させる。
                return@request false
            }
            val mode = params.mode
            val multiple = mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            if (mode != WebChromeClient.FileChooserParams.MODE_OPEN && !multiple) {
                // 保存（MODE_SAVE）・ディレクトリ選択等はダウンロード側の担当なので扱わない。
                return@request false
            }
            val acceptTypes = LetusFileChooser.acceptTypes(params)
            val request = LetusFileChooserRequest(
                callback = callback,
                acceptTypes = acceptTypes,
                multiple = multiple,
                resolveMimeType = { uri -> resolveMimeTypeOrNull(context, Uri.parse(uri)) },
            )
            // 前の要求が残っていれば解放してから置き換える。
            fileChooserSlot.start(request)
            return@request try {
                fileChooserLauncher.launch(acceptTypes)
                true
            } catch (e: ActivityNotFoundException) {
                // 受け先が無い場合も callback を宙に浮かせない。
                fileChooserSlot.release()
                fileChooserError = "ファイルを選択できるアプリが見つかりませんでした。"
                true
            }
        }
    // 巡回中は一覧 ⇄ 詳細の自動遷移の最中でもあるため、ユーザー操作をすべて止める。
    // 機能フラグが無効な間は、ViewModel の状態に関係なく入力遮断・戻る遮断・閉じる無効化も行わない。
    val tourRunning = tourEnabled && tourState.running
    val webViewInputBlocked = BrowserInputPolicy.blocksWebViewInput(tourRunning)
    val backNavigationBlocked = BrowserInputPolicy.blocksBackNavigation(tourRunning)
    val closeEnabled = BrowserInputPolicy.closeEnabled(tourRunning)
    val isTimetableUrl = remember(currentUrl) {
        BrowserUrlPolicy.isClassTimetablePage(currentUrl)
    }
    // CLASS の完全一致 https ホストのうち、時間割表以外を出欠詳細の候補とする。
    // パスは実機で確認できていないため許可リストにせず、DOM の見出し・表で検証する。
    val isAttendanceCandidate = remember(currentUrl, isTimetableUrl) {
        !isTimetableUrl && BrowserUrlPolicy.isClassConsole(currentUrl)
    }
    // CLASS のモバイル出席登録のページ（実機で確認できた完全一致パス）だけを候補にする。
    // パスだけを根拠にせず、読み取りボタンを出す前に DOM（見出し・#…dataList・.dataAll）も検証する。
    val isMobileAttendanceCandidate = remember(currentUrl) {
        BrowserUrlPolicy.isClassMobileAttendancePage(currentUrl)
    }

    // 巡回中は端末の戻る操作を「消費するだけ」にして、WebView の履歴移動も画面を閉じることも
    // 行わない。履歴が無いときの戻る操作は画面を閉じてしまうため、enabled を false にせず
    // イベントを消費させる点が重要。
    BackHandler(enabled = backNavigationBlocked || canGoBack) {
        if (backNavigationBlocked) {
            return@BackHandler
        }
        webViewRef?.goBack()
    }

    // Compose 側のオーバーレイだけでは WebView（実 View）への入力配信を確実に止められないため、
    // WebView 自身のタッチリスナとフォーカスを直接無効化してキーボード入力も止める。
    // 巡回が終了（完了・停止・中止）したら必ず元に戻す。
    LaunchedEffect(webViewRef, webViewInputBlocked) {
        val view = webViewRef ?: return@LaunchedEffect
        if (webViewInputBlocked) {
            // enabled=false でタッチ・キーの配信を止め、リスナでも念のため消費する。
            view.isEnabled = false
            view.setOnTouchListener { _, _ -> true }
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            view.clearFocus()
            hideSoftKeyboard(context, view)
        } else {
            view.isEnabled = true
            view.setOnTouchListener(null)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
        }
    }

    // evaluateJavascript の結果が「もう存在しない document」のものかどうか。
    // document 世代・WebView の同一性・破棄だけを見る（URL は別途確認する）。
    fun isStaleDocument(view: WebView, generation: Int): Boolean =
        generation != documentGeneration || webViewRef !== view

    // 時間割の読み取りを採用してよい document か（古い document ではない・同じ URL・対象パス）。
    fun isCurrentTimetableDocument(view: WebView, expectedUrl: String?, generation: Int): Boolean =
        !isStaleDocument(view, generation) &&
            view.url == expectedUrl &&
            BrowserUrlPolicy.isClassTimetablePage(view.url)

    // 自動起動を妨げるダイアログ（取り込みプレビュー・エラー・結果等）が表示中か。
    fun isBlockingDialogVisible(): Boolean =
        importState.preview != null || importState.error != null || importState.message != null ||
            attendanceState.preview != null || attendanceState.error != null ||
            mobileAttendanceState.message != null || mobileAttendanceState.error != null ||
            blockedUrl != null || fatalError != null || fileChooserError != null ||
            tourState.running || (tourEnabled && tourState.resultVisible)

    /**
     * 時間割の DOM 読み取り（VERIFY → SCRIPT）。手動ボタンと自動起動で同じ手順を使う。
     *
     * [expectedUrl] / [generation] は開始時点の snapshot。各コールバックで
     * document 世代・WebView の同一性・URL・破棄を確認し、古い結果は捨てる。
     * 抽出結果は ViewModel に渡すだけで、保存はユーザーの確認（プレビュー）後にしか行わない。
     */
    fun startTimetableImport(view: WebView, expectedUrl: String?, generation: Int) {
        isImportVerifying = true
        // 開始時点でも URL だけでなく DOM（表・ヘッダー）を再検証する。
        view.evaluateJavascript(ClassTimetableDomExtractor.VERIFY_SCRIPT) { raw ->
            // 古い document の検証結果は捨てる（読み取りは開始していないので状態は戻さない）。
            if (isStaleDocument(view, generation)) return@evaluateJavascript
            isImportVerifying = false
            val verificationError = ClassTimetableDomExtractor.parseVerificationResult(raw)
            val samePage = isCurrentTimetableDocument(view, expectedUrl, generation)
            if (verificationError != null || !samePage) {
                isTimetableVerified = false
                importViewModel.onLocalError(
                    (verificationError ?: ClassTimetableDomExtractor.ErrorCode.UNEXPECTED_PAGE)
                        .userMessage,
                )
            } else {
                isTimetableVerified = true
                importViewModel.onExtractionStarted()
                view.evaluateJavascript(ClassTimetableDomExtractor.SCRIPT) { result ->
                    if (isStaleDocument(view, generation)) {
                        // 遷移開始時に中止済み。新しい要求の状態を古いcallbackで上書きしない。
                        return@evaluateJavascript
                    } else if (!isCurrentTimetableDocument(view, expectedUrl, generation)) {
                        isTimetableVerified = false
                        importViewModel.onLocalError(
                            ClassTimetableDomExtractor.ErrorCode.UNEXPECTED_PAGE.userMessage,
                        )
                    } else {
                        importViewModel.onEvaluationResult(result)
                    }
                }
            }
        }
    }

    // URL が対象でも、実際に表示中の .rishuArea の表と月〜土ヘッダーを確認できるまで
    // ボタンは出さない。DOM の再描画・SPA 的な画面更新・非表示化に追従して即座に隠す。
    // 検証済みの document に対しては、ユーザー操作なしで 1 回だけ取り込み
    // （プレビュー作成）を自動起動する。保存は従来どおりプレビューでの確認が必須。
    LaunchedEffect(webViewRef, isTimetableUrl, importState, attendanceState, mobileAttendanceState, tourState, blockedUrl, fatalError, fileChooserError, isPageLoading) {
        val view = webViewRef
        if (!isTimetableUrl || view == null) {
            isTimetableVerified = false
            return@LaunchedEffect
        }

        isTimetableVerified = false
        while (isActive) {
            val expectedUrl = view.url
            val generation = documentGeneration
            if (isPageLoading || !BrowserUrlPolicy.isClassTimetablePage(expectedUrl)) {
                // 読み込み中は前の document が残っていることがあるため検証しない。
                isTimetableVerified = false
            } else {
                view.evaluateJavascript(ClassTimetableDomExtractor.VERIFY_SCRIPT) { raw ->
                    // 古い document の検証結果は捨てる（表示状態も変更しない）。
                    if (isStaleDocument(view, generation)) return@evaluateJavascript
                    val verified = BrowserUrlPolicy.isClassTimetablePage(view.url) &&
                        view.url == expectedUrl &&
                        ClassTimetableDomExtractor.parseVerificationResult(raw) == null
                    isTimetableVerified = verified
                    // 自動起動の条件:
                    //  - この document の読み込みが完了している（成功 false→true の再検証では再実行しない）
                    //  - この document では未実行（[autoImportGeneration]）
                    //  - 読み取り/検証中ではなく、ダイアログ表示中・巡回中でもない
                    if (verified && !attendanceSyncRequested &&
                        generation == pageLoadGeneration &&
                        autoImportGeneration != generation &&
                        !isImportVerifying &&
                        !importState.busy &&
                        !isBlockingDialogVisible()
                    ) {
                        autoImportGeneration = generation
                        startTimetableImport(view, expectedUrl, generation)
                    }
                }
            }
            delay(TIMETABLE_VERIFICATION_INTERVAL_MS)
        }
    }

    // 手動での再取得は従来どおり維持する（自動起動の実行有無に関係なく押せる）。
    val requestTimetableImport: () -> Unit = {
        val view = webViewRef
        val expectedUrl = view?.url
        val generation = documentGeneration
        if (view == null || !isTimetableUrl ||
            !BrowserUrlPolicy.isClassTimetablePage(expectedUrl)
        ) {
            isTimetableVerified = false
            importViewModel.onLocalError(
                "CLASS の学生時間割表ページが前面に表示されていることを確認できません。" +
                    "時間割表を開き直してから再試行してください。",
            )
        } else if (!isImportVerifying && !importState.busy) {
            startTimetableImport(view, expectedUrl, generation)
        }
    }

    // URL が CLASS の完全一致ホストでも、見出し・出席詳細テーブル・ヘッダーを
    // DOM で確認できるまでボタンは出さない。画面更新・非表示化に追従して即座に隠す。
    // 第一段階では表示中の1科目を読み取ってプレビューするだけで、保存は行わない。
    // 一覧巡回の実行中は詳細DOMと一覧DOMが交互に切り替わるため、単発読み取りの
    // 検証は行わずボタンも隠す（同時に別の読み取りを走らせない）。
    LaunchedEffect(webViewRef, isAttendanceCandidate, tourState.running) {
        val view = webViewRef
        if (!isAttendanceCandidate || view == null || tourState.running) {
            isAttendanceVerified = false
            return@LaunchedEffect
        }

        isAttendanceVerified = false
        while (isActive) {
            val expectedUrl = view.url
            if (isPageLoading ||
                !BrowserUrlPolicy.isClassConsole(expectedUrl) ||
                BrowserUrlPolicy.isClassTimetablePage(expectedUrl)
            ) {
                // 読み込み中は前の document が残ることがあるため検証しない。
                isAttendanceVerified = false
            } else {
                view.evaluateJavascript(ClassAttendanceDetailDomExtractor.VERIFY_SCRIPT) { raw ->
                    val current = view.url
                    val samePage = current == expectedUrl &&
                        BrowserUrlPolicy.isClassConsole(current) &&
                        !BrowserUrlPolicy.isClassTimetablePage(current)
                    isAttendanceVerified = samePage &&
                        ClassAttendanceDetailDomExtractor.parseVerificationResult(raw) == null
                }
            }
            delay(ATTENDANCE_VERIFICATION_INTERVAL_MS)
        }
    }

    val requestAttendanceImport: () -> Unit = {        val view = webViewRef
        val expectedUrl = view?.url
        if (view == null || !isAttendanceCandidate ||
            !BrowserUrlPolicy.isClassConsole(expectedUrl) ||
            BrowserUrlPolicy.isClassTimetablePage(expectedUrl)
        ) {
            isAttendanceVerified = false
            attendanceViewModel.onLocalError(
                "CLASS の出欠詳細が前面に表示されていることを確認できません。" +
                    "出欠詳細を開き直してから再試行してください。",
            )
        } else if (!isAttendanceVerifying) {
            isAttendanceVerifying = true
            // クリック時点でも URL・見出し・表を再検証する。
            view.evaluateJavascript(ClassAttendanceDetailDomExtractor.VERIFY_SCRIPT) { raw ->
                isAttendanceVerifying = false
                val verificationError =
                    ClassAttendanceDetailDomExtractor.parseVerificationResult(raw)
                val current = view.url
                // 出欠詳細は URL パスを実機確認していないため、一覧巡回と同じ
                // 「CLASS コンソールかつ時間割表ではない」判定で確認する。
                val samePage = current == expectedUrl &&
                    BrowserUrlPolicy.isClassConsole(current) &&
                    !BrowserUrlPolicy.isClassTimetablePage(current)
                if (verificationError != null || !samePage) {
                    isAttendanceVerified = false
                    attendanceViewModel.onLocalError(
                        (verificationError ?: ClassAttendanceDetailDomExtractor.ErrorCode.UNEXPECTED_PAGE)
                            .userMessage,
                    )
                } else {
                    isAttendanceVerified = true
                    attendanceViewModel.onExtractionStarted()
                    view.evaluateJavascript(ClassAttendanceDetailDomExtractor.SCRIPT) { result ->
                        val after = view.url
                        if (after != expectedUrl ||
                            !BrowserUrlPolicy.isClassConsole(after) ||
                            BrowserUrlPolicy.isClassTimetablePage(after)
                        ) {
                            isAttendanceVerified = false
                            attendanceViewModel.onLocalError(
                                ClassAttendanceDetailDomExtractor.ErrorCode.UNEXPECTED_PAGE.userMessage,
                            )
                        } else {
                            attendanceViewModel.onEvaluationResult(result)
                        }
                    }
                }
            }
        }
    }

    // モバイル出席登録（モバイル出席サイト）の画面も、URL ではなく DOM（見出し・
    // #...dataList・.dataAll）で確認できるまで読み取りボタンを出さない。
    // 読み取りは「出席」表示の最小限の抽出だけで、登録・送信・コード入力は行わない。
    // 一覧巡回の実行中は同時に読み取らないようボタンを隠す。
    LaunchedEffect(webViewRef, isMobileAttendanceCandidate, tourState.running) {
        val view = webViewRef
        if (!isMobileAttendanceCandidate || view == null || tourState.running) {
            isMobileAttendanceVerified = false
            return@LaunchedEffect
        }

        isMobileAttendanceVerified = false
        while (isActive) {
            val expectedUrl = view.url
            val expectedGeneration = documentGeneration
            if (isPageLoading ||
                !BrowserUrlPolicy.isClassMobileAttendancePage(expectedUrl)
            ) {
                isMobileAttendanceVerified = false
            } else {
                view.evaluateJavascript(ClassMobileAttendanceDomExtractor.VERIFY_SCRIPT) { raw ->
                    val current = view.url
                    val samePage = current == expectedUrl &&
                        expectedGeneration == documentGeneration && webViewRef === view && !isPageLoading &&
                        BrowserUrlPolicy.isClassMobileAttendancePage(current)
                    if (samePage) {
                        val result = ClassMobileAttendanceDomExtractor.parseVerificationResult(raw)
                        if (result == ClassMobileAttendanceDomExtractor.ErrorCode.NO_COURSES &&
                            isMobileAttendanceVerified
                        ) {
                            mobileAttendanceViewModel.clearLiveCapture()
                        }
                        isMobileAttendanceVerified = result == null
                    }
                }
            }
            delay(MOBILE_ATTENDANCE_VERIFICATION_INTERVAL_MS)
        }
    }

    val captureMobileAttendance: (Boolean) -> Unit = requestMobile@{ silent ->
        val view = webViewRef
        val expectedUrl = view?.url
        if (view == null || !isMobileAttendanceCandidate ||
            !BrowserUrlPolicy.isClassMobileAttendancePage(expectedUrl)
        ) {
            isMobileAttendanceVerified = false
            mobileAttendanceViewModel.onLocalError(
                "CLASS のモバイル出席登録が前面に表示されていることを確認できません。" +
                    "画面を開き直してから再試行してください。",
            )
        } else if (!isPageLoading && !tourState.running) {
            val token = mobileAttendanceViewModel.onExtractionStarted(silent) ?: return@requestMobile
            val expectedGeneration = documentGeneration
            // クリック時点でも DOM（見出し・授業一覧）を再検証する。
            view.evaluateJavascript(ClassMobileAttendanceDomExtractor.VERIFY_SCRIPT) { raw ->
                val verificationError =
                    ClassMobileAttendanceDomExtractor.parseVerificationResult(raw)
                val current = view.url
                val samePage = current == expectedUrl &&
                    expectedGeneration == documentGeneration && webViewRef === view &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    !isPageLoading && !tourState.running &&
                    BrowserUrlPolicy.isClassMobileAttendancePage(current)
                if (verificationError != null || !samePage) {
                    isMobileAttendanceVerified = false
                    mobileAttendanceViewModel.onLocalError(
                        (verificationError ?: ClassMobileAttendanceDomExtractor.ErrorCode.UNEXPECTED_PAGE)
                            .userMessage,
                        token,
                    )
                } else {
                    view.evaluateJavascript(ClassMobileAttendanceDomExtractor.SCRIPT) { result ->
                        val after = view.url
                        if (after != expectedUrl || expectedGeneration != documentGeneration || webViewRef !== view ||
                            isPageLoading || tourState.running ||
                            !lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
                            !BrowserUrlPolicy.isClassMobileAttendancePage(after)
                        ) {
                            isMobileAttendanceVerified = false
                            mobileAttendanceViewModel.onLocalError(
                                ClassMobileAttendanceDomExtractor.ErrorCode.UNEXPECTED_PAGE.userMessage,
                                token,
                            )
                        } else {
                            mobileAttendanceViewModel.onEvaluationResult(result, token)
                        }
                    }
                }
            }
        }
    }

    // 登録操作は利用者が行う。同一 document 内の出席表示の更新も定期的に読み取り、
    // ホームに戻ったとき手動の「読み取り」なしで反映する。非表示中は読み取らない。
    LaunchedEffect(webViewRef, documentGeneration, isMobileAttendanceVerified, tourState.running) {
        while (isActive && isMobileAttendanceVerified && !tourState.running) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                isMobileAttendanceVerified && !isPageLoading &&
                !mobileAttendanceViewModel.state.value.busy
            ) {
                captureMobileAttendance(true)
            }
            delay(3_000L)
        }
    }

    // JavaScript callback が破棄された場合にも busy を残さない。
    LaunchedEffect(mobileAttendanceState.busy) {
        if (mobileAttendanceState.busy) {
            delay(10_000L)
            mobileAttendanceViewModel.invalidateCapture()
        }
    }

    // 出欠一覧（ul[id$=_list]）も URL パスを実機確認していないため、DOM で確認できるまで
    // 巡回ボタンは出さない。一覧の行構造・onclick が想定と違えばボタンを隠す。
    // 機能フラグが無効な間は一覧の再検証自体を行わない（巡回用スクリプトを実行しない）。
    LaunchedEffect(webViewRef, isAttendanceCandidate, tourState.running, tourEnabled) {
        val view = webViewRef
        if (!tourEnabled || (!isAttendanceCandidate && !attendanceSyncRequested) || view == null || tourState.running) {
            isAttendanceListVerified = false
            return@LaunchedEffect
        }

        isAttendanceListVerified = false
        while (isActive) {
            val expectedUrl = view.url
            if (isPageLoading ||
                !BrowserUrlPolicy.isClassConsole(expectedUrl) ||
                (!attendanceSyncRequested && BrowserUrlPolicy.isClassTimetablePage(expectedUrl))
            ) {
                isAttendanceListVerified = false
            } else {
                view.evaluateJavascript(ClassAttendanceListDomExtractor.LIST_VERIFY_SCRIPT) { raw ->
                    val current = view.url
                    val samePage = current == expectedUrl &&
                        BrowserUrlPolicy.isClassConsole(current) &&
                        !BrowserUrlPolicy.isClassTimetablePage(current)
                    isAttendanceListVerified = (samePage || (attendanceSyncRequested && current == expectedUrl && BrowserUrlPolicy.isClassConsole(current))) &&
                        ClassAttendanceListDomExtractor.parseActionResult(raw) == null
                }
            }
            delay(ATTENDANCE_LIST_VERIFICATION_INTERVAL_MS)
        }
    }

    /**
     * 出欠一覧の前景巡回を開始する。開始はユーザーがボタンを押したときだけ。
     * WebView の操作は UI スレッドで行う。抽出結果の変換は ViewModel が行い、保存はしない。
     */
    val requestAttendanceTour: () -> Unit = requestTour@{
        // 無効時はボタン自体を表示しない。万一呼ばれてもランナーを生成しない。
        if (!tourEnabled) return@requestTour
        val view = webViewRef
        val expectedUrl = view?.url
        // 一覧の検証に成功した時点の URL と、クリック時点の URL が一致すること。
        // 単発の出欠読み取り・時間割取り込みの最中は巡回を開始しない。
        if (view == null || tourState.running ||
            !isAttendanceListVerified ||
            attendanceState.busy || importState.busy || isAttendanceVerifying ||
            isImportVerifying ||
            !BrowserUrlPolicy.isClassConsole(expectedUrl) ||
            (!attendanceSyncRequested && BrowserUrlPolicy.isClassTimetablePage(expectedUrl))
        ) {
            isAttendanceListVerified = false
            tourViewModel.onNotStarted(
                "CLASS の出欠一覧を確認できません。一覧を表示し直してから再試行してください。",
            )
        } else {
            // 開始後は一覧の再検証を止める（巡回中に別の読み取りを走らせない）。
            isAttendanceListVerified = false
            val runner = ClassAttendanceTourRunner(
                enqueue = { script, callback ->
                    view.evaluateJavascript(script) { raw -> callback(raw) }
                },
                currentUrl = { view.url },
                isPageLoading = { isPageLoading },
                isAlive = { webViewRef === view },
                previewLoader = { detail -> tourViewModel.preview(detail) },
                stepTimeoutMs = if (attendanceSyncRequested) 30_000L else 10_000L,
                totalTimeoutMs = if (attendanceSyncRequested) 600_000L else 180_000L,
                syncPreviewLoader = if (attendanceSyncRequested) ({ row, detail, academic ->
                    tourViewModel.syncPreview(row, detail, academic)
                }) else null,
            )
            tourViewModel.start(runner, retryTransient = attendanceSyncRequested)
        }
    }

    LaunchedEffect(webViewRef, attendanceSyncRequested, syncStarted) {
        if (!attendanceSyncRequested || syncStarted) return@LaunchedEffect
        val view = webViewRef ?: return@LaunchedEffect
        while (isActive && !syncStarted) {
            if (!isPageLoading && !syncEntryAttempted && BrowserUrlPolicy.isClassConsole(view.url)) {
                view.evaluateJavascript(org.cabetus.data.classweb.ClassAttendanceEntryDom.SCRIPT) { raw ->
                    if (raw == "\"opened\"") syncEntryAttempted = true
                }
            }
            if (isAttendanceListVerified && !tourState.running) {
                syncStarted = true
                requestAttendanceTour()
            }
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            if (!inPictureInPicture) TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        enabled = closeEnabled,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "閉じる")
                    }
                },
                title = {
                    Column {
                        Text(
                            title.ifBlank { "アプリ内ブラウザ" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (host != null) {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = "安全な接続",
                                    modifier = Modifier.padding(end = 2.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    host,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (pictureInPictureAvailable && tourState.running) {
                        TextButton(onClick = {
                            android.widget.Toast.makeText(context, "小窓を残して他のアプリを操作できます。操作・中止は小窓を拡大してください。画面消灯中の継続は保証されません。", android.widget.Toast.LENGTH_LONG).show()
                            onMinimize()
                        }) { Text("小窓") }
                    }
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack && !webViewInputBlocked,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward && !webViewInputBlocked,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "進む")
                    }
                    IconButton(
                        onClick = { webViewRef?.reload() },
                        enabled = currentUrl.isNotBlank() && !webViewInputBlocked,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再読み込み")
                    }
                    IconButton(
                        onClick = { BrowserIntents.openExternal(context, currentUrl) },
                        enabled = canOpenExternal && !webViewInputBlocked,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "外部ブラウザで開く")
                    }
                },
            )
        },
        bottomBar = {
            if (!inPictureInPicture) when {
                // 巡回中は他の読み取りボタンを出さず、中止だけを提供する。
                // 機能フラグが無効な間は、この進捗・中止 UI 自体を表示しない。
                tourEnabled && tourState.running -> {
                    Surface(tonalElevation = 3.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                if (attendanceSyncRequested) "全科目の出欠を取得・保存しています" else "出欠一覧を巡回しています（プレビュー専用）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (tourState.total > 0) {
                                    "${tourState.processed} / ${tourState.total} 件を処理しました"
                                } else {
                                    "一覧を確認しています…"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (tourState.total > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        tourState.processed.toFloat() / tourState.total.toFloat()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Button(
                                onClick = { tourViewModel.requestCancel() },
                                // 巡回中でも中止だけは常に受け付ける（中止処理中だけ無効）。
                                enabled = BrowserInputPolicy.cancelEnabled(
                                    tourRunning = tourRunning,
                                    cancelling = tourState.cancelling,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (tourState.cancelling) "中止しています…" else "巡回を中止")
                            }
                        }
                    }
                }

                isTimetableUrl && isTimetableVerified -> {
                    Surface(tonalElevation = 3.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "CLASS の学生時間割表を確認しました",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "科目コード・教室は CLASS の科目詳細から読み取ります。年度・学期は取得できません。" +
                                    "読み取れない値は推測しません。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = requestTimetableImport,
                                enabled = !importState.busy && !isImportVerifying,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when {
                                        importState.busy -> "読み取り中…"
                                        isImportVerifying -> "ページを確認中…"
                                        else -> "この時間割を取り込む"
                                    },
                                )
                            }
                        }
                    }
                }

                isAttendanceCandidate && isAttendanceVerified -> {
                    Surface(tonalElevation = 3.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "CLASS の出欠詳細を確認しました",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "表示中の1科目の出欠を読み取り、内容を確認するだけのプレビューです。" +
                                    "データベースへの保存、定期同期は行いません。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = requestAttendanceImport,
                                enabled = !attendanceState.busy && !isAttendanceVerifying,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when {
                                        attendanceState.busy -> "読み取り中…"
                                        isAttendanceVerifying -> "ページを確認中…"
                                        else -> "この科目の出欠を読み取る"
                                    },
                                )
                            }
                        }
                    }
                }

                // モバイル出席登録の画面。表示中の「出席」だけを時間割と紐付けてホーム表示に使う。
                // 出欠の登録・送信・コード入力は行わない（読み取り専用）。
                isMobileAttendanceCandidate && isMobileAttendanceVerified -> {
                    Surface(tonalElevation = 3.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "CLASS のモバイル出席登録を確認しました",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "CLASSの「出席」を読み取り、科目・日付・時限を特定できた記録を出席履歴に自動反映します。" +
                                    "受付中は保存しません。登録・送信・コード入力は行いません。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { captureMobileAttendance(false) },
                                enabled = !mobileAttendanceState.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (mobileAttendanceState.busy) "読み取り中…"
                                    else "出席を読み取って履歴に反映",
                                )
                            }
                        }
                    }
                }

                // 一覧を確認できたら、ユーザーが明示的に押したときだけ前景で巡回する。
                // 機能フラグが無効な間は、開始ボタンを表示しない。
                tourEnabled && isAttendanceCandidate && isAttendanceListVerified -> {
                    Surface(tonalElevation = 3.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "CLASS の出欠一覧を確認しました",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "表示中の一覧の科目を1件ずつ順に開いて読み取り、結果をまとめて確認します。" +
                                    "保存・自動統合は行いません。" + ClassAttendanceListDomExtractor.PAGINATION_NOTICE,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = requestAttendanceTour,
                                enabled = !tourState.running,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("表示中の一覧を巡回して読み取る") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createWebView(
                        context = ctx,
                        initialUrl = initialUrl.orEmpty(),
                        onTitle = { title = it },
                        onProgress = { progress = it },
                        onUrlChanged = { currentUrl = it },
                        onNavigationChanged = { view ->
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        },
                        onBlocked = { url ->
                            blockedUrl = url
                            progress = 0
                        },
                        onPageStarted = {
                            documentGeneration++
                            if (mobileAttendanceViewModel.state.value.busy) {
                                mobileAttendanceViewModel.invalidateCapture()
                            }
                            isImportVerifying = false
                            if (importState.busy) importViewModel.onLocalError("ページが切り替わったため読み取りを中止しました。")
                            isPageLoading = true
                            // 遷移したら前の document のファイル選択は無効。callback を解放する。
                            fileChooserSlot.release()
                            isTimetableVerified = false
                            isAttendanceVerified = false
                            isAttendanceListVerified = false
                            isMobileAttendanceVerified = false
                        },
                        onPageFinished = {
                            isPageLoading = false
                            pageLoadGeneration = documentGeneration
                            if (BrowserUrlPolicy.isLetusPage(webViewRef?.url)) onLetusVisited()
                        },
                        onHistoryChanged = {
                            isTimetableVerified = false
                            isAttendanceVerified = false
                            isAttendanceListVerified = false
                            isMobileAttendanceVerified = false
                        },
                        onFatalError = { message -> fatalError = message },
                        onShowFileChooser = requestLetusFileChooser,
                        onDownload = { url, agent, disposition ->
                            if (BrowserUrlPolicy.isLetusPage(webViewRef?.url)) {
                                downloadHandler(url, agent, disposition)
                            }
                        },
                    ).also { webViewRef = it }
                },
                onRelease = { view ->
                    // 画面破棄・WebView 破棄時は進行中の巡回を停止させる。
                    tourViewModel.onWebViewDetached()
                    mobileAttendanceViewModel.invalidateCapture()
                    // 未完了のファイル選択 callback も必ず解放する。
                    fileChooserSlot.release()
                    webViewRef = null
                    view.stopLoading()
                    view.destroy()
                },
            )

            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 巡回中は WebView を直接操作できないことを画面上でも明示する。
            // 入力の遮断自体は WebView 側（isEnabled / touch listener / focus）で行う。
            // ここでは操作の案内だけを出し、中止は下部のボタンから行う。
            if (webViewInputBlocked) {
                Text(
                    "巡回中はページを操作できません。中止する場合は下部の「巡回を中止」を押してください。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    blockedUrl?.let { url ->
        val canExternal = BrowserUrlPolicy.isSafeExternalUrl(url)
        AlertDialog(
            onDismissRequest = { blockedUrl = null },
            title = { Text("アプリ内では開けません") },
            text = {
                Text(
                    "認証 Cookie を保護するため、大学・SSO 以外のページはアプリ内で読み込みません。" +
                        "\n\n${BrowserUrlPolicy.hostOf(url) ?: url}",
                )
            },
            confirmButton = {
                if (canExternal) {
                    TextButton(
                        onClick = {
                            BrowserIntents.openExternal(context, url)
                            blockedUrl = null
                        },
                    ) { Text("外部ブラウザで開く") }
                } else {
                    TextButton(onClick = { blockedUrl = null }) { Text("閉じる") }
                }
            },
            dismissButton = if (canExternal) {
                { TextButton(onClick = { blockedUrl = null }) { Text("キャンセル") } }
            } else {
                null
            },
        )
    }

    fileChooserError?.let { message ->
        AlertDialog(
            onDismissRequest = { fileChooserError = null },
            title = { Text("ファイルを選択できません") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { fileChooserError = null }) { Text("閉じる") }
            },
        )
    }

    fatalError?.let { message ->
        AlertDialog(
            onDismissRequest = { fatalError = null },
            title = { Text("ページを読み込めません") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { fatalError = null }) { Text("閉じる") }
            },
            dismissButton = if (canOpenExternal) {
                {
                    TextButton(onClick = { BrowserIntents.openExternal(context, currentUrl) }) {
                        Text("外部ブラウザで開く")
                    }
                }
            } else {
                null
            },
        )
    }

    importState.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                if (!importState.busy) importViewModel.cancelPreview()
            },
            title = { Text("時間割の取り込みプレビュー") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "取り込み対象: ${preview.slots.size}コマ / ${preview.courseCount}科目",
                        fontWeight = FontWeight.Bold,
                    )
                    Text("既存の科目を再利用: ${preview.existingCourseCount}コマ")
                    Text("新規に追加するコマ: ${preview.newCellCount}コマ")
                    Text("新規に作成する科目: ${preview.newCourseCount}科目")
                    Text("正式な科目コードを確認: ${preview.officialCodeCellCount}コマ")
                    if (preview.unknownCodeCellCount > 0) {
                        Text("科目コード不明（仮コードで保存）: ${preview.unknownCodeCellCount}コマ")
                    }
                    if (preview.codeConflictCellCount > 0) {
                        Text("科目コードの重複・不一致: ${preview.codeConflictCellCount}コマ")
                    }
                    if (preview.skippedCodeConflictCount > 0) {
                        Text("既存科目とコード競合のためスキップ: ${preview.skippedCodeConflictCount}コマ")
                    }
                    if (preview.unknownRoomCellCount > 0) {
                        Text("教室を確認できないコマ: ${preview.unknownRoomCellCount}コマ")
                    }
                    Text(
                        "年度設定: " +
                            if (preview.academicYear > 0) "${preview.academicYear}年度" else "未設定",
                    )
                    preview.blockedReason?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "既存の時間割は削除しません。同じコマに別科目がある場合は、どちらが正しいか" +
                            "推測せず取り込みません。既存の時間割がある場合は年度・学期の混在を防ぐため" +
                            "取り込みを中止します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "科目コードは CLASS の詳細で、科目名・教員・教室・コード・単位の並びを" +
                            "確認できた場合だけ使用します。不明な場合は仮コード（CLASSWEB-…）を使います。" +
                            "同じ科目名で異なる正式コードが確認できた場合や、一部でコードを確認できない" +
                            "場合は、仮コードで統合せず対象を保留します。教室が確認できない場合は空欄にし、" +
                            "既存の科目・時間割は上書きしません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (preview.warnings.isNotEmpty()) {
                        HorizontalDivider()
                        Text("確認事項", fontWeight = FontWeight.Bold)
                        preview.warnings.forEach { warning ->
                            Text(
                                "・$warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { importViewModel.confirmSave() },
                    enabled = !importState.busy && !preview.blocked,
                ) {
                    Text(
                        when {
                            preview.blocked -> "取り込み中止"
                            importState.busy -> "保存中…"
                            else -> "保存する"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { importViewModel.cancelPreview() },
                    enabled = !importState.busy,
                ) { Text("キャンセル") }
            },
        )
    }

    importState.error?.let { message ->
        AlertDialog(
            onDismissRequest = { importViewModel.dismissDialog() },
            title = { Text("時間割を取り込めません") },
            text = {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { importViewModel.dismissDialog() }) { Text("閉じる") }
            },
        )
    }

    importState.message?.let { message ->
        AlertDialog(
            onDismissRequest = { importViewModel.dismissDialog() },
            title = { Text("時間割を保存しました") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importViewModel.dismissDialog() }) { Text("閉じる") }
            },
        )
    }
    attendanceState.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                if (!attendanceState.busy) attendanceViewModel.cancelPreview()
            },
            title = {
                Column {
                    Text("出欠の読み取りプレビュー")
                    attendanceState.capturedAt?.let { time ->
                        Text("画面から取得：" + java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm:ss")
                            .withZone(java.time.ZoneId.of("Asia/Tokyo"))
                            .format(java.time.Instant.ofEpochMilli(time)),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        preview.heading ?: "（見出しを確認できません）",
                        fontWeight = FontWeight.Bold,
                    )
                    Text("出席回: ${preview.rows.size}回")

                    if (preview.errors.isNotEmpty()) {
                        HorizontalDivider()
                        Text("読み取り結果の注意", fontWeight = FontWeight.Bold)
                        preview.errors.forEach { error ->
                            Text(
                                "・$error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    if (preview.rows.isNotEmpty()) {
                        HorizontalDivider()
                        Text("出席回の内容", fontWeight = FontWeight.Bold)
                        preview.rows.forEach { row ->
                            Text(
                                "第${row.sessionNo ?: "?"}回  ${row.dateText}  " +
                                    "${row.dayPeriodText}  ${row.mark.ifBlank { "未記録" }}" +
                                    "（${row.status.label}）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (preview.warnings.isNotEmpty()) {
                        HorizontalDivider()
                        Text("確認事項", fontWeight = FontWeight.Bold)
                        preview.warnings.forEach { warning ->
                            Text(
                                "・$warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider()
                    Text(
                        "この画面は表示中の1科目を読み取った内容の確認用です。" +
                            "データベースへの保存、全科目の自動巡回、定期同期は未対応です。" +
                            "科目コードや年度は推測しません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { attendanceViewModel.cancelPreview() },
                    enabled = !attendanceState.busy,
                ) { Text("閉じる") }
            },
        )
    }

    attendanceState.error?.let { message ->
        AlertDialog(
            onDismissRequest = { attendanceViewModel.dismissDialog() },
            title = { Text("出欠を読み取れません") },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { attendanceViewModel.dismissDialog() }) { Text("閉じる") }
            },
        )
    }

    mobileAttendanceState.message?.let { message ->
        AlertDialog(
            onDismissRequest = { mobileAttendanceViewModel.dismissDialog() },
            title = { Text("出席表示を読み取りました") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(
                        "ホームの出席欄には、CLASS の出席登録画面から読み取った科目名と出席状況を表示します。" +
                            "読み取りから15分経過するか、日付やネットワークが変わると失効します。" +
                            "学内/学外は CLASS に「学内ネットワークからのアクセス」が表示されていた場合だけ" +
                            "「学内」と表示します。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { mobileAttendanceViewModel.dismissDialog() }) { Text("閉じる") }
            },
        )
    }

    mobileAttendanceState.error?.let { message ->
        AlertDialog(
            onDismissRequest = { mobileAttendanceViewModel.dismissDialog() },
            title = { Text("出席表示を読み取れません") },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { mobileAttendanceViewModel.dismissDialog() }) { Text("閉じる") }
            },
        )
    }

    // 前景巡回の結果（プレビュー専用）。全件を列挙し、失敗・未処理・停止理由を明示する。
    // 機能フラグが無効な間は、結果ダイアログも表示しない。
    if (tourEnabled && tourState.resultVisible) {
        val items = tourResult?.items.orEmpty()
        AlertDialog(
            onDismissRequest = {
                if (!tourState.running) tourViewModel.dismissResult()
            },
            title = { Text(if (attendanceSyncRequested) "出欠の更新結果" else "出欠一覧の読み取り結果（プレビュー）") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "対象: ${tourState.total}件 / 読み取り成功: ${tourState.doneCount}件",
                        fontWeight = FontWeight.Bold,
                    )
                    if (attendanceSyncRequested) {
                        Text("保存済み：${tourState.doneCount}科目。対象は選択中の年度・学期です。", style = MaterialTheme.typography.bodySmall)
                        if (mappingChoices.isNotEmpty()) {
                            Text("時間割への対応を確認（初回のみ）", fontWeight = FontWeight.Bold)
                            Text("時間割が仮コードのため、表示する科目の対応を確認してください。確認前も取得記録は保存済みです。", style = MaterialTheme.typography.bodySmall)
                            mappingChoices.forEach { choice ->
                                TextButton(onClick = { tourViewModel.confirmMapping(choice) }) { Text(choice.label + "：対応を保存") }
                            }
                        }
                        mappingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (tourState.failedCount > 0) {
                        Text(
                            "失敗: ${tourState.failedCount}件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (tourState.skippedCount > 0) {
                        Text(
                            "未処理: ${tourState.skippedCount}件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    tourState.stopReason?.let { reason ->
                        Text(
                            (if (tourState.cancelled) "中止理由: " else "停止理由: ") + reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        if (tourState.isPartial) {
                            "一部の科目だけを読み取った部分結果です。読み取れなかった科目は未処理のままにしています。"
                        } else if (tourState.doneCount == tourState.total && tourState.total > 0) {
                            "表示中の一覧の全件を読み取りました。"
                        } else {
                            "読み取れた科目がありません。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )

                    HorizontalDivider()
                    if (items.isEmpty()) {
                        Text(
                            "科目ごとの結果はありません。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items.forEach { item ->
                        val status = when (item.status) {
                            ClassAttendanceTour.ItemStatus.DONE -> "読み取り済み"
                            ClassAttendanceTour.ItemStatus.FAILED -> "失敗"
                            ClassAttendanceTour.ItemStatus.SKIPPED -> "未処理"
                            ClassAttendanceTour.ItemStatus.READING -> "処理中"
                            ClassAttendanceTour.ItemStatus.PENDING -> "未処理"
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${item.row.index}. ${item.row.label}（$status）",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            item.preview?.let { preview ->
                                Text(
                                    preview.heading ?: "（見出しを確認できません）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "出席回: ${preview.rows.size}回",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                preview.rows.forEach { row ->
                                    Text(
                                        "第${row.sessionNo ?: "?"}回  ${row.dateText}  " +
                                            "${row.dayPeriodText}  ${row.mark.ifBlank { "未記録" }}" +
                                            "（${row.status.label}）",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                preview.errors.forEach { error ->
                                    Text(
                                        "・$error",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            item.error?.let { error ->
                                Text(
                                    "・$error",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    Text(
                        if (attendanceSyncRequested) "読み取り完了の科目はCLASS取得記録として保存しました。失敗・未処理の科目は前回の記録を保持します。既存時間割は変更しません。対象は選択中の年度・学期です。"
                        else "この画面はプレビュー専用です。" + ClassAttendanceListDomExtractor.PAGINATION_NOTICE,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { tourViewModel.dismissResult() },
                    enabled = !tourState.running,
                ) { Text("閉じる") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvalidUrlScreen(onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("アプリ内ブラウザ") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "閉じる")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("この URL はアプリ内ブラウザでは開けません。")
        }
    }
}

/**
 * SAF が返した content URI の MIME を ContentResolver で引く。
 *
 * WebView に content:// を読み込ませるわけではない（`allowContentAccess` は false のまま）
 * ため、ここでの参照だけでプロバイダに読み取り権限は要求されない。
 * 判別できない場合は null を返し、呼び出し側で「SAF 側の MIME 制限を通ったもの」として扱う。
 */
private fun resolveMimeTypeOrNull(context: Context, uri: Uri): String? =
    runCatching { context.contentResolver.getType(uri) }.getOrNull()

/** ソフトキーボードを閉じる。巡回で WebView の入力を遮断するときに使う。 */private fun hideSoftKeyboard(context: Context, view: WebView) {
    runCatching {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as?
            InputMethodManager ?: return
        manager.hideSoftInputFromWindow(view.windowToken, 0)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    initialUrl: String,
    onTitle: (String) -> Unit,
    onProgress: (Int) -> Unit,
    onUrlChanged: (String) -> Unit,
    onNavigationChanged: (WebView) -> Unit,
    onBlocked: (String) -> Unit,
    onPageStarted: () -> Unit,
    onPageFinished: () -> Unit,
    onHistoryChanged: () -> Unit,
    onFatalError: (String) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onDownload: (String, String, String?) -> Unit,
): WebView = WebView(context).apply {
    SecureWebViewConfig.configure(this)
    setDownloadListener { url, agent, disposition, _, _ ->
        onDownload(url.orEmpty(), agent.orEmpty(), disposition)
    }
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            onTitle(title.orEmpty())
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) {
            callback?.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }

        /**
         * `<input type="file">` のファイル選択。
         *
         * カメラ権限は要求しない。受理するかどうか（LETUS の完全一致 HTTPS ホストの
         * ページか、単一/複数選択か）は呼び出し側で判定し、false を返した場合は
         * WebView が未対応として扱う（callback はここでは保持しない）。
         */
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            if (filePathCallback == null || fileChooserParams == null) return false
            return onShowFileChooser(filePathCallback, fileChooserParams)
        }
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            return if (BrowserUrlPolicy.isAllowedInBrowser(url)) {
                false
            } else {
                onBlocked(url)
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onPageStarted()
            if (url != null) {
                if (!BrowserUrlPolicy.isAllowedInBrowser(url)) {
                    view.stopLoading()
                    onBlocked(url)
                    return
                }
                onUrlChanged(url)
            }
            onNavigationChanged(view)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            super.onPageCommitVisible(view, url)
            // CLASS の #menuPanel 高さ0 補正。対象ホスト以外では何もしない。
            ClassMenuPanelFix.applyIfNeeded(view, url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            SecureWebViewConfig.flushCookies()
            url?.let { onUrlChanged(it) }
            // ページ遷移ごとに再適用する（同一 document 内の変化はスクリプト側で追従）。
            ClassMenuPanelFix.applyIfNeeded(view, url)
            onNavigationChanged(view)
            onPageFinished()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            // JSF / SPA 的な history 更新でも、表示中ページを確認し直すまでボタンを隠す。
            onHistoryChanged()
            url?.let { onUrlChanged(it) }
            onNavigationChanged(view)
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: android.net.http.SslError,
        ) {
            // 証明書エラー時は絶対に続行しない（SSO の認証情報を保護する）。
            handler.cancel()
            onFatalError("サーバーの証明書を確認できませんでした。安全のため読み込みを中止しました。")
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String,
        ) {
            handler.cancel()
        }

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
            request.cancel()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                onFatalError("通信に失敗しました。ネットワーク接続を確認してください。")
            }
        }
    }

    loadUrl(initialUrl)
}
