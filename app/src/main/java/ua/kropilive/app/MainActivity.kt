package ua.kropilive.app

import android.os.Bundle
import android.os.Build
import android.graphics.Color as AndroidColor
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import ua.kropilive.app.data.repository.SettingsRepository
import ua.kropilive.app.navigation.MainNavigation
import ua.kropilive.app.ui.theme.KropiLiveTheme
import ua.kropilive.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current.applicationContext

            val settingsRepository = remember(context) {
                SettingsRepository(context)
            }

            val themeMode by settingsRepository.themeMode
                .collectAsState(initial = ThemeMode.SYSTEM)

            val coroutineScope = rememberCoroutineScope()

            KropiLiveTheme(themeMode = themeMode) {
                val colors = MaterialTheme.colorScheme
                val darkIcons = when (themeMode) {
                    ThemeMode.SYSTEM -> (resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                            android.content.res.Configuration.UI_MODE_NIGHT_YES
                    ThemeMode.LIGHT -> true
                    ThemeMode.DARK -> false
                }

                SideEffect {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = colors.background.toArgb()
                    @Suppress("DEPRECATION")
                    // Three-button mode: same background as Compose NavigationBar.
                    // Gesture mode: transparent system area, with the app bar underneath.
                    val insets = ViewCompat.getRootWindowInsets(window.decorView)
                    val hasTappableNavigation = insets?.getInsets(
                        WindowInsetsCompat.Type.tappableElement()
                    )?.bottom?.let { it > 0 } ?: true
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = if (hasTappableNavigation) {
                        colors.surfaceContainer.toArgb()
                    } else {
                        AndroidColor.TRANSPARENT
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = darkIcons
                        isAppearanceLightNavigationBars = darkIcons
                    }
                }

                MainNavigation(
                    themeMode = themeMode,
                    onThemeChange = { selectedMode ->
                        coroutineScope.launch {
                            settingsRepository.saveThemeMode(
                                selectedMode
                            )
                        }
                    }
                )
            }
        }
    }
}
