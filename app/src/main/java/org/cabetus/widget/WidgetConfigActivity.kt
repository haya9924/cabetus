package org.cabetus.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import org.cabetus.ui.theme.TusTheme
import kotlinx.coroutines.launch

/**
 * ウィジェット配置時の外観設定（不透明度・配色）。設定は widgetId ごとに Glance state へ保存。
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 途中でキャンセルされた場合に備え、まず CANCELED を設定
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            TusTheme {
                Surface(Modifier.fillMaxSize()) {
                    var opacity by remember { mutableFloatStateOf(WidgetConfig.DEFAULT_OPACITY.toFloat()) }
                    var scheme by remember { mutableStateOf(WidgetConfig.DEFAULT_COLOR) }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "ウィジェットの外観",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )

                        Text("背景の不透明度: ${opacity.toInt()}%")
                        Slider(
                            value = opacity,
                            onValueChange = { opacity = it },
                            valueRange = 0f..100f,
                        )

                        Text("配色", fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WidgetColorScheme.entries.forEach { s ->
                                FilterChip(
                                    selected = scheme == s,
                                    onClick = { scheme = s },
                                    label = { Text(s.label) },
                                )
                            }
                        }

                        Button(
                            onClick = { save(opacity.toInt(), scheme) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("この設定で追加") }
                    }
                }
            }
        }
    }

    private fun save(opacity: Int, scheme: WidgetColorScheme) {
        lifecycleScope.launch {
            val manager = androidx.glance.appwidget.GlanceAppWidgetManager(this@WidgetConfigActivity)
            val glanceId = manager.getGlanceIdBy(appWidgetId)
            updateAppWidgetState(
                this@WidgetConfigActivity,
                PreferencesGlanceStateDefinition,
                glanceId,
            ) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[WidgetConfig.KEY_OPACITY] = opacity
                    this[WidgetConfig.KEY_COLOR] = scheme.name
                }
            }
            // どちらのウィジェットの設定かを判定して正しい方を更新する
            if (glanceId in manager.getGlanceIds(NextClassWidget::class.java)) {
                NextClassWidget().update(this@WidgetConfigActivity, glanceId)
            } else {
                AssignmentWidget().update(this@WidgetConfigActivity, glanceId)
            }

            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }
}
