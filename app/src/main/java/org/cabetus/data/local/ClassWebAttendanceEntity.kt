package org.cabetus.data.local

import androidx.room.Entity

/** ClassWeb（授業支援システム）から取得した出欠1コマ分。 */
@Entity(
    tableName = "class_web_attendance",
    primaryKeys = ["academicYear", "term", "courseCode", "date", "period"],
)
data class ClassWebAttendanceEntity(
    val academicYear: Int,
    /** 学期（例: "前期", "後期", "通年"）。 */
    val term: String,
    val courseCode: String,
    /** 授業日のエポックミリ秒（その日の 0:00 JST）。 */
    val date: Long,
    val period: Int,
    val sessionNo: Int?,
    /** ClassWeb の生の出欠マーク。 */
    val rawMark: String,
    val fetchedAt: Long,
)
