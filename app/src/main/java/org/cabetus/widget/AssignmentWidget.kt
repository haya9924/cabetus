package org.cabetus.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.notification.NotificationHelper
import org.cabetus.ui.MainActivity
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 残っている課題を期日昇順で表示するホーム画面ウィジェット。
 * 背景の不透明度・配色は widgetId ごとに Glance state に保存され、設定Activityから変更できる。
 */
class AssignmentWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // DB アクセスは例外を投げ得る（Hilt/Room 初期化等）。失敗しても真っ白に
        // ならないよう握りつぶし、フォールバック表示に切り替える。
        val pending: List<AssignmentEntity>? = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            ).assignmentDao().getPending()
        }.getOrNull()

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

            WidgetBody(context, pending, bgWithOpacity, fg, accent)
        }
    }

    private companion object {
        val fmt: DateTimeFormatter =
            DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"))
    }

    @androidx.compose.runtime.Composable
    private fun WidgetBody(
        context: Context,
        pending: List<AssignmentEntity>?,
        bg: Color,
        fg: Color,
        accent: Color,
    ) {
        val openApp = actionStartActivity(
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_NAVIGATE, MainActivity.NAV_ASSIGNMENTS)
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
            if (pending == null) {
                Text(
                    "読み込みに失敗しました",
                    style = TextStyle(color = ColorProvider(fg), fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.padding(2.dp))
                Text(
                    "タップしてアプリを開く",
                    style = TextStyle(color = ColorProvider(accent)),
                )
                return@Column
            }
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    "課題の期日",
                    style = TextStyle(color = ColorProvider(fg), fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    "${pending.size}件",
                    style = TextStyle(color = ColorProvider(accent), fontWeight = FontWeight.Bold),
                )
            }
            Spacer(GlanceModifier.padding(4.dp))
            if (pending.isEmpty()) {
                Text(
                    "未提出の課題はありません",
                    style = TextStyle(color = ColorProvider(fg)),
                )
            } else {
                LazyColumn {
                    items(pending) { a ->
                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable(
                                    actionStartActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(a.url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    ),
                                ),
                        ) {
                            Text(
                                a.title,
                                maxLines = 1,
                                style = TextStyle(color = ColorProvider(fg), fontWeight = FontWeight.Medium),
                            )
                            Text(
                                "${a.courseName} ・ ${a.deadline?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: "期限なし"}",
                                maxLines = 1,
                                style = TextStyle(color = ColorProvider(accent)),
                            )
                        }
                    }
                }
            }
        }
    }
}
