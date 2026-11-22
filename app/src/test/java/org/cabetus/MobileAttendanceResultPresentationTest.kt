package org.cabetus

import org.cabetus.data.classweb.MobileAttendanceRepository.CaptureResult
import org.cabetus.ui.browser.mobileAttendanceResultMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** [mobileAttendanceResultMessage] の優先順位と内訳表示のテスト。 */
class MobileAttendanceResultPresentationTest {

    private fun saved(
        confirmedCount: Int = 0,
        pendingCount: Int = 0,
        unboundCount: Int = 0,
        ambiguousCount: Int = 0,
        pendingUnboundCount: Int = 0,
        pendingAmbiguousCount: Int = 0,
    ) = CaptureResult(
        saved = true,
        confirmedCount = confirmedCount,
        pendingCount = pendingCount,
        unboundCount = unboundCount,
        ambiguousCount = ambiguousCount,
        pendingUnboundCount = pendingUnboundCount,
        pendingAmbiguousCount = pendingAmbiguousCount,
        message = "",
    )

    @Test
    fun `all categories are joined`() {
        val result = saved(
            confirmedCount = 2,
            pendingCount = 1,
            unboundCount = 3,
            ambiguousCount = 1,
            pendingUnboundCount = 2,
            pendingAmbiguousCount = 4,
        )
        assertEquals(
            "出席表示を2件反映・受付中を1件反映・時間割未紐付け 5件・判定保留 5件",
            mobileAttendanceResultMessage(result, authRequired = false, failed = false),
        )
    }

    @Test
    fun `only nonzero categories appear`() {
        val result = saved(confirmedCount = 1, pendingCount = 1)
        assertEquals(
            "出席表示を1件反映・受付中を1件反映",
            mobileAttendanceResultMessage(result, authRequired = false, failed = false),
        )
        val unbound = saved(unboundCount = 1, pendingUnboundCount = 1)
        assertEquals(
            "時間割未紐付け 2件",
            mobileAttendanceResultMessage(unbound, authRequired = false, failed = false),
        )
        val ambiguous = saved(ambiguousCount = 2, pendingAmbiguousCount = 3)
        assertEquals(
            "判定保留 5件",
            mobileAttendanceResultMessage(ambiguous, authRequired = false, failed = false),
        )
    }

    @Test
    fun `all zero reports nothing to apply`() {
        val message = mobileAttendanceResultMessage(saved(), authRequired = false, failed = false)
        assertEquals("読み取り完了・反映対象なし", message)
        assertFalse(message.contains("受付表示なし"))
    }

    @Test
    fun `unsaved result cannot be kept`() {
        val result = CaptureResult(
            saved = false,
            confirmedCount = 0,
            unboundCount = 2,
            ambiguousCount = 1,
            message = "内部メッセージ",
        )
        assertEquals(
            "取得できません（読み取り結果を保持できませんでした）",
            mobileAttendanceResultMessage(result, authRequired = false, failed = false),
        )
    }

    @Test
    fun `null result is unavailable`() {
        assertEquals(
            "取得できません",
            mobileAttendanceResultMessage(null, authRequired = false, failed = false),
        )
    }

    @Test
    fun `failure overrides successful result`() {
        val result = saved(confirmedCount = 3)
        assertEquals(
            "取得できません",
            mobileAttendanceResultMessage(result, authRequired = false, failed = true),
        )
    }

    @Test
    fun `auth requirement takes precedence over everything`() {
        assertEquals(
            "自動接続を確認してください",
            mobileAttendanceResultMessage(saved(confirmedCount = 3), authRequired = true, failed = false),
        )
        assertEquals(
            "自動接続を確認してください",
            mobileAttendanceResultMessage(null, authRequired = true, failed = true),
        )
        assertEquals(
            "自動接続を確認してください",
            mobileAttendanceResultMessage(
                CaptureResult(saved = false, confirmedCount = 0, unboundCount = 0, ambiguousCount = 0, message = ""),
                authRequired = true,
                failed = true,
            ),
        )
    }
}
