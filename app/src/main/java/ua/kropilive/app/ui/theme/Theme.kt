package ua.kropilive.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * Палитра воздушной опасности.
 *
 * background — фон всей карточки.
 * border     — обводка карточки.
 * accent     — значок и акцентные элементы.
 * text       — основной текст.
 * secondaryText — пояснения.
 */
@Immutable
data class AirAlertStatusColors(
    val background: Color,
    val border: Color,
    val accent: Color,
    val text: Color,
    val secondaryText: Color
)

/**
 * Набор цветов для четырёх состояний.
 *
 * GREEN   — нет подтверждённой опасности.
 * YELLOW  — повышенная опасность.
 * RED     — воздушная тревога.
 * UNKNOWN — нет актуальных данных.
 */
@Immutable
data class AirAlertPalette(
    val green: AirAlertStatusColors,
    val yellow: AirAlertStatusColors,
    val red: AirAlertStatusColors,
    val unknown: AirAlertStatusColors
)

private val LightAirAlertPalette = AirAlertPalette(
    green = AirAlertStatusColors(
        background = Color(0xFFDCFCE7),
        border = Color(0xFF86EFAC),
        accent = Color(0xFF15803D),
        text = Color(0xFF14532D),
        secondaryText = Color(0xFF166534)
    ),
    yellow = AirAlertStatusColors(
        background = Color(0xFFFEF3C7),
        border = Color(0xFFFCD34D),
        accent = Color(0xFFB45309),
        text = Color(0xFF78350F),
        secondaryText = Color(0xFF92400E)
    ),
    red = AirAlertStatusColors(
        background = Color(0xFFFEE2E2),
        border = Color(0xFFFCA5A5),
        accent = Color(0xFFDC2626),
        text = Color(0xFF7F1D1D),
        secondaryText = Color(0xFF991B1B)
    ),
    unknown = AirAlertStatusColors(
        background = Color(0xFFF1F5F9),
        border = Color(0xFFCBD5E1),
        accent = Color(0xFF64748B),
        text = Color(0xFF334155),
        secondaryText = Color(0xFF475569)
    )
)

private val DarkAirAlertPalette = AirAlertPalette(
    green = AirAlertStatusColors(
        background = Color(0xFF123524),
        border = Color(0xFF166534),
        accent = Color(0xFF4ADE80),
        text = Color(0xFFDCFCE7),
        secondaryText = Color(0xFFBBF7D0)
    ),
    yellow = AirAlertStatusColors(
        background = Color(0xFF42300D),
        border = Color(0xFF92400E),
        accent = Color(0xFFFACC15),
        text = Color(0xFFFEF9C3),
        secondaryText = Color(0xFFFDE68A)
    ),
    red = AirAlertStatusColors(
        background = Color(0xFF451A1A),
        border = Color(0xFF991B1B),
        accent = Color(0xFFF87171),
        text = Color(0xFFFEE2E2),
        secondaryText = Color(0xFFFECACA)
    ),
    unknown = AirAlertStatusColors(
        background = Color(0xFF1E293B),
        border = Color(0xFF475569),
        accent = Color(0xFF94A3B8),
        text = Color(0xFFF1F5F9),
        secondaryText = Color(0xFFCBD5E1)
    )
)

private val LocalAirAlertPalette =
    staticCompositionLocalOf {
        LightAirAlertPalette
    }

/**
 * Доступ к палитре из любого Compose-экрана:
 *
 * val colors = KropiLiveStatusTheme.airAlerts.red
 */
object KropiLiveStatusTheme {

    val airAlerts: AirAlertPalette
        @Composable
        get() = LocalAirAlertPalette.current
}

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkBackground,

    background = DarkBackground,
    onBackground = DarkText,

    surface = DarkSurface,
    onSurface = DarkText,

    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkSecondaryText,

    secondary = DarkSecondaryText,
    onSecondary = DarkBackground,

    error = StatusDanger
)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightSurface,

    background = LightBackground,
    onBackground = LightText,

    surface = LightSurface,
    onSurface = LightText,

    surfaceVariant = LightSurface,
    onSurfaceVariant = LightSecondaryText,

    secondary = LightSecondaryText,
    onSecondary = LightSurface,

    error = StatusDanger
)

@Composable
fun KropiLiveTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val appColors =
        if (isDark) DarkColors else LightColors

    val airAlertColors =
        if (isDark) DarkAirAlertPalette
        else LightAirAlertPalette

    androidx.compose.runtime.CompositionLocalProvider(
        LocalAirAlertPalette provides airAlertColors
    ) {
        MaterialTheme(
            colorScheme = appColors,
            content = content
        )
    }
}
