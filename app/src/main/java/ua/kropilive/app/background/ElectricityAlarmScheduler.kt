package ua.kropilive.app.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ua.kropilive.app.data.repository.HourlyGraph
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ElectricityAlarmScheduler {

    private const val PREFS_NAME =
        "electricity_alarm_schedule_v1"

    private const val SENT_PREFS_NAME =
        "electricity_alarm_sent_v1"

    private const val IDS_KEY = "scheduled_event_ids"
    private const val QUEUE_KEY = "scheduled_queue"

    private const val WARNING_MINUTES = 15L

    private val zone = ZoneId.of("Europe/Kyiv")

    private val timeFormatter =
        DateTimeFormatter.ofPattern(
            "HH:mm",
            Locale.US
        )

    @Synchronized
    fun schedule(
        context: Context,
        graph: HourlyGraph?
    ) {

        if (graph == null) return

        val queue = normalizeQueue(graph.group)

        val today = LocalDate.now(zone).toString()

        val notifyOutage =
            NotificationPreferences.get(
                context,
                "power_off"
            )

        val notifyRestoration =
            NotificationPreferences.get(
                context,
                "power_on"
            )

        if (
            queue.isBlank() ||
            graph.date != today ||
            (!notifyOutage && !notifyRestoration)
        ) {
            cancelAll(context)
            return
        }

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val manager = context.getSystemService(
            Context.ALARM_SERVICE
        ) as AlarmManager

        val previousIds = prefs.getStringSet(
            IDS_KEY,
            emptySet()
        )?.toSet() ?: emptySet()

        val previousQueue =
            prefs.getString(QUEUE_KEY, null)

        if (
            previousQueue != null &&
            previousQueue != queue
        ) {
            cancelAll(context)
        }

        val existingIds =
            if (previousQueue == queue) {
                previousIds
            } else {
                emptySet()
            }

        val events = buildEvents(
            graph = graph,
            queue = queue
        ).filter { event ->

            when (event.type) {

                ElectricityAlarmReceiver.TYPE_OUTAGE,
                ElectricityAlarmReceiver.TYPE_OUTAGE_STARTED ->
                    notifyOutage

                ElectricityAlarmReceiver.TYPE_RESTORATION ->
                    notifyRestoration

                else -> false
            }
        }

        val desiredIds =
            events.map { it.id }.toSet()

        // Отменяем события, которые исчезли
        // из нового графика.
        for (id in existingIds - desiredIds) {
            cancelEvent(
                context,
                manager,
                id
            )
        }

        val sentPrefs = context.getSharedPreferences(
            SENT_PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val activeIds = mutableSetOf<String>()

        for (event in events) {

            // Не повторяем уже отправленные события.
            if (
                sentPrefs.getBoolean(
                    event.id,
                    false
                )
            ) {

                // Если старый будильник ещё существует,
                // удаляем его.
                if (event.id in existingIds) {
                    cancelEvent(
                        context,
                        manager,
                        event.id
                    )
                }

                continue
            }

            // Время штатного предупреждения прошло, но отключение
            // ещё впереди: показываем сообщение сразу, один раз.
            if (event.triggerAt <= System.currentTimeMillis()) {
                if (event.id in existingIds) {
                    cancelEvent(context, manager, event.id)
                }
                prefs.edit().remove(event.id).commit()
                // The start-of-outage alert is meaningful only at its scheduled
                // time. Never turn an old start event into a late 15-minute warning.
                if (event.type == ElectricityAlarmReceiver.TYPE_OUTAGE_STARTED) {
                    continue
                }
                if (showImmediateWarning(context, event, queue)) {
                    sentPrefs.edit().putBoolean(event.id, true).commit()
                }
                continue
            }

            // Сначала записываем разрешение
            // для проверки в Receiver.
            val saved = prefs.edit()
                .putBoolean(event.id, true)
                .commit()

            if (!saved) {
                continue
            }

            /*
             * ВАЖНО:
             *
             * Устанавливаем будильник повторно
             * даже при наличии ID в SharedPreferences.
             *
             * Это восстанавливает уведомления после
             * перезагрузки телефона.
             *
             * Один и тот же PendingIntent не создаёт
             * второй независимый будильник.
             */
            val scheduled = scheduleEvent(
                context = context,
                manager = manager,
                event = event,
                queue = queue
            )

            if (scheduled) {

                activeIds.add(event.id)

            } else {

                prefs.edit()
                    .remove(event.id)
                    .commit()
            }
        }

        val editor = prefs.edit()

        for (id in existingIds - activeIds) {
            editor.remove(id)
        }

        for (id in activeIds) {
            editor.putBoolean(id, true)
        }

        editor.putStringSet(
            IDS_KEY,
            activeIds
        )

        editor.putString(
            QUEUE_KEY,
            queue
        )

        editor.apply()
    }

    @Synchronized
    fun cancelAll(context: Context) {

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val ids = prefs.getStringSet(
            IDS_KEY,
            emptySet()
        )?.toSet() ?: emptySet()

        val manager = context.getSystemService(
            Context.ALARM_SERVICE
        ) as AlarmManager

        for (id in ids) {
            cancelEvent(
                context,
                manager,
                id
            )
        }

        val editor = prefs.edit()

        for (id in ids) {
            editor.remove(id)
        }

        editor.remove(IDS_KEY)
        editor.remove(QUEUE_KEY)

        editor.apply()
    }

    private fun scheduleEvent(
        context: Context,
        manager: AlarmManager,
        event: ElectricityEvent,
        queue: String
    ): Boolean {

        val pendingIntent = createPendingIntent(
            context = context,
            event = event,
            queue = queue
        )

        return try {

            val exactAllowed =
                Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.S ||
                        manager.canScheduleExactAlarms()

            if (exactAllowed) {

                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    event.triggerAt,
                    pendingIntent
                )

            } else {

                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    event.triggerAt,
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

    private fun showImmediateWarning(
        context: Context,
        event: ElectricityEvent,
        queue: String
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return false

        val preference = if (event.type == ElectricityAlarmReceiver.TYPE_OUTAGE)
            "power_off" else "power_on"
        if (!NotificationPreferences.get(context, preference)) return false

        val now = System.currentTimeMillis()
        val transitionAt = event.triggerAt + WARNING_MINUTES * 60_000L
        if (now >= transitionAt || now < event.triggerAt) return false
        val minutes = ceil((transitionAt - now) / 60_000.0).toInt()
        val outage = event.type == ElectricityAlarmReceiver.TYPE_OUTAGE
        val title = if (outage) "Світло · Скоро відключення" else "Світло · Скоро відновлення"
        val message = if (outage)
            "За графіком відключення приблизно через $minutes хв. Черга $queue, початок о ${event.startTime}."
        else
            "За графіком відновлення приблизно через $minutes хв. Черга $queue, о ${event.endTime}."

        val channelId = ElectricityAlarmReceiver.CHANNEL_ID
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Світло · Попередження за графіком",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) }
        )
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, 918274, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
        return try {
            val notifications = NotificationManagerCompat.from(context)
            if (!notifications.areNotificationsEnabled()) false
            else {
                @Suppress("MissingPermission")
                notifications.notify(event.id.hashCode(), notification)
                true
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun createPendingIntent(
        context: Context,
        event: ElectricityEvent,
        queue: String
    ): PendingIntent {

        val intent = Intent(
            context,
            ElectricityAlarmReceiver::class.java
        ).apply {

            action =
                ElectricityAlarmReceiver
                    .ACTION_ELECTRICITY_ALARM

            putExtra(
                ElectricityAlarmReceiver.EXTRA_EVENT_ID,
                event.id
            )

            putExtra(
                ElectricityAlarmReceiver.EXTRA_EVENT_TYPE,
                event.type
            )

            putExtra(
                ElectricityAlarmReceiver.EXTRA_QUEUE,
                queue
            )

            putExtra(
                ElectricityAlarmReceiver.EXTRA_START_TIME,
                event.startTime
            )

            putExtra(
                ElectricityAlarmReceiver.EXTRA_END_TIME,
                event.endTime
            )

            putExtra(
                ElectricityAlarmReceiver.EXTRA_TRIGGER_AT,
                event.triggerAt
            )
        }

        return PendingIntent.getBroadcast(
            context,
            event.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelEvent(
        context: Context,
        manager: AlarmManager,
        id: String
    ) {

        val intent = Intent(
            context,
            ElectricityAlarmReceiver::class.java
        ).apply {

            action =
                ElectricityAlarmReceiver
                    .ACTION_ELECTRICITY_ALARM
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or
                    PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {

            manager.cancel(pendingIntent)

            pendingIntent.cancel()
        }
    }

    private fun buildEvents(
        graph: HourlyGraph,
        queue: String
    ): List<ElectricityEvent> {

        val date = try {
            LocalDate.parse(graph.date)
        } catch (_: Exception) {
            return emptyList()
        }

        val slots = graph.times.mapNotNull {
                (timeText, status) ->

            val time = try {
                LocalTime.parse(timeText)
            } catch (_: Exception) {
                null
            }

            if (
                time == null ||
                time.minute !in listOf(0, 30) ||
                time.second != 0
            ) {

                null

            } else {

                LocalDateTime.of(
                    date,
                    time
                ) to status
            }

        }.sortedBy { it.first }

        if (slots.size < 2) {
            return emptyList()
        }

        val events = mutableListOf<ElectricityEvent>()

        val now = System.currentTimeMillis()

        for (index in 1 until slots.size) {

            val previous = slots[index - 1]
            val current = slots[index]

            // Переходы только между соседними
            // 30-минутными интервалами.
            if (
                previous.first.plusMinutes(30) !=
                current.first
            ) {
                continue
            }

            val previousStatus = previous.second
            val currentStatus = current.second

            // Неизвестный статус "10" не считаем
            // подтверждённым переходом.

            if (
                previousStatus == "0" &&
                currentStatus == "1"
            ) {

                val start = current.first

                val end = findOutageEnd(
                    slots = slots,
                    startIndex = index
                )

                addOutageEvent(
                    events = events,
                    date = date,
                    queue = queue,
                    start = start,
                    end = end,
                    now = now
                )

                addOutageStartedEvent(
                    events = events,
                    date = date,
                    queue = queue,
                    start = start,
                    end = end,
                    now = now
                )
            }

            if (
                previousStatus == "1" &&
                currentStatus == "0"
            ) {

                addRestorationEvent(
                    events = events,
                    date = date,
                    queue = queue,
                    end = current.first,
                    now = now
                )
            }
        }

        return events
    }

    private fun findOutageEnd(
        slots: List<Pair<LocalDateTime, String>>,
        startIndex: Int
    ): LocalDateTime? {

        var previousTime =
            slots[startIndex].first

        for (index in startIndex + 1 until slots.size) {

            val current = slots[index]

            if (
                previousTime.plusMinutes(30) !=
                current.first
            ) {
                return null
            }

            when (current.second) {

                "0" -> return current.first

                "1" -> {
                    previousTime = current.first
                }

                else -> return null
            }
        }

        return null
    }

    private fun addOutageEvent(
        events: MutableList<ElectricityEvent>,
        date: LocalDate,
        queue: String,
        start: LocalDateTime,
        end: LocalDateTime?,
        now: Long
    ) {

        val triggerAt = start
            .atZone(zone)
            .toInstant()
            .toEpochMilli() -
                WARNING_MINUTES * 60_000L

        if (triggerAt + WARNING_MINUTES * 60_000L <= now) return

        val startText = start
            .toLocalTime()
            .format(timeFormatter)

        val endText = end
            ?.toLocalTime()
            ?.format(timeFormatter)
            ?: "не визначено"

        val id =
            "${date}_${queue}_${startText}_outage"

        events.add(
            ElectricityEvent(
                id = id,
                type =
                    ElectricityAlarmReceiver.TYPE_OUTAGE,
                startTime = startText,
                endTime = endText,
                triggerAt = triggerAt
            )
        )
    }

    // A separate alarm for the actual start of a scheduled outage.
    // Distinct ID ensures it does not replace the 15-minute warning.
    private fun addOutageStartedEvent(
        events: MutableList<ElectricityEvent>,
        date: LocalDate,
        queue: String,
        start: LocalDateTime,
        end: LocalDateTime?,
        now: Long
    ) {
        val triggerAt = start.atZone(zone).toInstant().toEpochMilli()
        if (triggerAt <= now) return

        val startText = start.toLocalTime().format(timeFormatter)
        val endText = end?.toLocalTime()?.format(timeFormatter)
            ?: "не визначено"

        events.add(
            ElectricityEvent(
                id = "${date}_${queue}_${startText}_outage_started",
                type = ElectricityAlarmReceiver.TYPE_OUTAGE_STARTED,
                startTime = startText,
                endTime = endText,
                triggerAt = triggerAt
            )
        )
    }

    private fun addRestorationEvent(
        events: MutableList<ElectricityEvent>,
        date: LocalDate,
        queue: String,
        end: LocalDateTime,
        now: Long
    ) {

        val triggerAt = end
            .atZone(zone)
            .toInstant()
            .toEpochMilli() -
                WARNING_MINUTES * 60_000L

        if (triggerAt + WARNING_MINUTES * 60_000L <= now) return

        val endText = end
            .toLocalTime()
            .format(timeFormatter)

        val id =
            "${date}_${queue}_${endText}_restoration"

        events.add(
            ElectricityEvent(
                id = id,
                type =
                    ElectricityAlarmReceiver
                        .TYPE_RESTORATION,
                startTime = "",
                endTime = endText,
                triggerAt = triggerAt
            )
        )
    }

    private fun normalizeQueue(
        queue: String
    ): String {

        return queue
            .trim()
            .replace(',', '.')
            .replace(
                Regex("""(?i)^черга\s*"""),
                ""
            )
    }

    private data class ElectricityEvent(
        val id: String,
        val type: String,
        val startTime: String,
        val endTime: String,
        val triggerAt: Long
    )
}