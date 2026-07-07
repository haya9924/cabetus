package org.cabetus.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 全ウィジェットを再描画する。取得完了時に FetchWorker から呼ばれる。 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun updateAll() {
        val manager = GlanceAppWidgetManager(context)
        // 配置済みウィジェットが無ければ何もしない。各ウィジェットは独立して runCatching。
        runCatching {
            if (manager.getGlanceIds(AssignmentWidget::class.java).isNotEmpty()) {
                AssignmentWidget().updateAll(context)
            }
        }
        runCatching {
            if (manager.getGlanceIds(NextClassWidget::class.java).isNotEmpty()) {
                NextClassWidget().updateAll(context)
            }
        }
    }
}
