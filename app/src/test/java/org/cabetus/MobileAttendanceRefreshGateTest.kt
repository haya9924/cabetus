package org.cabetus

import org.cabetus.domain.MobileAttendanceRefreshGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * モバイル出席の再取得スロットル判定のテスト。
 *
 * 同じキー（対象コマ+通信種別）では一定間隔を空ける。開始を記録していない
 * （busy などで実際には取得していない）場合は due のままにする。
 */
class MobileAttendanceRefreshGateTest {

    private val anyway = MobileAttendanceRefreshGate(intervalMs = 60_000L)

    @Test
    fun `初回は常に due`() {
        assertTrue(anyway.isDue(key = "slot-1:wifi", nowMs = 1_000L))
    }

    @Test
    fun `間隔直前は due ではない`() {
        anyway.markStarted(key = "slot-1:wifi", nowMs = 1_000L)
        assertFalse(anyway.isDue(key = "slot-1:wifi", nowMs = 1_000L + 59_999L))
    }

    @Test
    fun `間隔ちょうどで due`() {
        anyway.markStarted(key = "slot-1:wifi", nowMs = 1_000L)
        assertTrue(anyway.isDue(key = "slot-1:wifi", nowMs = 1_000L + 60_000L))
    }

    @Test
    fun `キーが変われば due`() {
        anyway.markStarted(key = "slot-1:wifi", nowMs = 1_000L)

        assertTrue(anyway.isDue(key = "slot-2:wifi", nowMs = 1_000L + 1L))
        assertTrue(anyway.isDue(key = "slot-1:mobile", nowMs = 1_000L + 1L))
        assertFalse(anyway.isDue(key = "slot-1:wifi", nowMs = 1_000L + 1L))
    }

    @Test
    fun `開始を記録していなければ busy 解除後も due のまま`() {
        anyway.markStarted(key = "slot-1:wifi", nowMs = 1_000L)
        // 開始したコマからは due ではない。
        assertFalse(anyway.isDue(key = "slot-1:wifi", nowMs = 1_000L + 1L))

        // 別キーで取得を試みたが busy で実際には開始していない（markStarted しない）。
        assertTrue(anyway.isDue(key = "slot-2:wifi", nowMs = 1_000L + 2L))
        assertTrue(anyway.isDue(key = "slot-2:wifi", nowMs = 1_000L + 3L))

        // 間隔内でも、直前の開始記録はキーが違うため影響しない。
        assertTrue(anyway.isDue(key = "slot-3:wifi", nowMs = 1_000L + 4L))
    }

    @Test
    fun `時計の巻き戻しは due`() {
        anyway.markStarted(key = "slot-1:wifi", nowMs = 10_000L)
        assertTrue(anyway.isDue(key = "slot-1:wifi", nowMs = 9_999L))
    }
}
