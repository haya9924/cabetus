package org.cabetus.widget

import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.notification.AlarmScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Glance ウィジェットから Room へアクセスするための Hilt EntryPoint。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun assignmentDao(): AssignmentDao
    fun timetableDao(): TimetableDao
    fun alarmScheduler(): AlarmScheduler
}
