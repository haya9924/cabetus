package org.cabetus.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.work.FetchScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 端末再起動・アプリ更新後に、授業開始アラーム・デイリー通知・定期取得を再登録する。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    @Inject
    lateinit var fetchScheduler: FetchScheduler

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = settingsRepository.current()
                if (!s.setupCompleted) return@launch
                fetchScheduler.schedulePeriodic(s.fetch)
                alarmScheduler.rescheduleClassAlarms()
                if (s.notifications.dailySummary) {
                    alarmScheduler.scheduleDailySummary(
                        s.notifications.dailySummaryHour,
                        s.notifications.dailySummaryMinute,
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
