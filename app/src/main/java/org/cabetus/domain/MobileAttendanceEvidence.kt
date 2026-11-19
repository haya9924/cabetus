package org.cabetus.domain

import org.cabetus.data.classweb.ConfirmedAttendanceRecord
import org.cabetus.data.classweb.MobileAttendanceSnapshot
import org.cabetus.data.classweb.PendingAttendanceRecord
import org.cabetus.data.network.NetworkTransport
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * CLASS モバイル出席登録の読み取り結果を、ホームに表示してよいかの判定。
 *
 * キャッシュ（[MobileAttendanceSnapshot]）は、読み取った時刻・対象日付・
 * 端末のネットワーク種別を持つ。次のどれかに当てはまる場合は**失効**させ、
 * 「出席完了」も「受付中」も表示しない（古い情報を現在の出席として見せない）。
 * - 保存から [MAX_AGE] 以上経過している
 * - 端末の時計が読み取り時刻より前に戻っている（巻き戻し）
 * - 対象日付が日本時間（Asia/Tokyo）の日付と一致しない（日付をまたいだ）
 * - 読み取り時と端末のネットワーク種別が変わった（Wi-Fi ⇄ モバイルなど）
 * - 読み取り時にネットワーク状態を取得できていない、または端末がオフライン
 * - アプリが「要再ログイン」を確認している（ログイン切れ）
 *
 * 出席完了は「`label.attendSuc` + 完全一致『出席』」を読み取れたコマだけに出す。
 * 受付中は、同じく時間割と紐付いた「出席確認中」と受付時間を読み取れたコマだけに、
 * 受付終了までの残り時間として出す。受付開始前は受付中として扱わない。
 * どちらも確認できない場合（未読取・失効・紐付けなし・受付開始前・受付終了）は
 * 「状況未確認」（[STATUS_UNKNOWN_LABEL]）と表示し、既存の出席記録や
 * ネットワーク種別などから出席状況・未提出・残り期限を推測しない。
 *
 * 保存・読み取り・表示だけを行う純粋ロジックで、Android に依存しない。
 *
 * 日時は日本時間（[ZONE] = Asia/Tokyo）で扱う。端末のタイムゾーン設定には依存しない。
 */
object MobileAttendanceEvidence {

    /** 日付・時刻の基準タイムゾーン（日本時間）。 */
    val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

    /** キャッシュを有効とみなす最大経過時間。 */
    val MAX_AGE: Duration = Duration.ofMinutes(15)

    enum class Status {
        /** 読み取り結果が無い。 */
        NONE,

        /** 表示中のコマと紐付き、有効な「出席完了」。 */
        CONFIRMED,

        /** 表示中のコマと紐付き、受付中の状態。受付終了までの残り時間を出せる。 */
        PENDING,

        /** 表示中のコマと紐付くが、出席確認の受付開始前。 */
        BEFORE_START,

        /** 表示中のコマと紐付いていたが、出席確認の受付時間を過ぎた。 */
        EXPIRED,

        /** キャッシュはあるが古い・日付が違う・ネットワークが変わったなどで失効。 */
        STALE,

        /** キャッシュはあるが、表示中のコマとは紐付かない。 */
        NOT_APPLICABLE,
    }

    /** アプリが把握しているログイン状態。古い出席情報を使わないための判定に使う。 */
    enum class LoginState {
        /** ログイン済みとして確認できている。 */
        LOGGED_IN,

        /** 「要再ログイン」を確認している（ログイン切れ）。 */
        LOGGED_OUT,

        /** まだ確認できていない（この状態だけでは失効させない）。 */
        UNKNOWN,
    }

