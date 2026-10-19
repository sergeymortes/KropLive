package ua.kropilive.app.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ua.kropilive.app.data.repository.AirAlertLevel
import ua.kropilive.app.data.repository.AirAlertState

/**
 * Сповіщення про ЗМІНИ інформаційного рівня NEPTUN.
 * NEPTUN RED/GREEN не підтверджують офіційне оголошення/відбій тривоги.
 * UNKNOWN не змінює попередній підтверджений стан.
 */
object AlertNotifier {
    private const val PREFS = "air_alert_confirmed_state_v3"
    private const val REQUIRED_CONFIRMATIONS = 2

    // Нові ID каналів: Android не дозволяє програмно змінити важливість
    // та вібрацію вже створеного каналу.
    private const val CHANNEL_RED = "neptun_red_heads_up_v1"
    private const val CHANNEL_YELLOW = "neptun_yellow_heads_up_v1"
    private const val CHANNEL_GREEN = "neptun_green_heads_up_v2"

    private const val NOTIFICATION_ID = 3101

    @Synchronized
    fun check(context: Context, state: AirAlertState?) {
        if (state == null || state.level == AirAlertLevel.UNKNOWN) return
        if (state.district.isBlank()) return
        if (state.level == AirAlertLevel.RED && !state.active) return

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val district = state.district.trim()
        val statusKey = "status_$district"
        val candidateKey = "candidate_$district"
        val countKey = "candidate_count_$district"
        val current = state.level.name
        val previous = prefs.getString(statusKey, null)

        if (previous == null) {
            // Перше спостереження GREEN/YELLOW задає базову лінію без сповіщення.
            // Перше RED повідомляємо одразу, якщо це дозволено системою.
            if (state.level == AirAlertLevel.RED) {
                if (!notifyIfAllowed(appContext, state, null)) return
            }
            prefs.edit().putString(statusKey, current)
                .remove(candidateKey).remove(countKey).apply()
            return
        }

        if (previous == current) {
            prefs.edit().remove(candidateKey).remove(countKey).apply()
            return
        }

        val previousLevel = runCatching { AirAlertLevel.valueOf(previous) }.getOrNull()

        if (state.level == AirAlertLevel.RED) {
            if (!notifyIfAllowed(appContext, state, previousLevel)) return
            prefs.edit().putString(statusKey, current)
                .remove(candidateKey).remove(countKey).apply()
            return
        }

        // YELLOW/GREEN: два послідовних підтвердження зміни.
        val candidate = prefs.getString(candidateKey, null)
        val count = if (candidate == current) prefs.getInt(countKey, 0) + 1 else 1
        if (count < REQUIRED_CONFIRMATIONS) {
            prefs.edit().putString(candidateKey, current)
                .putInt(countKey, count).apply()
            return
        }

        if (!notifyIfAllowed(appContext, state, previousLevel)) return
        prefs.edit().putString(statusKey, current)
            .remove(candidateKey).remove(countKey).apply()
    }

    /** true — сповіщення відправлено або користувач вимкнув їх у застосунку. */
    private fun notifyIfAllowed(
        context: Context,
        state: AirAlertState,
        previousLevel: AirAlertLevel?
    ): Boolean {
        if (!NotificationPreferences.get(context, "air")) return true
        if (!hasNotificationPermission(context)) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return sendNotification(context, state, previousLevel)
    }

    private fun sendNotification(
        context: Context,
        state: AirAlertState,
        previousLevel: AirAlertLevel?
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannels(manager)

        val title: String
        val message: String
        val channelId: String
        val color: Int
        val priority: Int

        when (state.level) {
            AirAlertLevel.RED -> {
                title = "🔴 NEPTUN: червоний рівень"
                message = "${state.district}: повідомлено про червоний рівень небезпеки. Перевірте офіційні сповіщення про тривогу."
                channelId = CHANNEL_RED
                color = android.graphics.Color.RED
                priority = NotificationCompat.PRIORITY_MAX
            }
            AirAlertLevel.YELLOW -> {
                title = "🟡 NEPTUN: жовтий рівень"
                message = "${state.district}: зафіксовано інформацію про повітряні загрози. Перевірте офіційні сповіщення."
                channelId = CHANNEL_YELLOW
                color = android.graphics.Color.rgb(234, 179, 8)
                priority = NotificationCompat.PRIORITY_HIGH
            }
            AirAlertLevel.GREEN -> {
                title = "🟢 NEPTUN: загроз не виявлено"
                message = if (previousLevel == AirAlertLevel.RED || previousLevel == AirAlertLevel.YELLOW) {
                    "${state.district}: попередні загрози більше не відображаються в NEPTUN. Це не офіційний відбій тривоги."
                } else {
                    "${state.district}: у свіжих даних NEPTUN активних загроз не виявлено. Це не офіційний відбій."
                }
                channelId = CHANNEL_GREEN
                color = android.graphics.Color.rgb(34, 197, 94)
                priority = NotificationCompat.PRIORITY_HIGH
            }
            AirAlertLevel.UNKNOWN -> return false
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(color)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createOpenAppIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        return try {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun createChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val red = NotificationChannel(
            CHANNEL_RED, "NEPTUN · Червоний рівень", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Термінові інформаційні повідомлення NEPTUN про червоний рівень"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 450, 180, 450, 180, 700)
            enableLights(true)
            lightColor = android.graphics.Color.RED
        }
        val yellow = NotificationChannel(
            CHANNEL_YELLOW, "NEPTUN · Жовтий рівень", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Інформаційні повідомлення NEPTUN про жовтий рівень"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250)
        }
        val green = NotificationChannel(
            CHANNEL_GREEN, "NEPTUN · Зміни обстановки", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Повідомлення про зникнення загроз у NEPTUN (не офіційний відбій)"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180)
        }
        manager.createNotificationChannel(red)
        manager.createNotificationChannel(yellow)
        manager.createNotificationChannel(green)
    }

    private fun createOpenAppIntent(context: Context): PendingIntent? {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 3102, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
