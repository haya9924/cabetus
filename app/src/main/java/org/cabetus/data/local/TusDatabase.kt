package org.cabetus.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

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
    ],
    version = 1,
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
}