    /** ホームに出す表示。 */
    data class Display(
        val status: Status,
        /** 読み取り時刻（日本時間）。 */
        val capturedAt: LocalDateTime? = null,
        /** 読み取ってからの経過（秒）。 */
        val ageSeconds: Long? = null,
        /** 失効・非該当の理由（画面にそのまま出せる短い日本語）。 */
        val reason: String? = null,
        /** 読み取り時の端末ネットワーク種別。 */
        val transport: NetworkTransport? = null,
        /** キャッシュの対象日付（画面表記）。 */
        val dateLabel: String? = null,
        /** 画面に「学内ネットワークからのアクセス」が表示されていたか。 */
        val campusNetworkLabelVisible: Boolean = false,
        /** 出席確認の受付開始（表示用「HH:mm」）。 */
        val windowStartLabel: String? = null,
        /** 出席確認の受付終了（表示用「HH:mm」）。 */
        val windowEndLabel: String? = null,
        /** 受付終了までの残り秒数（受付中のときだけ）。 */
        val remainingSeconds: Long? = null,
        /** 受付終了の日時（日本時間。日付をまたがない）。 */
        val deadline: LocalDateTime? = null,
        /**
         * 紐付けたレコードが、画面に曜日時限が無いため推定で紐付けたものか。
         * true の場合は推定であることを画面に明示する。
         */
        val inferred: Boolean = false,
        /** 推定の根拠（推定した場合だけ。画面に出せる短い文言）。 */
        val inferenceBasis: String? = null,
    ) {
        val isConfirmed: Boolean get() = status == Status.CONFIRMED
        val isPending: Boolean get() = status == Status.PENDING

        /** 「出席確認時間 10:20～12:00」のような表示。 */
        val windowLabel: String?
            get() = if (windowStartLabel != null && windowEndLabel != null) {
                "$windowStartLabel～$windowEndLabel"
            } else {
                null
            }
    }

    /**
     * 対象のコマ（[dayOfWeek]・[period]・[courseCode]）について、キャッシュを評価する。
     *
     * [now] は日本時間（Asia/Tokyo）のローカル時刻として渡す（タイムゾーン変換はしない）。
     * 呼び出し側で `LocalDateTime.now(MobileAttendanceEvidence.ZONE)` のように JST を明示する。
     * [currentTransport] は現在の端末ネットワーク種別。不明なら null。
     * [currentNetworkKnown] は現在の状態を取得できているか。
     * [loginState] はアプリが把握しているログイン状態。
     */
    fun evaluate(
        snapshot: MobileAttendanceSnapshot?,
        now: LocalDateTime,
        dayOfWeek: Int,
        period: Int,
        courseCode: String,
        currentTransport: NetworkTransport?,
        currentNetworkKnown: Boolean,
        loginState: LoginState = LoginState.UNKNOWN,
    ): Display {
        if (snapshot == null) return Display(status = Status.NONE)

        val capturedAt = runCatching {
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(snapshot.capturedAtEpochMs),
                ZONE,
            )
        }.getOrNull()
        if (capturedAt == null) {
            return Display(
                status = Status.STALE,
                reason = "読み取り時刻を確認できないため失効しています。" +
                    "出席状況を確認するには CLASS のモバイル出席登録を開いてください。",
                campusNetworkLabelVisible = false,
            )
        }
        val rawAgeSeconds = Duration.between(capturedAt, now).seconds
        val ageSeconds = rawAgeSeconds.coerceAtLeast(0L)
        val transport = snapshot.transport?.let { name ->
            NetworkTransport.entries.firstOrNull { it.name == name }
        }
        val dateLabel = snapshot.displayDateLabel()

        val base = Display(
            status = Status.STALE,
            capturedAt = capturedAt,
            ageSeconds = ageSeconds,
            transport = transport,
            dateLabel = dateLabel,
            campusNetworkLabelVisible = snapshot.campusNetworkLabelVisible,
        )

