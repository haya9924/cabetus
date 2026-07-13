package org.cabetus.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.cabetus.data.letus.FetchResult
import org.cabetus.data.letus.LetusRepository
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.notification.NotificationHelper
import org.cabetus.widget.WidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * LETUS からの課題取得ワーカー。定期実行にも手動実行にも使う。
 *
 * モバイルデータ設定の扱い:
 *  - 従量制ネットワーク && モバイルデータ不許可 → スキップカウンタ++で終了
 *  - ただしスキップ回数が forceFetchAfterSkips 以上なら強制実行してカウンタをリセット
 */
@HiltWorker
class FetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val letusRepository: LetusRepository,
    private val settingsRepository: SettingsRepository,
    private val notificationHelper: NotificationHelper,
    private val widgetUpdater: WidgetUpdater,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.current()
        val fetch = settings.fetch
        val forced = inputData.getBoolean(KEY_FORCE, false)

        // モバイルデータ制御（手動 forced のときは無視）
        if (!forced) {
            val metered = NetworkUtil.isActiveNetworkMetered(applicationContext)
            if (metered && !fetch.allowMobileData) {
                val nextCount = settings.skipCounter + 1
                if (nextCount < fetch.forceFetchAfterSkips) {
                    settingsRepository.setSkipCounter(nextCount)
                    settingsRepository.setLastFetch(
                        "モバイルデータのためスキップ（$nextCount/${fetch.forceFetchAfterSkips}）",
                        System.currentTimeMillis(),
                    )
                    return Result.success()
                }
                // 強制実行してカウンタリセット
                settingsRepository.setSkipCounter(0)
            }
        }

        // 「更新中」通知を表示し、終了・例外・retry いずれでも必ず消す
        notificationHelper.notifyFetchProgress()
        try {
            val result = letusRepository.fetchAll()
            val now = System.currentTimeMillis()

            return when (result) {
                is FetchResult.Success -> {
                    if (settings.notifications.newAssignment) {
                        notificationHelper.notifyNewAssignments(result.newAssignments)
                    }
                    if (settings.notifications.letusChange) {
                        notificationHelper.notifyAssignmentChanges(result.changed)
                    }
                    if (settings.notifications.deadline) {
                        notificationHelper.notifyDeadlineWarnings(result.dueSoon)
                    }
                    settingsRepository.setLoggedIn(true)
                    settingsRepository.setLastFetch("取得成功: ${result.detected}件", now)
                    widgetUpdater.updateAll()
                    Result.success()
                }

                FetchResult.LoginRequired -> {
                    settingsRepository.setLoggedIn(false)
                    if (settings.notifications.fetchFailure) {
                        notificationHelper.notifyFetchFailure(loginRequired = true)
                    }
                    settingsRepository.setLastFetch("要再ログイン", now)
                    Result.success()
                }

                is FetchResult.NetworkError -> {
                    if (settings.notifications.fetchFailure) {
                        notificationHelper.notifyFetchFailure(loginRequired = false)
                    }
                    settingsRepository.setLastFetch("失敗: ${result.message}", now)
                    Result.retry()
                }
            }
        } finally {
            notificationHelper.cancelFetchProgress()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}
