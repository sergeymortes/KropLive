
package ua.kropilive.app.background

import android.content.Context

object NotificationPreferences {

    private const val PREFS = "kropilive_notifications_v1"

    fun get(context: Context, key: String): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, true)
    }

    fun set(context: Context, key: String, enabled: Boolean) {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        preferences.edit()
            .putBoolean(key, enabled)
            .commit()
    }
}
