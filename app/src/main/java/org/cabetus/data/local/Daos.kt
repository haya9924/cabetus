package org.cabetus.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MoodleCourseDao {
    @Upsert
    suspend fun upsertAll(courses: List<MoodleCourseEntity>)

    /** 未登録コースのみ追加する。既存行（ユーザーの enabled 設定）は変更しない。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiscovered(courses: List<MoodleCourseEntity>)

    /** 表示名・URL・更新時刻のみ更新する。enabled には触れない。 */
    @Query("UPDATE moodle_courses SET name = :name, url = :url, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMetadata(id: String, name: String, url: String, updatedAt: Long)

    /**
     * コース発見結果を既存データと競合させずに反映する。
     * 新規は enabled = true で追加し、既存はメタデータのみ更新する。
     */
    @Transaction
    suspend fun mergeDiscovered(courses: List<MoodleCourseEntity>) {
        if (courses.isEmpty()) return
        insertDiscovered(courses)
        courses.forEach { updateMetadata(it.id, it.name, it.url, it.updatedAt) }
    }

    @Query("SELECT * FROM moodle_courses WHERE enabled = 1")
    suspend fun getEnabled(): List<MoodleCourseEntity>

    @Query("SELECT * FROM moodle_courses")
    suspend fun getAll(): List<MoodleCourseEntity>

    @Query("SELECT * FROM moodle_courses ORDER BY name")
    fun observeAll(): Flow<List<MoodleCourseEntity>>

    @Query("UPDATE moodle_courses SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface AssignmentDao {
    /**
     * フェッチ結果を反映する。新規課題は追加し、既存課題はフェッチ由来の列のみ更新する。
     * ignored / detectedAt / firstSeenAt はユーザー・履歴由来のため保持する。
     */
    @Transaction
    suspend fun upsertAll(assignments: List<AssignmentEntity>) {
        if (assignments.isEmpty()) return
        insertFetched(assignments)
        assignments.forEach { updateFetched(it) }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFetched(assignments: List<AssignmentEntity>)

    /** フェッチ由来の列のみ更新する（ignored / detectedAt / firstSeenAt は変更しない）。 */
    @Query(
        """
        UPDATE assignments SET
            courseId = :courseId,
            courseName = :courseName,
            title = :title,
            url = :url,
            deadline = :deadline,
            deadlineText = :deadlineText,
            submissionStatus = :submissionStatus,
            lifecycleStatus = :lifecycleStatus,
            lastCheckedAt = :lastCheckedAt
        WHERE id = :id
        """,
    )
    suspend fun updateFetched(
        id: String,
        courseId: String,
        courseName: String,
        title: String,
        url: String,
        deadline: Long?,
        deadlineText: String,
        submissionStatus: SubmissionStatus,
        lifecycleStatus: LifecycleStatus,
        lastCheckedAt: Long,
    )

    suspend fun updateFetched(assignment: AssignmentEntity) {
        updateFetched(
            id = assignment.id,
            courseId = assignment.courseId,
            courseName = assignment.courseName,
            title = assignment.title,
            url = assignment.url,
            deadline = assignment.deadline,
            deadlineText = assignment.deadlineText,
            submissionStatus = assignment.submissionStatus,
            lifecycleStatus = assignment.lifecycleStatus,
            lastCheckedAt = assignment.lastCheckedAt,
        )
    }

    @Query("SELECT * FROM assignments WHERE id = :id")
    suspend fun getById(id: String): AssignmentEntity?

    @Query("SELECT * FROM assignments")
    suspend fun getAll(): List<AssignmentEntity>

    @Query(
        """
        SELECT * FROM assignments
        WHERE ignored = 0
          AND courseId NOT IN (SELECT id FROM moodle_courses WHERE enabled = 0)
        ORDER BY CASE WHEN deadline IS NULL THEN 1 ELSE 0 END, deadline ASC
        """,
    )
    fun observeAll(): Flow<List<AssignmentEntity>>

    /** 非表示にした課題（復元用）。 */
    @Query("SELECT * FROM assignments WHERE ignored = 1 ORDER BY CASE WHEN deadline IS NULL THEN 1 ELSE 0 END, deadline ASC")
    fun observeIgnored(): Flow<List<AssignmentEntity>>

    /**
     * ホーム・ウィジェット用: 未提出課題（期限切れも含む）を期日昇順で。
     * 非表示コースの課題は除外する。
     */
    @Query(
        """
        SELECT * FROM assignments
        WHERE ignored = 0
          AND courseId NOT IN (SELECT id FROM moodle_courses WHERE enabled = 0)
          AND lifecycleStatus != 'SUBMITTED'
          AND submissionStatus NOT IN ('SUBMITTED', 'COMPLETED')
        ORDER BY CASE WHEN deadline IS NULL THEN 1 ELSE 0 END, deadline ASC
        """,
    )
    fun observePending(): Flow<List<AssignmentEntity>>

    @Query(
        """
        SELECT * FROM assignments
        WHERE ignored = 0
          AND courseId NOT IN (SELECT id FROM moodle_courses WHERE enabled = 0)
          AND lifecycleStatus != 'SUBMITTED'
          AND submissionStatus NOT IN ('SUBMITTED', 'COMPLETED')
        ORDER BY CASE WHEN deadline IS NULL THEN 1 ELSE 0 END, deadline ASC
        """,
    )
    suspend fun getPending(): List<AssignmentEntity>

    @Query("UPDATE assignments SET ignored = :ignored WHERE id = :id")
    suspend fun setIgnored(id: String, ignored: Boolean)
}

@Dao
interface NotifiedKeyDao {
    @Query("SELECT EXISTS(SELECT 1 FROM notified_keys WHERE `key` = :key)")
    suspend fun exists(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: NotifiedKeyEntity)

    /**
     * 通知の重複防止キーを先取りする。戻り値 -1 は既に登録済み（＝通知抑止）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(entity: NotifiedKeyEntity): Long

    @Query("DELETE FROM notified_keys WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM notified_keys WHERE createdAt < :threshold AND `key` NOT LIKE 'attendance-risk:%'")
    suspend fun purgeOlderThan(threshold: Long)
}

@Dao
interface NotificationHistoryDao {
    @Insert
    suspend fun insert(entity: NotificationHistoryEntity): Long

    @Insert
    suspend fun insertAll(entities: List<NotificationHistoryEntity>)

    @Query("SELECT * FROM notification_history ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<NotificationHistoryEntity>>

    @Query("SELECT COUNT(*) FROM notification_history WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("UPDATE notification_history SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM notification_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notification_history")
    suspend fun clear()
}

@Dao
interface ClassCourseDao {
    @Upsert
    suspend fun upsertAll(courses: List<ClassCourseEntity>)

    @Query("SELECT * FROM class_courses")
    suspend fun getAll(): List<ClassCourseEntity>

    @Query("SELECT * FROM class_courses WHERE code = :code")
    suspend fun getByCode(code: String): ClassCourseEntity?

    @Query("SELECT * FROM class_courses")
    fun observeAll(): Flow<List<ClassCourseEntity>>
}

@Dao
interface TimetableDao {
    @Query("DELETE FROM timetable_cells")
    suspend fun clear()

    @Insert
    suspend fun insertAll(cells: List<TimetableCellEntity>)

    @Query("SELECT * FROM timetable_cells ORDER BY dayOfWeek, period")
    fun observeAll(): Flow<List<TimetableCellEntity>>

    @Query("SELECT * FROM timetable_cells ORDER BY dayOfWeek, period")
    suspend fun getAll(): List<TimetableCellEntity>

    @Query("SELECT * FROM timetable_cells WHERE dayOfWeek = :day AND period = :period")
    suspend fun getForSlot(day: Int, period: Int): List<TimetableCellEntity>
}

@Dao
interface ClassSessionDao {
    @Query("SELECT * FROM class_sessions")
    fun observeAll(): Flow<List<ClassSessionEntity>>

    @Query("DELETE FROM class_sessions")
    suspend fun clear()

    @Insert
    suspend fun insertAll(sessions: List<ClassSessionEntity>)

    @Query("SELECT * FROM class_sessions WHERE courseCode = :code ORDER BY sessionNo")
    suspend fun getForCourse(code: String): List<ClassSessionEntity>

    @Query("SELECT * FROM class_sessions WHERE date >= :from AND date < :to ORDER BY date, period")
    suspend fun getBetween(from: Long, to: Long): List<ClassSessionEntity>

    @Query("SELECT * FROM class_sessions")
    suspend fun getAll(): List<ClassSessionEntity>
}

@Dao
interface LocalAttendanceDao {
    @Query("SELECT * FROM local_attendance")
    fun observeAll(): Flow<List<LocalAttendanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalAttendanceEntity)

    @Query("SELECT COUNT(*) FROM local_attendance WHERE courseCode = :code")
    suspend fun countForCourse(code: String): Int

    @Query("SELECT * FROM local_attendance WHERE courseCode = :code")
    suspend fun getForCourse(code: String): List<LocalAttendanceEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM local_attendance WHERE courseCode = :code AND date = :date AND period = :period)")
    suspend fun exists(code: String, date: Long, period: Int): Boolean
}

@Dao
interface AttendanceOverrideDao {
    @Query("SELECT * FROM attendance_overrides")
    fun observeAll(): Flow<List<AttendanceOverrideEntity>>

    @Upsert
    suspend fun upsert(entity: AttendanceOverrideEntity)

    @Query("DELETE FROM attendance_overrides WHERE courseCode = :code AND date = :date AND period = :period")
    suspend fun delete(code: String, date: Long, period: Int)

    @Query("SELECT * FROM attendance_overrides WHERE courseCode = :code")
    suspend fun getForCourse(code: String): List<AttendanceOverrideEntity>

    @Query("SELECT * FROM attendance_overrides WHERE courseCode = :code AND date = :date AND period = :period")
    suspend fun get(code: String, date: Long, period: Int): AttendanceOverrideEntity?
}
