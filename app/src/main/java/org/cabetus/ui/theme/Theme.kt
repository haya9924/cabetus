package org.cabetus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
 * Material You（動的カラー）を優先し、Android 12 未満やユーザーが無効化した場合は
 * シードカラーの固定スキームにフォールバックする。
 */
@Composable
fun TusTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
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
