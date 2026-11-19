package org.cabetus.domain

/**
 * モバイル出席の再取得を間引くための純粋な判定。
 *
 * キー（対象コマ+通信種別など）が変わったとき、まだ一度も開始していないとき、
 * 端末の時計が巻き戻ったとき、前回の開始から [intervalMs] 以上経過したときだけ due。
 * 実際に取得を開始した時点で [markStarted] を呼ぶ（busy などで開始できなかった場合は呼ばない）。
 */
class MobileAttendanceRefreshGate(
    private val intervalMs: Long = 60_000L,
) {
    private var lastKey: String? = null
    private var lastAttemptAt: Long? = null

    fun isDue(key: String, nowMs: Long): Boolean {
        if (key != lastKey) return true
        val last = lastAttemptAt ?: return true
        // 時計の巻き戻し（now が前回開始より前）は経過を判断できないため due にする。
        if (nowMs < last) return true
        return nowMs - last >= intervalMs
    }

    fun markStarted(key: String, nowMs: Long) {
        lastKey = key
        lastAttemptAt = nowMs
    }
}