        // 端末の時計が読み取り時刻より前に戻っている場合は、経過時間を信用せず失効させる。
        if (rawAgeSeconds < 0L) {
            return base.copy(
                reason = "端末の時計が読み取り時刻より前になっているため、読み取り結果を使いません。" +
                    "出席状況を確認するには CLASS のモバイル出席登録を開いてください。",
            )
        }
        // 経過時間。[MAX_AGE] 以上で失効（ちょうど MAX_AGE も失効させる）。
        if (ageSeconds >= MAX_AGE.seconds) {
            return base.copy(
                reason = "${formatAge(ageSeconds)}前の読み取りのため失効しています。" +
                    "出席状況を確認するには CLASS のモバイル出席登録を開いてください。",
            )
        }
        // 日付。年が画面に無い場合は月日だけで比較する（キャッシュ側の年は推測しない）。
        if (!snapshot.matchesDate(now.toLocalDate())) {
            return base.copy(
                reason = "読み取った日付（${dateLabel ?: "不明"}）が今日ではないため失効しています。",
            )
        }
        // 読み取り時のネットワーク状態。
        if (!snapshot.networkKnown || snapshot.transport == null) {
            return base.copy(
                reason = "読み取り時のネットワーク状態を確認できないため失効しています。",
            )
        }
        if (!currentNetworkKnown || currentTransport == null) {
            return base.copy(
                reason = "現在のネットワーク状態を確認できないため失効しています。",
            )
        }
        if (transport != currentTransport) {
            return base.copy(
                reason = "読み取り時（${transportLabel(transport)}）と現在" +
                    "（${transportLabel(currentTransport)}）のネットワークが異なるため失効しています。",
            )
        }
        // ログイン切れが確認できている場合は、古い読み取り結果を使わない。
        if (loginState == LoginState.LOGGED_OUT) {
            return base.copy(
                reason = "ログイン切れが確認されているため、以前の読み取り結果を使いません。" +
                    "CLASS にログインし直してから出席状況を確認してください。",
            )
        }
        val targetDate = now.toLocalDate()
        if (!snapshot.matchesDate(targetDate)) {
            return base.copy(reason = "読み取った日付と今日が一致しないため失効しています。")
        }

        val confirmed = snapshot.confirmed.firstOrNull { record ->
            record.matches(dayOfWeek, period, courseCode, targetDate)
        }
        if (confirmed != null) {
            // 対象のコマが終了したら、授業後の古い「出席完了」を残さない。
            val periodTime = PeriodTimes.of(period)
                ?: return base.copy(reason = "時限時刻を確認できないため失効しています。")
            if (now.toLocalTime().isAfter(periodTime.end)) {
                return base.copy(
                    reason = "この時限は終了しているため、読み取り結果は失効しています。",
                )
            }
            return base.copy(
                status = Status.CONFIRMED,
                reason = null,
                inferred = confirmed.inferred,
                inferenceBasis = confirmed.inferenceBasis,
            )
        }

        val pending = snapshot.pending.firstOrNull { record ->
            record.matches(dayOfWeek, period, courseCode, targetDate)
        }
        if (pending != null) {
            val start = minutesToTime(pending.windowStartMinutes)
            val end = minutesToTime(pending.windowEndMinutes)
            if (start == null || end == null || !end.isAfter(start)) {
                return base.copy(reason = "出席確認の受付時間を確認できないため失効しています。")
            }
            val deadline = LocalDateTime.of(targetDate, end)
            // 受付開始前は PENDING にしない（開始前を「受付中」と見せない）。
            if (now.isBefore(LocalDateTime.of(targetDate, start))) {
                return base.copy(
                    status = Status.BEFORE_START,
                    windowStartLabel = formatTime(start),
                    windowEndLabel = formatTime(end),
                    deadline = deadline,
                    inferred = pending.inferred,
                    inferenceBasis = pending.inferenceBasis,
                    reason = "出席確認の受付開始前（${formatTime(start)}～）です。" +
                        "受付状況は CLASS で確認してください。",
                )
            }
            if (now.isAfter(deadline)) {
                return base.copy(
                    status = Status.EXPIRED,
                    windowStartLabel = formatTime(start),
                    windowEndLabel = formatTime(end),
                    deadline = deadline,
                    inferred = pending.inferred,
                    inferenceBasis = pending.inferenceBasis,
                    reason = "出席確認の受付時間（${formatTime(start)}～${formatTime(end)}）を" +
                        "過ぎています。出席状況は CLASS で確認してください。",
                )
            }
            return base.copy(
                status = Status.PENDING,
                reason = null,
                windowStartLabel = formatTime(start),
                windowEndLabel = formatTime(end),
                remainingSeconds = Duration.between(now, deadline).seconds.coerceAtLeast(0L),
                deadline = deadline,
                inferred = pending.inferred,
                inferenceBasis = pending.inferenceBasis,
            )
        }

