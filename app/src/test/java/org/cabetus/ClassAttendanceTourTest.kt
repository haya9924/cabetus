package org.cabetus

import org.cabetus.data.classweb.ClassAttendanceListDomExtractor
import org.cabetus.data.classweb.ClassAttendanceTour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出欠一覧の前景巡回の状態遷移（純ロジック）の合成テスト。
 * WebView には触れず、合成した行データだけで有限・安全に停止することを確認する。
 */
class ClassAttendanceTourTest {

    private fun row(index: Int, token: String = "%08x".format(index)) =
        ClassAttendanceListDomExtractor.Row(index, "合成科目$index", token)

    private fun roster(size: Int): List<ClassAttendanceListDomExtractor.Row> =
        (1..size).map { row(it) }

    /** 1 行分（クリック→詳細検証→抽出→戻る→一覧再検証）を進める。 */
    private fun advanceRow(
        state: ClassAttendanceTour.State,
        currentRoster: List<ClassAttendanceListDomExtractor.Row> = state.roster,
    ): ClassAttendanceTour.State {
        var next = ClassAttendanceTour.onRowClicked(state)
        next = ClassAttendanceTour.onDetailVerified(next)
        next = ClassAttendanceTour.onDetailExtracted(
            next,
            org.cabetus.data.classweb.ClassAttendanceImportPreview(
                heading = "出欠詳細",
                rows = emptyList(),
                warnings = emptyList(),
                errors = emptyList(),
            ),
        )
        next = ClassAttendanceTour.onBackClicked(next)
        return ClassAttendanceTour.onListReverified(next, currentRoster)
    }

    @Test
    fun startRequiresNonEmptyContiguousRosterWithinLimit() {
        assertNull(ClassAttendanceTour.start(emptyList()))

        // 行番号は 1 始まりの連番であること。
        assertNull(ClassAttendanceTour.start(listOf(row(0))))
        assertNull(ClassAttendanceTour.start(listOf(row(1), row(3))))
        assertNull(ClassAttendanceTour.start(listOf(row(2), row(1))))

        // 無制限ループを防ぐため上限を超える一覧は開始しない。
        assertNull(
            ClassAttendanceTour.start(roster(ClassAttendanceListDomExtractor.MAX_ROWS + 1)),
        )

        val state = ClassAttendanceTour.start(roster(2))!!
        assertEquals(ClassAttendanceTour.Phase.CLICK_ROW, state.phase)
        assertEquals(0, state.currentIndex)
        assertEquals(2, state.total)
        assertEquals(0, state.doneCount)
        assertFalse(state.isTerminal)
    }

    @Test
    fun phaseTransitionsAreOrderedAndIgnoreOutOfOrderCalls() {
        val initial = ClassAttendanceTour.start(roster(1))!!

        // 順序外の遷移は状態を変えない。
        assertEquals(initial, ClassAttendanceTour.onDetailVerified(initial))
        assertEquals(initial, ClassAttendanceTour.onBackClicked(initial))
        assertEquals(
            initial,
            ClassAttendanceTour.onListReverified(initial, initial.roster),
        )

        val clicked = ClassAttendanceTour.onRowClicked(initial)
        assertEquals(ClassAttendanceTour.Phase.VERIFY_DETAIL, clicked.phase)
        assertEquals(
            ClassAttendanceTour.ItemStatus.READING,
            clicked.currentItem!!.status,
        )
        // 二重クリック相当の呼び出しでは進まない。
        assertEquals(clicked, ClassAttendanceTour.onRowClicked(clicked))
    }

    @Test
    fun completesAllRowsAndEndsInTerminalCompleteState() {
        var state = ClassAttendanceTour.start(roster(3))!!

        state = advanceRow(state)
        assertEquals(ClassAttendanceTour.Phase.CLICK_ROW, state.phase)
        assertEquals(1, state.currentIndex)
        assertEquals(1, state.doneCount)

        state = advanceRow(state)
        assertEquals(2, state.currentIndex)

        state = advanceRow(state)
        assertEquals(ClassAttendanceTour.Phase.COMPLETE, state.phase)
        assertTrue(state.isTerminal)
        assertEquals(-1, state.currentIndex)
        assertEquals(3, state.doneCount)
        assertEquals(0, state.failedCount)
        assertEquals(0, state.skippedCount)
        assertFalse(state.isPartial)
        assertNull(state.stopReason)
        assertFalse(state.cancelled)
    }

