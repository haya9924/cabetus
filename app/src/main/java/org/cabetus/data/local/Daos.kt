package org.cabetus.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MoodleCourseDao {
    @Upsert
    suspend fun upsertAll(courses: List<MoodleCourseEntity>)

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
    @Upsert
    suspend fun upsertAll(assignments: List<AssignmentEntity>)

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
     * ホーム・ウィジェット用: 未提出かつ期限切れでない課題を期日昇順で。
     * 非表示コースの課題は除外する。
     */
    @Query(
        """
        SELECT * FROM assignments
        WHERE ignored = 0
          AND courseId NOT IN (SELECT id FROM moodle_courses WHERE enabled = 0)
          AND lifecycleStatus IN ('ACTIVE', 'BEFORE_START')
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
          AND lifecycleStatus IN ('ACTIVE', 'BEFORE_START')
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

    @Query("DELETE FROM notified_keys WHERE createdAt < :threshold")
    suspend fun purgeOlderThan(threshold: Long)
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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalAttendanceEntity)

    @Query("SELECT COUNT(*) FROM local_attendance WHERE courseCode = :code")
    suspend fun countForCourse(code: String): Int

    @Query("SELECT * FROM local_attendance WHERE courseCode = :code")
    suspend fun getForCourse(code: String): List<LocalAttendanceEntity>
}
