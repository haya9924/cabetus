package org.cabetus.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun toSubmission(value: String?): SubmissionStatus? =
        value?.let { SubmissionStatus.valueOf(it) }

    @TypeConverter
    fun fromSubmission(value: SubmissionStatus?): String? = value?.name

    @TypeConverter
    fun toLifecycle(value: String?): LifecycleStatus? =
        value?.let { LifecycleStatus.valueOf(it) }

    @TypeConverter
    fun fromLifecycle(value: LifecycleStatus?): String? = value?.name

    @TypeConverter
    fun toNotificationType(value: String?): NotificationType? =
        value?.let { NotificationType.valueOf(it) }

    @TypeConverter
    fun fromNotificationType(value: NotificationType?): String? = value?.name
}

@Database(
    entities = [
        MoodleCourseEntity::class,
        AssignmentEntity::class,
        NotifiedKeyEntity::class,
        ClassCourseEntity::class,
        TimetableCellEntity::class,
        ClassSessionEntity::class,
        LocalAttendanceEntity::class,
        AttendanceOverrideEntity::class,
        NotificationHistoryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TusDatabase : RoomDatabase() {
    abstract fun moodleCourseDao(): MoodleCourseDao
    abstract fun assignmentDao(): AssignmentDao
    abstract fun notifiedKeyDao(): NotifiedKeyDao
    abstract fun classCourseDao(): ClassCourseDao
    abstract fun timetableDao(): TimetableDao
    abstract fun classSessionDao(): ClassSessionDao
    abstract fun localAttendanceDao(): LocalAttendanceDao
    abstract fun attendanceOverrideDao(): AttendanceOverrideDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao

    companion object {
        /**
         * v1→v2: 手動修正用テーブル attendance_overrides を追加するだけ。
         * 既存データには一切触れないため安全（列は Room の期待スキーマと完全一致させる）。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attendance_overrides` (
                        `courseCode` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `period` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`courseCode`, `date`, `period`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v2→v3: 通知履歴テーブル notification_history を追加するだけ。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notification_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `url` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `read` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
