package org.cabetus.widget

import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** ウィジェットの配色。 */
enum class WidgetColorScheme(val label: String) {
    DYNAMIC("ダイナミック"),
    LIGHT("ライト"),
    DARK("ダーク"),
    BLUE("ブルー"),
    GREEN("グリーン"),
    PURPLE("パープル"),
}

/** ウィジェット外観設定（widgetIdごとに Glance state に保存）。 */
object WidgetConfig {
    val KEY_OPACITY = intPreferencesKey("widget_opacity") // 0..100
    val KEY_COLOR = stringPreferencesKey("widget_color")

    const val DEFAULT_OPACITY = 80
    val DEFAULT_COLOR = WidgetColorScheme.DYNAMIC

    /** 背景・文字色を返す。DYNAMIC は呼び出し側で GlanceTheme を使うため、ここではライト相当を返す。 */
    fun colors(scheme: WidgetColorScheme): Pair<Color, Color> = when (scheme) {
        WidgetColorScheme.DYNAMIC, WidgetColorScheme.LIGHT -> Color(0xFFFFFFFF) to Color(0xFF1A1A1A)
        WidgetColorScheme.DARK -> Color(0xFF1C1B1F) to Color(0xFFECECEC)
        WidgetColorScheme.BLUE -> Color(0xFF1B3A5B) to Color(0xFFFFFFFF)
        WidgetColorScheme.GREEN -> Color(0xFF1B4332) to Color(0xFFFFFFFF)
        WidgetColorScheme.PURPLE -> Color(0xFF3C2A5B) to Color(0xFFFFFFFF)
    }

    fun accent(scheme: WidgetColorScheme): Color = when (scheme) {
        WidgetColorScheme.DYNAMIC, WidgetColorScheme.LIGHT -> Color(0xFF3A5BA0)
        WidgetColorScheme.DARK -> Color(0xFFAAC5FF)
        WidgetColorScheme.BLUE -> Color(0xFF8FB7FF)
        WidgetColorScheme.GREEN -> Color(0xFF8FE3B0)
        WidgetColorScheme.PURPLE -> Color(0xFFCBB0FF)
    }
}
