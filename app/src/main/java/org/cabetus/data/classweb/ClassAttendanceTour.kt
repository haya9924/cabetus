package org.cabetus.data.classweb

/**
 * 出欠一覧の前景自動巡回（プレビュー専用）の状態遷移。
 *
 * WebView の操作そのものは [org.cabetus.ui.browser.ClassAttendanceTourRunner] が行い、
 * このクラスは「今どの行を、どの段階まで進めたか」だけを純粋に管理する。
 * JVM でテストできるよう Android に依存しない。
 *
 * 巡回は必ず有限回で終わる。
 * - 一覧の行数は [ClassAttendanceListDomExtractor.MAX_ROWS] を上限にする
 * - 1行につき「開く → 詳細検証 → 抽出 → 戻る → 一覧再検証」の固定手順だけを進める
 * - 想定外の DOM・一覧の変化・タイムアウト・ユーザー中止では、その場で停止する
 *
 * 途中で停止した場合も、それまでに読み取れた行は [State.items] に残す（部分結果）。
 * 未処理の行は SKIPPED とし、成功として扱わない。
 * 保存・自動統合は行わない（結果はプレビュー表示だけ）。
 */
object ClassAttendanceTour {

    /** 一覧の1行の処理状態。 */
    enum class ItemStatus {
        PENDING,
        READING,
        DONE,
        FAILED,
        SKIPPED,
    }

    /** 現在の手順。 */
    enum class Phase {
        /** 一覧の行リンクをクリックする。 */
        CLICK_ROW,

        /** 詳細 DOM が表示されるまで検証する。 */
        VERIFY_DETAIL,

        /** 詳細 DOM からテーブルを読み取る。 */
        EXTRACT_DETAIL,

        /** 詳細画面の「戻る」をクリックする。 */
        CLICK_BACK,

        /** 一覧へ戻ったことを検証し、一覧の並びを再確認する。 */
        VERIFY_LIST,

        /** 全行を処理した。 */
        COMPLETE,

        /** 想定外の状態・失敗・中止で停止した。 */
        STOPPED,
    }

    data class Item(
        val row: ClassAttendanceListDomExtractor.Row,
        val status: ItemStatus = ItemStatus.PENDING,
        val preview: ClassAttendanceImportPreview? = null,
        val error: String? = null,
    )

    data class State(
        val phase: Phase,
        val roster: List<ClassAttendanceListDomExtractor.Row>,
        val items: List<Item>,
        /** 処理中の行の 0 始まり位置。終了後は -1。 */
        val currentIndex: Int,
        val stopReason: String? = null,
        val cancelled: Boolean = false,
    ) {
        val isTerminal: Boolean
            get() = phase == Phase.COMPLETE || phase == Phase.STOPPED

        val total: Int get() = items.size

        val doneCount: Int get() = items.count { it.status == ItemStatus.DONE }

        val failedCount: Int get() = items.count { it.status == ItemStatus.FAILED }

        val skippedCount: Int get() = items.count { it.status == ItemStatus.SKIPPED }

        /** 完了したが、失敗・未処理の行が残っている（部分結果）。 */
        val isPartial: Boolean get() = isTerminal && doneCount < total

        val currentItem: Item? get() = items.getOrNull(currentIndex)
    }

    /**
     * 巡回を開始する。行が空、または上限を超える場合は開始しない（null）。
     * 無制限ループを作らないため、ここで件数を確定する。
     */
    fun start(roster: List<ClassAttendanceListDomExtractor.Row>): State? {
        if (roster.isEmpty()) return null
        if (roster.size > ClassAttendanceListDomExtractor.MAX_ROWS) return null
        // 一覧の行番号は 1 始まりの連番であることを前提にする。
        // ずれていれば行対応を誤るため、巡回を開始しない。
        if (roster.any { it.index < 1 }) return null
        if (roster.withIndex().any { (position, row) -> row.index != position + 1 }) return null

        return State(
            phase = Phase.CLICK_ROW,
            roster = roster,
            items = roster.map { Item(row = it) },
            currentIndex = 0,
        )
    }

    /** 行リンクのクリックに成功した。 */
    fun onRowClicked(state: State): State =
        if (state.phase == Phase.CLICK_ROW) {
            markReading(state).copy(phase = Phase.VERIFY_DETAIL)
        } else {
            state
        }

