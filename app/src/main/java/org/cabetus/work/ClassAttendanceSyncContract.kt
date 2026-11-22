package org.cabetus.work

/**
 * CLASS 出欠のバックグラウンド同期で、ワーカーと通知が共有する契約。
 *
 * WorkManager の入力 Data キー・通知 ID・チャンネル ID・中断理由を一箇所に固定し、
 * ワーカーと通知の片方だけを変更して進捗や結果が食い違わないようにする。
 * 認証値・Cookie・出席コードなどは一切ここに置かない。
 */
object ClassAttendanceSyncContract {
    /** Native JSF戻る操作と認証済み通信の実機確認が終わるまで既存取得経路を維持する。 */
    const val ENABLED = false

    /** 同期ジョブの一意名（`enqueueUniqueWork` で使う）。 */
    const val UNIQUE_WORK = "class-attendance-background-sync"

    /** 入力 Data のキー。 */
    const val YEAR = "year"
    const val PROCESSED = "processed"
    const val TOTAL = "total"
    const val SAVED = "saved"
    const val FAILED = "failed"
    const val REASON = "reason"

    /** 進捗通知と結果通知の ID（固定・上書き更新する）。 */
    const val PROGRESS_NOTIFICATION_ID = 74001
    const val RESULT_NOTIFICATION_ID = 74002

    /** 通知チャンネル。進捗は LOW、結果は DEFAULT（通知側で遅延生成する）。 */
    const val CHANNEL_PROGRESS = "class_sync_progress"
    const val CHANNEL_RESULT = "class_sync_result"

    /**
     * 同期を完了できなかった理由。
     *
     * Data には [key] を保存し、通知の文言には [label] を使う。
     * 想定外の値は [fromKey] が null を返すため、未知の理由として扱える。
     */
    enum class Reason(val key: String, val label: String) {
        AUTH("auth", "認証切れ"),
        NETWORK("network", "通信エラー"),
        UNSUPPORTED("unsupported", "未対応の画面"),
        VALIDATION("validation", "出欠一覧を確認できません"),
        STORAGE("storage", "保存に失敗"),
        CANCELLED("cancelled", "中止");

        companion object {
            fun fromKey(key: String?): Reason? = entries.firstOrNull { it.key == key }
        }
    }
}