        return base.copy(
            status = Status.NOT_APPLICABLE,
            reason = "この授業の出席表示は読み取れていません。",
        )
    }

    /**
     * 出席状況の見出し。PENDING は「受付中」であり提出状況は未確認であることを示し、
     * 未提出とは断定しない。CONFIRMED（出席完了）だけを出席済みとして示し、
     * BEFORE_START・EXPIRED・STALE・NONE・NOT_APPLICABLE はすべて「状況未確認」にまとめる。
     */
    fun statusLabel(display: Display): String = when (display.status) {
        Status.CONFIRMED -> "出席完了"
        Status.PENDING -> PENDING_LABEL
        Status.BEFORE_START,
        Status.EXPIRED,
        Status.STALE,
        Status.NONE,
        Status.NOT_APPLICABLE,
        -> STATUS_UNKNOWN_LABEL
    }

    /** 受付中の固定表記。提出状況は未確認であることを明示し、未提出と断定しない。 */
    const val PENDING_LABEL: String = "受付中・提出状況未確認"

    /** 「状況未確認」の固定表記。 */
    const val STATUS_UNKNOWN_LABEL: String = "状況未確認"

    /**
     * 推定で紐付けたレコードであることの表示。
     *
     * 画面に曜日時限が無く、ページ日付の曜日・科目名・受付時間からコマを推定した場合だけ
     * 「推定」と短い根拠を出す。推定でない場合は null（何も出さない）。
     * 根拠は画面に出せる長さに切り詰める。
     */
    fun inferenceLabel(display: Display): String? {
        if (!display.inferred) return null
        val basis = display.inferenceBasis
            ?.trim()
            ?.removePrefix(INFERRED_LABEL)
            ?.trimStart(':', '：', '、', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return INFERRED_LABEL
        return "$INFERRED_LABEL（${basis.take(MAX_INFERENCE_BASIS_UI_LENGTH)}）"
    }

    /** 画面に出す推定の固定表記。 */
    const val INFERRED_LABEL: String = "推定"

    /** 画面に出す推定根拠の最大文字数。 */
    const val MAX_INFERENCE_BASIS_UI_LENGTH: Int = 40

    /**
     * 読み取りの新しさ。出席表示は CLASS の画面を**手動で**読み取った結果なので、
     * 読み取った時刻と表示時点での古さを明示する。読み取り時刻が無ければ null。
     */
    fun freshnessLabel(display: Display): String? {
        val capturedAt = display.capturedAt ?: return null
        val clock = "%02d:%02d".format(capturedAt.hour, capturedAt.minute)
        val age = display.ageSeconds
        return if (age == null) {
            "手動反映: $clock の読み取り"
        } else {
            "手動反映: $clock の読み取り（${formatAge(age)}前）"
        }
    }

    /** 「3分12秒」のような経過表記。 */
    fun formatAge(seconds: Long): String {
        val total = seconds.coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return when {
            hours > 0 -> "%d時間%02d分".format(hours, minutes)
            minutes > 0 -> "%d分%02d秒".format(minutes, secs)
            else -> "%d秒".format(secs)
        }
    }

    fun transportLabel(transport: NetworkTransport?): String = when (transport) {
        NetworkTransport.WIFI -> "Wi-Fi"
        NetworkTransport.CELLULAR -> "モバイルデータ"
        NetworkTransport.ETHERNET -> "有線LAN"
        NetworkTransport.VPN -> "VPN"
        NetworkTransport.BLUETOOTH -> "Bluetooth"
        NetworkTransport.OTHER -> "その他"
        NetworkTransport.NONE, null -> "なし"
    }

    /** 学内/学外の表示。CLASS 側のラベルを確認できたときだけ「学内」と言う。 */
    fun campusScopeLabel(display: Display): String = when {
        display.status == Status.NONE -> "学内/学外: 未確認"
        display.status == Status.STALE -> "学内/学外: 未確認（読み取りが失効）"
        display.campusNetworkLabelVisible -> "学内/学外: 学内（CLASS の表示）"
        else -> "学内/学外: 未確認"
    }

    /** 画面に出す短い説明。推測を避け、CLASS 側の表示だけを根拠にする。 */
    const val SCOPE_NOTE: String =
        "学内/学外は、CLASS のモバイル出席登録に「学内ネットワークからのアクセス」が" +
            "表示されていた場合だけ「学内」と表示します。表示が無い場合は学外と断定せず、" +
            "「未確認」のままにします。"

    /** この出席表示が自動更新されない手動の読み取り結果であることの説明。 */
    const val MANUAL_READ_NOTE: String =
        "出席状況は、CLASS のモバイル出席登録を手動で読み取った結果です（自動更新はありません）。" +
            "読み取りから時間が経つと失効し、「状況未確認」になります。" +
            "残り時間は出席確認の受付終了までで、授業自体の残り時間とは別です。"

    /** 出席確認の締切表示に関する説明。 */
    const val PENDING_NOTE: String =
        "受付中の表示は、CLASS のモバイル出席登録に「出席確認中」と受付時間が表示され、" +
            "科目・日付・時限を時間割と確実に紐付けられた場合だけ行います。" +
            "受付中は提出状況が未確認という意味で、未提出と断定はしません。" +
            "「出席」の表示を確認できた場合は「出席完了」に切り替えます。" +
            "読み取りが古い・日付やネットワークが変わった・ログイン切れ・受付開始前・受付時間を過ぎた場合は" +
            "「状況未確認」と表示し、出席状況を推測しません。"

    private fun minutesToTime(minutes: Int): LocalTime? =
        runCatching { LocalTime.of(minutes / 60, minutes % 60) }.getOrNull()

    private fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    private fun MobileAttendanceSnapshot.displayDateLabel(): String =
        if (year != null) "%d/%d/%d".format(year, month, day) else "%d/%d".format(month, day)

    private fun MobileAttendanceSnapshot.matchesDate(date: LocalDate): Boolean =
        if (year != null) {
            year == date.year && month == date.monthValue && day == date.dayOfMonth
        } else {
            month == date.monthValue && day == date.dayOfMonth
        }
}

