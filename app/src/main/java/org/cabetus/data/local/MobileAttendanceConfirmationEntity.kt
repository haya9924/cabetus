package org.cabetus.data.local

import androidx.room.Entity

/**
 * モバイル出席（ClassWeb モバイル画面）で確認した出席1コマ分。
 *
 * 同じ科目・日付・時限の確認は 1 行だけ保持する。既存行は削除・上書きしない
 * （再取り込みは IGNORE。既存レコードを保持する）。
 */
@Entity(
    tableName = "mobile_attendance_confirmations",
    primaryKeys = ["courseCode", "date", "period"],
)
data class MobileAttendanceConfirmationEntity(
    val courseCode: String,
    /** 授業日のエポックミリ秒（その日の 0:00 JST）。 */
    val date: Long,
    val period: Int,
    /** 出席確認した時刻のエポックミリ秒。 */
    val confirmedAt: Long,
    /**
     * 画面に曜日時限が無く、ページ日付の曜日・科目名・受付時間から
     * コマを推定したレコードか。
     */
    val inferred: Boolean,
    /** 推定の根拠（推定した場合だけ。明示レコードは null）。 */
    val inferenceBasis: String?,
)
