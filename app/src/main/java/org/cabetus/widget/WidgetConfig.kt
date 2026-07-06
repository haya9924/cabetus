package org.cabetus.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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

    /**
     * 背景・文字・アクセント色を解決する。DYNAMIC かつ Android 12+ では端末の
     * ダイナミックカラー（Material You）から実際の色を取り出す。
     * 色は描画時に解決されるため、ライト/ダーク切替は次回のウィジェット更新で反映される。
     */
    fun resolveColors(context: Context, scheme: WidgetColorScheme): Triple<Color, Color, Color> {
        if (scheme == WidgetColorScheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val night = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val cs = if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            return Triple(cs.surface, cs.onSurface, cs.primary)
        }
        val (bg, fg) = colors(scheme)
        return Triple(bg, fg, accent(scheme))
    }
}
