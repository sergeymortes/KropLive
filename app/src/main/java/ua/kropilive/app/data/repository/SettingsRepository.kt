package ua.kropilive.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ua.kropilive.app.ui.theme.ThemeMode

private val Context.settingsDataStore by preferencesDataStore(
    name = "kropilive_settings"
)

class SettingsRepository(
    private val context: Context
) {
    companion object {
        private val THEME_KEY = stringPreferencesKey("theme_mode")
    }

    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data.map { preferences ->
            val savedValue = preferences[THEME_KEY]

            ThemeMode.entries.firstOrNull {
                it.name == savedValue
            } ?: ThemeMode.SYSTEM
        }

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[THEME_KEY] = mode.name
        }
    }
}
