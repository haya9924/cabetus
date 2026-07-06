package org.cabetus.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.cabetus.data.ai.AssistantContext
import org.cabetus.data.ai.AssistantRepository
import org.cabetus.data.ai.AssistantResult
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.data.weather.WeatherRepository
import org.cabetus.domain.PeriodTimes
import org.cabetus.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** AIによる「今日のまとめ」を生成して通知するワーカー。 */
@HiltWorker
class DailySummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val assignmentDao: AssignmentDao,
    private val timetableDao: TimetableDao,
    private val classCourseDao: ClassCourseDao,
    private val weatherRepository: WeatherRepository,
    private val assistantRepository: AssistantRepository,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.current()
        if (!settings.notifications.dailySummary || !settings.ai.isConfigured) {
            return Result.success()
        }

        val today = LocalDate.now()
        val dow = today.dayOfWeek.value
        val nameByCode = classCourseDao.getAll().associate { it.code to it.name }
        val classes = timetableDao.getAll()
            .filter { it.dayOfWeek == dow }
            .sortedBy { it.period }
            .map { c ->
                val time = PeriodTimes.of(c.period)?.let { "%02d:%02d".format(it.start.hour, it.start.minute) } ?: ""
                val name = nameByCode[c.courseCode]?.ifBlank { c.courseName } ?: c.courseName
                "${c.period}限 $time $name${c.room?.let { "（$it）" } ?: ""}"
            }
        val pending = assignmentDao.getPending().take(8).map {
            "${it.courseName} ${it.title}（${NotificationHelper.formatDeadline(it.deadline)}）"
        }
        val weather = weatherRepository.getWeather(settings.campus)
            ?.let { "${it.label} ${it.currentTemp?.toInt() ?: ""}℃" }

        val context = AssistantContext(
            dateLabel = today.format(DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)),
            todayClasses = classes,
            pendingAssignments = pending,
            weather = weather,
        )

        return when (val result = assistantRepository.generate(settings.ai, context)) {
            is AssistantResult.Success -> {
                settingsRepository.setDailySummaryCache(today.toString(), result.text)
                notificationHelper.notifyDailySummary(result.text)
                Result.success()
            }

            is AssistantResult.Error -> Result.retry()
            AssistantResult.NotConfigured -> Result.success()
        }
    }

    companion object {
        const val NAME = "daily_summary_work"
    }
}
