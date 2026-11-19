package org.cabetus.ui.browser

import org.cabetus.data.classweb.MobileAttendanceRepository

/**
 * モバイル出席の読み取り結果を、画面に出す 1 行のメッセージへ変換する。
 *
 * 優先順位は「認証要求 > 失敗または結果なし > 保存できず > 保存済みの内訳」。
 * 保存済みでも反映対象が 0 件なら、その旨だけを返す。
 */
internal fun mobileAttendanceResultMessage(
    result: MobileAttendanceRepository.CaptureResult?,
    authRequired: Boolean,
    failed: Boolean,
): String {
    if (authRequired) return "自動接続を確認してください"
    if (failed || result == null) return "取得できません"
    if (!result.saved) return "取得できません（読み取り結果を保持できませんでした）"

    val parts = buildList {
        if (result.confirmedCount > 0) add("出席表示を${result.confirmedCount}件反映")
        if (result.pendingCount > 0) add("受付中を${result.pendingCount}件反映")
        val unbound = result.unboundCount + result.pendingUnboundCount
        if (unbound > 0) add("時間割未紐付け ${unbound}件")
        val ambiguous = result.ambiguousCount + result.pendingAmbiguousCount
        if (ambiguous > 0) add("判定保留 ${ambiguous}件")
    }
    if (parts.isEmpty()) return "読み取り完了・反映対象なし"
    return parts.joinToString("・")
}
