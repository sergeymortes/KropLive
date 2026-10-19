
package ua.kropilive.app.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Восстанавливает фоновое обновление данных
 * после перезагрузки телефона.
 *
 * Android удаляет будильники AlarmManager
 * при перезагрузке устройства.
 *
 * Поэтому после запуска системы запрашиваем
 * обновление графика и повторное планирование
 * будущих уведомлений.
 */
class ElectricityBootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val action = intent.action

        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        try {

            // Убеждаемся, что периодическое
            // обновление зарегистрировано.
            BackgroundRefresh.schedule(context)

            // Отдельное однократное обновление:
            // загрузит свежий график и восстановит
            // будильники через ElectricityAlarmScheduler.
            BackgroundRefresh.refreshNow(context)

            Log.d(
                "KropLiveBoot",
                "Requested refresh after: $action"
            )

        } catch (exception: Exception) {

            Log.e(
                "KropLiveBoot",
                "Failed to request refresh",
                exception
            )
        }
    }
}