    @Test
    fun changedRosterStopsInsteadOfReadingAnotherSubject() {
        val state = ClassAttendanceTour.start(roster(3))!!
        var current = advanceRow(state)
        assertEquals(1, current.currentIndex)

        // 2 行目を開く途中で、一覧の件数・並び・リンク先が変わった。
        val clicked = ClassAttendanceTour.onRowClicked(current)
        val extracted = ClassAttendanceTour.onDetailExtracted(
            ClassAttendanceTour.onDetailVerified(clicked),
            org.cabetus.data.classweb.ClassAttendanceImportPreview(
                heading = "出欠詳細",
                rows = emptyList(),
                warnings = emptyList(),
                errors = emptyList(),
            ),
        )
        val back = ClassAttendanceTour.onBackClicked(extracted)
        val changed = listOf(row(1), row(2, "deadbeef"), row(3))
        current = ClassAttendanceTour.onListReverified(back, changed)

        assertEquals(ClassAttendanceTour.Phase.STOPPED, current.phase)
        assertEquals(ClassAttendanceListDomExtractor.ErrorCode.LIST_CHANGED.userMessage, current.stopReason)
        assertEquals(ClassAttendanceTour.ItemStatus.DONE, current.items[0].status)
        // 読み取り済みの行は残し、停止原因の行は FAILED、以降は SKIPPED。
        // 読み取り済みの行は、後続の停止で取り消さない。
        assertEquals(ClassAttendanceTour.ItemStatus.DONE, current.items[1].status)
        assertEquals(ClassAttendanceTour.ItemStatus.SKIPPED, current.items[2].status)
        assertTrue(current.isPartial)
    }

    @Test
    fun sameRosterComparesIndexAndToken() {
        val baseline = roster(2)
        assertTrue(ClassAttendanceTour.sameRoster(baseline, roster(2)))
        assertFalse(ClassAttendanceTour.sameRoster(baseline, roster(3)))
        assertFalse(
            ClassAttendanceTour.sameRoster(baseline, listOf(row(1), row(2, "ffffffff"))),
        )
        assertFalse(ClassAttendanceTour.sameRoster(baseline, listOf(row(2), row(1))))
    }

    @Test
    fun stopMarksCurrentRowFailedAndLeavesRemainingSkipped() {
        val state = ClassAttendanceTour.start(roster(3))!!
        val clicked = ClassAttendanceTour.onRowClicked(state)
        val stopped = ClassAttendanceTour.stop(clicked, "タイムアウト")

        assertEquals(ClassAttendanceTour.Phase.STOPPED, stopped.phase)
        assertEquals(-1, stopped.currentIndex)
        assertEquals(ClassAttendanceTour.ItemStatus.FAILED, stopped.items[0].status)
        assertEquals(ClassAttendanceTour.ItemStatus.SKIPPED, stopped.items[1].status)
        assertEquals(ClassAttendanceTour.ItemStatus.SKIPPED, stopped.items[2].status)
        assertEquals(1, stopped.failedCount)
        assertEquals(2, stopped.skippedCount)
        assertEquals(0, stopped.doneCount)
        assertTrue(stopped.isPartial)

        // 終了後の停止・中止は状態を変えない。
        assertEquals(stopped, ClassAttendanceTour.stop(stopped, "再度停止"))
        assertEquals(stopped, ClassAttendanceTour.cancel(stopped, "再度中止"))
    }

    @Test
    fun cancelKeepsPartialResultsAndDoesNotMarkCurrentRowFailed() {
        var state = ClassAttendanceTour.start(roster(2))!!
        state = advanceRow(state)
        val clicked = ClassAttendanceTour.onRowClicked(state)

        val cancelled = ClassAttendanceTour.cancel(clicked, "ユーザー操作で巡回を中止しました。")

        assertEquals(ClassAttendanceTour.Phase.STOPPED, cancelled.phase)
        assertTrue(cancelled.cancelled)
        assertEquals(ClassAttendanceTour.ItemStatus.DONE, cancelled.items[0].status)
        // 中止時は処理中の行を失敗扱いにせず、未処理として残す。
        assertEquals(ClassAttendanceTour.ItemStatus.SKIPPED, cancelled.items[1].status)
        assertEquals(1, cancelled.doneCount)
        assertEquals(0, cancelled.failedCount)
        assertEquals(1, cancelled.skippedCount)
        assertTrue(cancelled.isPartial)
    }
}