/** 曜日番号の表示用（1=月 … 7=日）。 */
internal fun dayOfWeekLabel(dayOfWeek: Int): String = when (dayOfWeek) {
    1 -> "月"
    2 -> "火"
    3 -> "水"
    4 -> "木"
    5 -> "金"
    6 -> "土"
    7 -> "日"
    else -> "?"
}

private fun ConfirmedAttendanceRecord.matches(
    dayOfWeek: Int,
    period: Int,
    courseCode: String,
    date: LocalDate,
): Boolean =
    matches(dayOfWeek, period, courseCode) && dateMatches(date)

private fun PendingAttendanceRecord.matches(
    dayOfWeek: Int,
    period: Int,
    courseCode: String,
    date: LocalDate,
): Boolean =
    this.dayOfWeek == dayOfWeek &&
        this.period == period &&
        this.courseCode == courseCode &&
        dateMatches(date)

private fun ConfirmedAttendanceRecord.dateMatches(date: LocalDate): Boolean =
    if (year != null) {
        year == date.year && month == date.monthValue && day == date.dayOfMonth
    } else {
        month == date.monthValue && day == date.dayOfMonth
    }

private fun PendingAttendanceRecord.dateMatches(date: LocalDate): Boolean =
    if (year != null) {
        year == date.year && month == date.monthValue && day == date.dayOfMonth
    } else {
        month == date.monthValue && day == date.dayOfMonth
    }

/** テスト等で [ConfirmedAttendanceRecord] を作るときの補助。 */
internal fun ConfirmedAttendanceRecord.matches(dayOfWeek: Int, period: Int, courseCode: String): Boolean =
    this.dayOfWeek == dayOfWeek && this.period == period && this.courseCode == courseCode
