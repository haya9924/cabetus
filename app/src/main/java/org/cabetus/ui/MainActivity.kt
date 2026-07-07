package org.cabetus.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cabetus.data.settings.AppSettings
import org.cabetus.ui.nav.Routes
import org.cabetus.ui.nav.TopTab
import org.cabetus.ui.setup.SetupScreen
import org.cabetus.ui.theme.TusTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var pendingNavRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val current = settings ?: AppSettings()
            TusTheme(
                themeMode = current.themeMode,
                dynamicColor = current.dynamicColor,
                customAccent = current.customAccent,
                customBackground = current.customBackground,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (settings != null && !current.setupCompleted) {
                        SetupScreen(onFinished = { /* recompose により本体へ */ })
                    } else {
                        val route = remember(pendingNavRoute) { pendingNavRoute }
                        MainScaffold(
                            pendingRoute = route,
                            onPendingConsumed = { pendingNavRoute = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_NAVIGATE)) {
            NAV_LOGIN -> pendingNavRoute = Routes.LOGIN
            NAV_SETTINGS -> pendingNavRoute = Routes.SETTINGS
            NAV_ASSIGNMENTS -> pendingNavRoute = TopTab.ASSIGNMENTS.route
            NAV_TIMETABLE -> pendingNavRoute = TopTab.TIMETABLE.route
        }
    }

    companion object {
        const val EXTRA_NAVIGATE = "navigate"
        const val NAV_LOGIN = "login"
        const val NAV_SETTINGS = "settings"
        const val NAV_ASSIGNMENTS = "assignments"
        const val NAV_TIMETABLE = "timetable"
    }
}
