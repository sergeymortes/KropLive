package ua.kropilive.app.background

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class ElectricityAlarmReceiver : BroadcastReceiver() {

    companion object {

        const val ACTION_ELECTRICITY_ALARM =
            "ua.kropilive.app.ELECTRICITY_ALARM"

        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_QUEUE = "queue"
        const val EXTRA_START_TIME = "start_time"
        const val EXTRA_END_TIME = "end_time"
        const val EXTRA_TRIGGER_AT = "trigger_at"

        const val TYPE_OUTAGE = "outage"
        const val TYPE_OUTAGE_STARTED = "outage_started"
        const val TYPE_RESTORATION = "restoration"

        private const val TYPE_TEST = "test"

        const val CHANNEL_ID = "electricity_alerts"

        private const val TEST_CHANNEL_ID =
            "electricity_test_alerts"

        private const val TEST_REQUEST_CODE = 918273

        fun scheduleTest(context: Context): Boolean {

            val appContext = context.applicationContext

            val manager = appContext.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

            val triggerAt =
                System.currentTimeMillis() + 2 * 60_000L

            val intent = Intent(
                appContext,
                ElectricityAlarmReceiver::class.java
            ).apply {
                action = ACTION_ELECTRICITY_ALARM

                putExtra(
                    EXTRA_EVENT_ID,
                    "electricity_test"
                )

                putExtra(
                    EXTRA_EVENT_TYPE,
                    TYPE_TEST
                )

                putExtra(
                    EXTRA_TRIGGER_AT,
                    triggerAt
                )
            }

            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                TEST_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

            return try {

                val exactAllowed =
                    Build.VERSION.SDK_INT <
                            Build.VERSION_CODES.S ||
                            manager.canScheduleExactAlarms()

                if (exactAllowed) {
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }

                true

            } catch (_: SecurityException) {
                pendingIntent.cancel()
                false
            } catch (_: IllegalArgumentException) {
                pendingIntent.cancel()
                false
            }
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        if (intent.action != ACTION_ELECTRICITY_ALARM) {
            return
        }

        if (!hasNotificationPermission(context)) {
            return
        }

        val eventId =
            intent.getStringExtra(EXTRA_EVENT_ID)
                ?: return

        val eventType =
            intent.getStringExtra(EXTRA_EVENT_TYPE)
                ?: return

        val triggerAt = intent.getLongExtra(
            EXTRA_TRIGGER_AT,
            -1L
        )

        if (triggerAt <= 0L) return

        val now = System.currentTimeMillis()

        if (now < triggerAt - 60_000L) return
        if (now > triggerAt + 5 * 60_000L) return

        if (eventType == TYPE_TEST) {
            showTestNotification(context)
            return
        }

        if (
            eventType != TYPE_OUTAGE &&
            eventType != TYPE_OUTAGE_STARTED &&
            eventType != TYPE_RESTORATION
        ) {
            return
        }

        val preferenceKey = when (eventType) {
            TYPE_OUTAGE, TYPE_OUTAGE_STARTED -> "power_off"
            TYPE_RESTORATION -> "power_on"
            else -> return
        }

        if (
            !NotificationPreferences.get(
                context,
                preferenceKey
            )
        ) {
            return
        }

        val queue =
            intent.getStringExtra(EXTRA_QUEUE)
                ?.takeIf { it.isNotBlank() }
                ?: return

        val startTime =
            intent.getStringExtra(EXTRA_START_TIME)
                ?: return

        val endTime =
            intent.getStringExtra(EXTRA_END_TIME)
                ?: return

        val schedulePrefs =
            context.getSharedPreferences(
                "electricity_alarm_schedule_v1",
                Context.MODE_PRIVATE
            )

        if (
            !schedulePrefs.getBoolean(
                eventId,
                false
            )
        ) {
            return
        }

        val sentPrefs =
            context.getSharedPreferences(
                "electricity_alarm_sent_v1",
                Context.MODE_PRIVATE
            )

        if (
            sentPrefs.getBoolean(
                eventId,
                false
            )
        ) {
            return
        }

        createChannel(
            context = context,
            channelId = CHANNEL_ID,
            channelName =
                "Світло · Попередження за графіком",
            description =
                "Попередження про заплановані відключення " +
                        "та очікуване відновлення електропостачання"
        )

        val isOutage = eventType == TYPE_OUTAGE || eventType == TYPE_OUTAGE_STARTED
        val isOutageStarted = eventType == TYPE_OUTAGE_STARTED

        val title = if (isOutageStarted) {
            "Світло · Почалося відключення"
        } else if (isOutage) {
            "Світло · Майбутнє відключення"
        } else {
            "Світло · Очікуване відновлення"
        }

        val message = if (isOutageStarted) {
            "За графіком почалося відключення світла за вашою адресою."
        } else if (isOutage) {
            "Через 15 хвилин за вашою адресою " +
                    "заплановане відключення електроенергії."
        } else {
            "Через 15 хвилин за графіком очікується " +
                    "відновлення електропостачання за вашою адресою."
        }

        val details = if (isOutage) {

            if (
                endTime == "не визначено" ||
                endTime.isBlank()
            ) {
                "Черга $queue · Початок о $startTime · " +
                        "Час завершення не визначено"
            } else {
                "Черга $queue · $startTime–$endTime"
            }

        } else {
            "Черга $queue · Очікується о $endTime"
        }

        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setSmallIcon(
                if (isOutage) {
                    android.R.drawable.ic_dialog_alert
                } else {
                    android.R.drawable.ic_lock_idle_charging
                }
            )
            .setColor(
                if (isOutage) {
                    android.graphics.Color.RED
                } else {
                    android.graphics.Color.rgb(
                        34,
                        197,
                        94
                    )
                }
            )
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$message\n$details")
            )
            .setPriority(
                NotificationCompat.PRIORITY_HIGH
            )
            .setCategory(
                NotificationCompat.CATEGORY_REMINDER
            )
            .setContentIntent(
                createOpenAppIntent(context)
            )
            .setAutoCancel(true)
            .build()

        if (
            sendNotification(
                context,
                eventId.hashCode(),
                notification
            )
        ) {
            sentPrefs.edit()
                .putBoolean(eventId, true)
                .apply()
        }
    }

    /**
     * Создаёт действие открытия приложения
     * при нажатии на уведомление.
     *
     * Используем стандартный launcher Intent,
     * чтобы не зависеть от названия MainActivity.
     */
    private fun createOpenAppIntent(
        context: Context
    ): PendingIntent? {

        val launchIntent =
            context.packageManager
                .getLaunchIntentForPackage(
                    context.packageName
                )
                ?: return null

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        return PendingIntent.getActivity(
            context,
            918274,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showTestNotification(
        context: Context
    ) {

        createChannel(
            context = context,
            channelId = TEST_CHANNEL_ID,
            channelName =
                "Світло · Тестові сповіщення",
            description =
                "Перевірка доставки тестових сповіщень"
        )

        val notification = NotificationCompat.Builder(
            context,
            TEST_CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_dialog_info
            )
            .setColor(
                android.graphics.Color.rgb(
                    59,
                    130,
                    246
                )
            )
            .setContentTitle(
                "Світло · Тестове сповіщення"
            )
            .setContentText(
                "Перевірка успішна! КропіLive може " +
                        "надсилати заплановані сповіщення."
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "Це тестове сповіщення. " +
                                "Воно не пов'язане з реальними " +
                                "відключеннями електроенергії."
                    )
            )
            .setPriority(
                NotificationCompat.PRIORITY_HIGH
            )
            .setCategory(
                NotificationCompat.CATEGORY_REMINDER
            )
            .setContentIntent(
                createOpenAppIntent(context)
            )
            .setAutoCancel(true)
            .build()

        sendNotification(
            context,
            TEST_REQUEST_CODE,
            notification
        )
    }

    private fun createChannel(
        context: Context,
        channelId: String,
        channelName: String,
        description: String
    ) {

        val manager = context.getSystemService(
            NotificationManager::class.java
        )

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            this.description = description
            enableVibration(true)
        }

        manager.createNotificationChannel(channel)
    }

    private fun hasNotificationPermission(
        context: Context
    ): Boolean {

        return (
                Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                )
    }

    @Suppress("MissingPermission")
    private fun sendNotification(
        context: Context,
        notificationId: Int,
        notification: Notification
    ): Boolean {

        if (!hasNotificationPermission(context)) {
            return false
        }

        return try {

            val manager =
                NotificationManagerCompat.from(context)

            if (!manager.areNotificationsEnabled()) {
                return false
            }

            manager.notify(
                notificationId,
                notification
            )

            true

        } catch (_: SecurityException) {
            false
        }
    }
}