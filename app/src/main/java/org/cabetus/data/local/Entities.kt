package org.cabetus.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** LETUS（Moodle）のコース。ログイン済みページから発見する。 */
@Entity(tableName = "moodle_courses")
data class MoodleCourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val updatedAt: Long = 0L,
)

/** 課題の提出状況。 */
enum class SubmissionStatus { UNKNOWN, NOT_SUBMITTED, SUBMITTED, COMPLETED }

/** 課題のライフサイクル状態。 */
enum class LifecycleStatus { ACTIVE, BEFORE_START, SUBMITTED, PASSED }

/** LETUS の課題。全機能から参照する中心データ。 */
@Entity(
    tableName = "assignments",
    indices = [Index("courseId"), Index("deadline")],
)
data class AssignmentEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val courseName: String,
    val title: String,
    val url: String,
    /** 締切のエポックミリ秒。パース不能なら null。 */
    val deadline: Long?,
    val deadlineText: String,
    val submissionStatus: SubmissionStatus,
    val lifecycleStatus: LifecycleStatus,
    val detectedAt: Long,
    val firstSeenAt: Long,
    val lastCheckedAt: Long,
    /** ユーザーが非表示にした課題。 */
    val ignored: Boolean = false,
)

/** 通知の重複防止キー（例: "{assignmentId}:24h"）。 */
@Entity(tableName = "notified_keys")
data class NotifiedKeyEntity(
    @PrimaryKey val key: String,
    val createdAt: Long,
)

/** CLASS の科目（時間割PDF・出欠PDF由来）。 */
@Entity(tableName = "class_courses")
data class ClassCourseEntity(
    @PrimaryKey val code: String,
    val name: String,
    val instructor: String,
    val credits: Double?,
    /** 出席率（%）。出欠PDF由来。 */
    val attendanceRate: Double?,
)

/** 時間割の1コマ（1セル内に複数科目があり得るため独立行）。 */
@Entity(
    tableName = "timetable_cells",
    indices = [Index("dayOfWeek", "period")],
)
data class TimetableCellEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 1=月 … 6=土 */
    val dayOfWeek: Int,
    val period: Int,
    val courseCode: String,
    val courseName: String,
    val instructor: String,
    /** "E501" など。オンラインの場合は null。 */
    val room: String?,
    val isOnline: Boolean,
    val isMultiSession: Boolean,
)

/** 授業回（出欠PDF由来）。未来の授業日程も含む（mark=null は未実施）。 */
@Entity(
    tableName = "class_sessions",
    indices = [Index("courseCode"), Index("date")],
)
data class ClassSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseCode: String,
    val sessionNo: Int,
    /** 授業日のエポックミリ秒（その日の 0:00 JST）。 */
    val date: Long,
    val period: Int,
    /** 出欠マーク（○▽△×公休外-等）。null=未実施。 */
    val mark: String?,
)

/** 通知の「出席チェック」ボタンで記録したローカル出席。 */
@Entity(tableName = "local_attendance", primaryKeys = ["courseCode", "date", "period"])
data class LocalAttendanceEntity(
    val courseCode: String,
    val date: Long,
    val period: Int,
    val checkedAt: Long,
)

/**
 * ユーザーが手動で修正した出席状況（PDF・アプリ記録より優先）。
 * PDF由来データを直接書き換えず上書きとして保存する。status は AttendanceStatus の enum 名。
 */
@Entity(tableName = "attendance_overrides", primaryKeys = ["courseCode", "date", "period"])
data class AttendanceOverrideEntity(
    val courseCode: String,
    val date: Long,
    val period: Int,
    val status: String,
    val updatedAt: Long,
)
