package org.cabetus.data.classweb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.TimetableCellEntity
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.network.NetworkMonitor
import org.cabetus.data.network.NetworkStatus
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CLASS モバイル出席登録の読み取り結果のキャッシュを扱う。
 *
 * - 保存するのは、時間割のコマと確実に紐付けられた「出席」表示と、その読み取り時刻・
 *   対象日付・端末ネットワーク種別だけである（[MobileAttendanceSnapshot]）。
 * - 紐付けできなかった授業は件数だけを保存し、科目名は保存しない。
 * - 出席の登録・送信・コード入力は行わない。読み取り専用。
 * - 時間割（`timetable_cells` / `class_courses`）へは書き込まない。
 * - 確実に紐付いた出席完了は専用テーブルに永続化し、出席状況の最優先根拠とする。
 *   受付中・失敗・キャッシュ消去で確認済みの履歴を削除しない。
 *
 * キャッシュは**プロセス内のメモリだけ**に保持する（[MutableStateFlow]）。
 * DataStore などへは保存せず、アプリのプロセスが終了すれば消える。
 * 以前のバージョンが DataStore に保存した古い読み取り値は**一切読み込まない**
 * （失効・ログアウト・ネットワーク断・変更・再取得失敗・プロセス終了で復活しない）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MobileAttendanceRepository @Inject constructor(
    private val timetableDao: TimetableDao,
    private val classCourseDao: ClassCourseDao,
    private val clock: Clock,
    private val networkMonitor: NetworkMonitor,
    private val confirmations: org.cabetus.data.local.MobileAttendanceConfirmationDao,
) {

    /** プロセス内だけのキャッシュ。起動直後・破棄後は必ず null。 */
    private val _snapshot = MutableStateFlow<MobileAttendanceSnapshot?>(null)

    /** 現在のプロセスで読み取った出席証拠。古い保存値は読み込まない。 */
    val snapshot: StateFlow<MobileAttendanceSnapshot?> = _snapshot.asStateFlow()

    /** ネットワーク状態の監視用。シングルトンなのでプロセス終了まで生きる。 */
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // キャッシュが存在する間だけ NetworkCallback を登録し、
        // ネットワークが不明・切断・読み取り時と別種別になったらメモリ上の値を捨てる。
        monitorScope.launch {
            runCatching {
                _snapshot
                    .map { current -> current != null }
                    .distinctUntilChanged()
                    .flatMapLatest { hasSnapshot ->
                        if (hasSnapshot) networkMonitor.status else emptyFlow()
                    }
                    .collect { status -> onNetworkStatusChanged(status) }
            }
        }
    }

    /**
     * 読み取った内容を、既存の時間割と紐付けて**メモリ上のキャッシュ**へ反映する。
     *
     * 時間割のコマと 1 件も紐付かない場合も、読み取った件数と注意だけを保持する
     * （「出席完了」の表示には使われない）。
     */
    suspend fun capture(
        observation: ClassMobileAttendanceDomExtractor.Observation,
        networkStatus: NetworkStatus,
        isStillValid: () -> Boolean = { true },
    ): CaptureResult = withContext(Dispatchers.IO) {
        val cells = timetableDao.getAll()
        val courses = classCourseDao.getAll()
        val nameByCode = courses.associate { it.code to it.name }
        val bound = MobileAttendanceBinder.bind(observation, cells, nameByCode)

        val capturedAt = clock.millis()
        val snapshot = MobileAttendanceSnapshot(
            capturedAtEpochMs = capturedAt,
            year = observation.pageDate?.year,
            month = observation.pageDate?.month ?: 0,
            day = observation.pageDate?.day ?: 0,
            weekdayLabel = observation.pageDate?.weekdayLabel,
            transport = networkStatus.transport.takeIf { networkStatus.isKnown }?.name,
            networkKnown = networkStatus.isKnown && networkStatus.isConnected,
            campusNetworkLabelVisible = observation.campusNetworkLabelVisible,
            confirmed = bound.confirmed,
            pending = bound.pending,
            unboundCourseCount = bound.unboundCount,
            ambiguousCourseCount = bound.ambiguousCount,
            pendingUnboundCourseCount = bound.pendingUnboundCount,
            pendingAmbiguousCourseCount = bound.pendingAmbiguousCount,
            pageCourses = projectMobileAttendancePageCourses(observation),
            warnings = (observation.warnings + bound.warnings).distinct(),
        )

        val encoded = MobileAttendanceCodec.encode(snapshot)
        if (MobileAttendanceCodec.decode(encoded) == null) {
            return@withContext CaptureResult(
                saved = false,
                confirmedCount = 0,
                unboundCount = bound.unboundCount,
                ambiguousCount = bound.ambiguousCount,
                message = "読み取り結果を安全に保持できない形式だったため、ホームへの反映を見送りました。",
            )
        }
        // 成功ラベルから一意に紐付いた分だけを永続化する。受付中・失効・再読取失敗では消さない。
        // DB の失敗は呼出側へ伝え、保存前に成功表示を出さない。
        val history = MobileAttendanceConfirmationPlanner.plan(snapshot)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(isStillValid()) { "Attendance document changed before saving" }
        if (networkStatus.isKnown && networkStatus.isConnected && history.isNotEmpty()) {
            confirmations.insertAll(history)
        }
        _snapshot.value = snapshot

        CaptureResult(
            saved = true,
            confirmedCount = snapshot.confirmed.size,
            pendingCount = snapshot.pending.size,
            unboundCount = bound.unboundCount,
            ambiguousCount = bound.ambiguousCount,
            pendingUnboundCount = bound.pendingUnboundCount,
            pendingAmbiguousCount = bound.pendingAmbiguousCount,
            message = buildMessage(snapshot) + if (history.isNotEmpty() && networkStatus.isKnown && networkStatus.isConnected) {
                "\n出席登録画面で確認した${history.size}件を出席状況に連携しました（最優先）。"
            } else "",
        )
    }

    /**
     * キャッシュを破棄する（失効・ネットワーク断/変更・再取得失敗・ログアウト時など）。
     *
     * メモリ上の値だけを消す。破棄後に古い読み取り値が復活することはない。
     */
    suspend fun clear() {
        _snapshot.value = null
    }

    /**
     * ネットワーク状態が変わったときの失効判定。
     *
     * 読み取り時と同じネットワーク（不明・切断を除く）でなければ、古い出席表示を
     * 使い続けないようメモリ上のキャッシュを破棄する。
     */
    private fun onNetworkStatusChanged(status: NetworkStatus) {
        val current = _snapshot.value ?: return
        if (!isUsable(current, status)) {
            _snapshot.value = null
        }
    }

    private fun isUsable(snapshot: MobileAttendanceSnapshot, status: NetworkStatus): Boolean {
        if (!snapshot.networkKnown) return false
        val capturedTransport = snapshot.transport ?: return false
        if (!status.isKnown || !status.isConnected) return false
        return capturedTransport == status.transport.name
    }

    private fun buildMessage(snapshot: MobileAttendanceSnapshot): String = buildString {
        if (snapshot.pageCourses.isEmpty()) {
            append("出席登録画面から読み取れた科目はありません。")
        } else {
            append("出席登録画面から${snapshot.pageCourses.size}件の科目を読み取りました。")
        }
        if (snapshot.unboundCourseCount > 0) {
            append("\n時間割と紐付けられない出席が${snapshot.unboundCourseCount}件あります（出席履歴には連携しません）。")
        }
        if (snapshot.ambiguousCourseCount > 0) {
            append("\n判定できない表記が${snapshot.ambiguousCourseCount}件あります（反映しません）。")
        }
        append("\nこの読み取り結果は、時刻・日付・ネットワークの変化で失効します。")
        append("\nホームの表示は一時的です。日付・科目・時限を確定できた出席完了は、別途出席履歴に保存します。")
        append("\n出欠の登録・送信は行っていません。")
    }

    data class CaptureResult(
        val saved: Boolean,
        val confirmedCount: Int,
        val pendingCount: Int = 0,
        val unboundCount: Int,
        val ambiguousCount: Int,
        val pendingUnboundCount: Int = 0,
        val pendingAmbiguousCount: Int = 0,
        val message: String,
    )
}

/** 読み取り時刻をテストから制御するための補助。 */
internal fun nowLocal(clock: Clock): LocalDateTime =
    LocalDateTime.now(clock).atZone(ZoneId.systemDefault()).toLocalDateTime()

/** 現在の時間割のコマ（テスト用の補助）。 */
internal fun List<TimetableCellEntity>.forSlot(dayOfWeek: Int, period: Int): List<TimetableCellEntity> =
    filter { it.dayOfWeek == dayOfWeek && it.period == period }
