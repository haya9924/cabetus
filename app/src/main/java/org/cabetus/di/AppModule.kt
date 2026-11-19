package org.cabetus.di

import android.content.Context
import androidx.room.Room
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.AttendanceOverrideDao
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.ClassSessionDao
import org.cabetus.data.local.LocalAttendanceDao
import org.cabetus.data.local.MoodleCourseDao
import org.cabetus.data.local.NotifiedKeyDao
import org.cabetus.data.local.NotificationHistoryDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.local.TusDatabase
import org.cabetus.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TusDatabase =
        Room.databaseBuilder(context, TusDatabase::class.java, "tus.db")
            .addMigrations(TusDatabase.MIGRATION_1_2, TusDatabase.MIGRATION_2_3, TusDatabase.MIGRATION_3_4, TusDatabase.MIGRATION_4_5)
            .build()

    @Provides
    fun provideMoodleCourseDao(db: TusDatabase): MoodleCourseDao = db.moodleCourseDao()

    @Provides
    fun provideAssignmentDao(db: TusDatabase): AssignmentDao = db.assignmentDao()

    @Provides
    fun provideNotifiedKeyDao(db: TusDatabase): NotifiedKeyDao = db.notifiedKeyDao()

    @Provides
    fun provideNotificationHistoryDao(db: TusDatabase): NotificationHistoryDao =
        db.notificationHistoryDao()

    @Provides
    fun provideClassWebAttendanceDao(db: TusDatabase): org.cabetus.data.local.ClassWebAttendanceDao = db.classWebAttendanceDao()

    @Provides
    fun provideMobileAttendanceConfirmationDao(db: TusDatabase): org.cabetus.data.local.MobileAttendanceConfirmationDao = db.mobileAttendanceConfirmationDao()

    @Provides
    fun provideClassCourseDao(db: TusDatabase): ClassCourseDao = db.classCourseDao()

    @Provides
    fun provideTimetableDao(db: TusDatabase): TimetableDao = db.timetableDao()

    @Provides
    fun provideClassSessionDao(db: TusDatabase): ClassSessionDao = db.classSessionDao()

    @Provides
    fun provideLocalAttendanceDao(db: TusDatabase): LocalAttendanceDao = db.localAttendanceDao()

    @Provides
    fun provideAttendanceOverrideDao(db: TusDatabase): AttendanceOverrideDao =
        db.attendanceOverrideDao()

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    /**
     * 現在時刻の取得元。端末のローカル時刻をそのまま使う。
     * テスト・将来の差し替えのため DI 経由で渡す（リポジトリ側で既定値を持たせない）。
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
