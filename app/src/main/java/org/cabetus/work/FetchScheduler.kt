package org.cabetus.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.cabetus.data.settings.FetchFrequency
import org.cabetus.data.settings.FetchSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 定期取得・手動取得のスケジューリング。 */
@Singleton
class FetchScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val wm get() = WorkManager.getInstance(context)

    /** 設定に応じて定期取得を（再）登録する。 */
    fun schedulePeriodic(settings: FetchSettings) {
        val interval = when (settings.frequency) {
            FetchFrequency.HOURLY -> Duration.ofHours(1)
            FetchFrequency.DAILY -> Duration.ofDays(1)
        }
        val constraints = Constraints.Builder()
            // モバイルデータ許可時は CONNECTED、不許可でも Worker 側でスキップ判定するため CONNECTED
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val builder = PeriodicWorkRequestBuilder<FetchWorker>(interval)
            .setConstraints(constraints)

        // DAILY のときは指定時刻付近に初回を合わせる
        if (settings.frequency == FetchFrequency.DAILY) {
            builder.setInitialDelay(initialDelayToHour(settings.dailyHour))
        }

        wm.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            builder.build(),
        )
    }

    /** 今すぐ取得（設定画面の「今すぐ取得」やセットアップ完了時）。 */
    fun fetchNow() {
        val request = OneTimeWorkRequestBuilder<FetchWorker>()
            .setInputData(workDataOf(FetchWorker.KEY_FORCE to true))
            .build()
        wm.enqueueUniqueWork(ONESHOT_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelPeriodic() {
        wm.cancelUniqueWork(PERIODIC_NAME)
    }

    private fun initialDelayToHour(hour: Int): Duration {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = now.withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target)
    }

    companion object {
        const val PERIODIC_NAME = "letus_periodic_fetch"
        const val ONESHOT_NAME = "letus_oneshot_fetch"
    }
}