    /** 詳細 DOM を検証できた。 */
    fun onDetailVerified(state: State): State =
        if (state.phase == Phase.VERIFY_DETAIL) {
            state.copy(phase = Phase.EXTRACT_DETAIL)
        } else {
            state
        }

    /**
     * 詳細テーブルの抽出とプレビュー変換に成功した。
     * 行の内容は保持し、続けて一覧へ戻る。
     */
    fun onDetailExtracted(state: State, preview: ClassAttendanceImportPreview): State =
        if (state.phase == Phase.EXTRACT_DETAIL) {
            updateCurrent(state) { it.copy(status = ItemStatus.DONE, preview = preview, error = null) }
                .copy(phase = Phase.CLICK_BACK)
        } else {
            state
        }

    /** 検証不能の科目は保存せず、戻った一覧を再検証して次へ進む。 */
    fun onDetailFailed(state: State, reason: String): State =
        if (state.phase == Phase.EXTRACT_DETAIL) updateCurrent(state) {
            it.copy(status = ItemStatus.FAILED, error = reason)
        }.copy(phase = Phase.CLICK_BACK) else state

    /** 「戻る」クリックに成功した。 */
    fun onBackClicked(state: State): State =
        if (state.phase == Phase.CLICK_BACK) {
            state.copy(phase = Phase.VERIFY_LIST)
        } else {
            state
        }

    /**
     * 一覧へ戻ったことを検証し、一覧の件数・並び・リンク先トークンが
     * 巡回開始時と同じか確認する。
     * 一致しなければ別の科目を読まないよう停止する。
     * 一致すれば次の行へ進み、最後の行なら完了する。
     */
    fun onListReverified(
        state: State,
        currentRoster: List<ClassAttendanceListDomExtractor.Row>,
    ): State {
        if (state.phase != Phase.VERIFY_LIST) return state

        if (!sameRoster(state.roster, currentRoster)) {
            return stop(
                state,
                reason = ClassAttendanceListDomExtractor.ErrorCode.LIST_CHANGED.userMessage,
                markCurrentFailed = true,
            )
        }

        val next = state.currentIndex + 1
        return if (next >= state.items.size) {
            state.copy(phase = Phase.COMPLETE, currentIndex = -1, stopReason = null)
        } else {
            state.copy(phase = Phase.CLICK_ROW, currentIndex = next)
        }
    }

    /**
     * 失敗・想定外 DOM・タイムアウトで停止する。
     * 処理中の行は FAILED、未処理の行は SKIPPED として明示する。
     */
    fun stop(state: State, reason: String, markCurrentFailed: Boolean = true): State {
        if (state.isTerminal) return state
        val items = state.items.mapIndexed { position, item ->
            when {
                // 読み取り済みの行は、後続の停止理由で失敗扱いに上書きしない。
                position == state.currentIndex && item.status == ItemStatus.DONE -> item
                position == state.currentIndex && markCurrentFailed ->
                    item.copy(status = ItemStatus.FAILED, error = reason)
                position == state.currentIndex ->
                    item.copy(status = ItemStatus.SKIPPED, error = reason)
                item.status == ItemStatus.PENDING || item.status == ItemStatus.READING ->
                    item.copy(status = ItemStatus.SKIPPED, error = "巡回を停止したため未処理です。")
                else -> item
            }
        }
        return state.copy(
            phase = Phase.STOPPED,
            items = items,
            currentIndex = -1,
            stopReason = reason,
        )
    }

    /** ユーザー操作による中止。途中までの結果は保持する。 */
    fun cancel(state: State, reason: String): State {
        if (state.isTerminal) return state
        return stop(state, reason, markCurrentFailed = false).copy(cancelled = true)
    }

    /** 一覧の件数と行トークン（リンク文字列 + onclick のハッシュ）が一致するか。 */
    fun sameRoster(
        baseline: List<ClassAttendanceListDomExtractor.Row>,
        current: List<ClassAttendanceListDomExtractor.Row>,
    ): Boolean {
        if (baseline.size != current.size) return false
        return baseline.indices.all { baseline[it].index == current[it].index &&
            baseline[it].token == current[it].token }
    }

    private fun markReading(state: State): State =
        updateCurrent(state) {
            it.copy(status = ItemStatus.READING, error = null)
        }

    private fun updateCurrent(state: State, transform: (Item) -> Item): State =
        if (state.currentIndex !in state.items.indices) {
            state
        } else {
            state.copy(
                items = state.items.mapIndexed { position, item ->
                    if (position == state.currentIndex) transform(item) else item
                },
            )
        }
}
