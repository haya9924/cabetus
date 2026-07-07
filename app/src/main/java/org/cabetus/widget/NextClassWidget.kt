package org.cabetus.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import org.cabetus.domain.NextClassInfo
import org.cabetus.domain.NextClassResolver
import org.cabetus.ui.MainActivity

/**
 * 次の授業（授業中ならその授業）の授業名・教室・時間を表示するホーム画面ウィジェット。
 * 外観設定（不透明度・配色）は課題ウィジェットと共通の Glance state キーを使う。
 */
class NextClassWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // DB アクセスは例外を投げ得るため握りつぶす。失敗（例外）と「授業なし」（成功だが null）を区別する。
        val result: Result<NextClassInfo?> = runCatching {
            val cells = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            ).timetableDao().getAll()
            NextClassResolver.resolve(cells)
        }
        val failed = result.isFailure
        val info = result.getOrNull()

        provideContent {
            val prefs: Preferences = currentState()
            val opacity = (prefs[WidgetConfig.KEY_OPACITY] ?: WidgetConfig.DEFAULT_OPACITY)
                .coerceIn(0, 100)
            val scheme = runCatching {
                WidgetColorScheme.valueOf(
                    prefs[WidgetConfig.KEY_COLOR] ?: WidgetConfig.DEFAULT_COLOR.name,
                )
            }.getOrDefault(WidgetConfig.DEFAULT_COLOR)

            val (bg, fg, accent) = WidgetConfig.resolveColors(context, scheme)
            val bgWithOpacity = bg.copy(alpha = opacity / 100f)

            WidgetBody(context, info, failed, bgWithOpacity, fg, accent)
        }
    }

    @androidx.compose.runtime.Composable
    private fun WidgetBody(
        context: Context,
        info: NextClassInfo?,
        failed: Boolean,
        bg: Color,
        fg: Color,
        accent: Color,
    ) {
        val openApp = actionStartActivity(
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_NAVIGATE, MainActivity.NAV_TIMETABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(16.dp)
                .background(ColorProvider(bg))
                .padding(12.dp)
                .clickable(openApp),
        ) {
            if (failed) {
                Text(
                    "読み込みに失敗しました",
                    style = TextStyle(color = ColorProvider(fg), fontWeight = FontWeight.Bold),
                )
                return@Column
            }
            if (info == null) {
                Text(
                    "次の授業",
                    style = TextStyle(color = ColorProvider(fg), fontWeight = FontWeight.Bold),
                )
                // Glance の Spacer はサイズ未指定だと展開して後続要素を潰すため、必ず明示サイズを与える
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    "今後の授業はありません",
                    style = TextStyle(color = ColorProvider(fg)),
                )
                return@Column
            }
            Text(
                if (info.isOngoing) "授業中" else "次の授業",
                style = TextStyle(color = ColorProvider(accent), fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                info.cell.courseName.ifBlank { info.cell.courseCode },
                maxLines = 2,
                style = TextStyle(color = ColorProvider(fg), fontWeight = FontWeight.Bold),
            )
            Text(
                "%d限 %02d:%02d〜%02d:%02d".format(
                    info.period,
                    info.start.hour, info.start.minute,
                    info.end.hour, info.end.minute,
                ),
                style = TextStyle(color = ColorProvider(fg)),
            )
            Text(
                if (info.cell.isOnline) "オンライン" else (info.cell.room ?: ""),
                maxLines = 1,
                style = TextStyle(color = ColorProvider(accent)),
            )
        }
    }
}
