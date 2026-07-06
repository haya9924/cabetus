package org.cabetus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/** テーマ設定モード。DataStore に保存する。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF3A5BA0),
    secondary = Color(0xFF4E6A9B),
    tertiary = Color(0xFF7A5BA0),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFAAC5FF),
    secondary = Color(0xFFB5C7EA),
    tertiary = Color(0xFFD4BBFF),
)

/**
 * カスタムアクセント/背景色から ColorScheme を組み立てる。
 * 指定された色を優先し、未指定の役割はフォールバックスキームを流用する。
 */
private fun customScheme(dark: Boolean, accent: Color?, background: Color?): ColorScheme {
    var s = if (dark) FallbackDark else FallbackLight
    if (accent != null) {
        val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
        s = s.copy(
            primary = accent,
            onPrimary = onAccent,
            secondary = lerp(accent, Color.Gray, 0.3f),
            tertiary = lerp(accent, s.tertiary, 0.5f),
            primaryContainer = lerp(accent, if (dark) Color.Black else Color.White, 0.7f),
            onPrimaryContainer = lerp(accent, if (dark) Color.White else Color.Black, 0.6f),
        )
    }
    if (background != null) {
        val onBg = if (background.luminance() > 0.5f) Color(0xFF1A1A1A) else Color(0xFFECECEC)
        s = s.copy(
            background = background,
            surface = background,
            onBackground = onBg,
            onSurface = onBg,
            surfaceVariant = lerp(background, onBg, 0.08f),
            onSurfaceVariant = lerp(onBg, background, 0.25f),
            surfaceContainerLowest = lerp(background, onBg, 0.02f),
            surfaceContainerLow = lerp(background, onBg, 0.03f),
            surfaceContainer = lerp(background, onBg, 0.05f),
            surfaceContainerHigh = lerp(background, onBg, 0.08f),
            surfaceContainerHighest = lerp(background, onBg, 0.11f),
        )
    }
    return s
}

/**
 * カスタムカラー（設定時）> Material You（動的カラー）> シードカラーのフォールバック
 * の優先順位で ColorScheme を決定する。
 */
@Composable
fun TusTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    customAccent: Int? = null,
    customBackground: Int? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        customAccent != null || customBackground != null ->
            customScheme(dark, customAccent?.let { Color(it) }, customBackground?.let { Color(it) })

        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        dark -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
