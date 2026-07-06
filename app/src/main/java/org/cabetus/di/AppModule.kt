package org.cabetus.di

import android.content.Context
import androidx.room.Room
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.ClassSessionDao
import org.cabetus.data.local.LocalAttendanceDao
import org.cabetus.data.local.MoodleCourseDao
import org.cabetus.data.local.NotifiedKeyDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.local.TusDatabase
import org.cabetus.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TusDatabase =
        Room.databaseBuilder(context, TusDatabase::class.java, "tus.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMoodleCourseDao(db: TusDatabase): MoodleCourseDao = db.moodleCourseDao()

    @Provides
    fun provideAssignmentDao(db: TusDatabase): AssignmentDao = db.assignmentDao()

    @Provides
    fun provideNotifiedKeyDao(db: TusDatabase): NotifiedKeyDao = db.notifiedKeyDao()

    @Provides
    fun provideClassCourseDao(db: TusDatabase): ClassCourseDao = db.classCourseDao()

    @Provides
    fun provideTimetableDao(db: TusDatabase): TimetableDao = db.timetableDao()

    @Provides
    fun provideClassSessionDao(db: TusDatabase): ClassSessionDao = db.classSessionDao()

    @Provides
    fun provideLocalAttendanceDao(db: TusDatabase): LocalAttendanceDao = db.localAttendanceDao()

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)
}
