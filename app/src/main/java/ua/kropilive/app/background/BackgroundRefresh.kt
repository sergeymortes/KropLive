package ua.kropilive.app.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ua.kropilive.app.data.repository.AddressRepository
import ua.kropilive.app.data.repository.AirAlertRepository
import ua.kropilive.app.data.repository.EcostyleRepository
import ua.kropilive.app.data.repository.HourlyBlackoutRepository
import ua.kropilive.app.data.repository.HourlyGraph
import ua.kropilive.app.data.repository.TelegramBlackoutRepository
import ua.kropilive.app.data.repository.WaterNoticeRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Отдельный Worker воздушной тревоги.
 *
 * Не зависит от загрузки воды, света и других данных.
 * Не гарантирует немедленный запуск Android.
 */
class AirAlertRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext

        return try {
            val address = withTimeoutOrNull(5_000L) {
                AddressRepository(context).address.first()
            } ?: return Result.retry()

            val district = AirAlertRepository.districtFor(address)

            if (district.isNullOrBlank()) {
                return Result.success()
            }

            val state = withTimeoutOrNull(18_000L) {
                AirAlertRepository.load(district)
            } ?: return Result.retry()

            AlertNotifier.check(context, state)

            Result.success()

        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/**
 * Фоновое обновление остальных источников.
 *
 * Электричество, вода и Экоcтайл.
 */
class DataRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val context = applicationContext

        val address = try {
            AddressRepository(context).address.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@coroutineScope Result.retry()
        }

        val waterTask = async {
            try {
                withTimeoutOrNull(8_000L) {
                    WaterNoticeRepository.load(address)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ошибка воды не останавливает другие задачи.
            }
        }

        val ecostyleTask = async {
            try {
                withTimeoutOrNull(8_000L) {
                    EcostyleRepository.load()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ошибка Экоcтайла не останавливает обновление.
            }
        }

        val electricityTask = async {
            try {
                val queue = address.electricityQueue.trim()

                if (queue.isBlank()) {
                    ElectricityAlarmScheduler.cancelAll(context)
                    return@async
                }

                coroutineScope {
                    val siteTask = async {
                        try {
                            withTimeoutOrNull(12_000L) {
                                HourlyBlackoutRepository.load(
                                    context,
                                    queue
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }

                    val telegramTask = async {
                        try {
                            withTimeoutOrNull(12_000L) {
                                TelegramBlackoutRepository.load(
                                    context,
                                    queue
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }

                    val siteGraph = siteTask.await()
                    val telegramGraph = telegramTask.await()

                    val today = LocalDate.now(
                        ZoneId.of("Europe/Kyiv")
                    ).toString()

                    fun normalizeQueue(value: String): String {
                        return value
                            .trim()
                            .replace(',', '.')
                            .replace(
                                Regex(
                                    """(?i)^(черга|підчерга|група)\s*[:№#-]?\s*"""
                                ),
                                ""
                            )
                            .replace(' ', '.')
                    }

                    val expectedQueue = normalizeQueue(queue)

                    val freshSite = siteGraph?.takeIf {
                        it.date == today &&
                                !it.cached &&
                                normalizeQueue(it.group) == expectedQueue
                    }

                    val freshTelegram = telegramGraph?.takeIf {
                        it.date == today &&
                                !it.cached &&
                                normalizeQueue(it.queue) == expectedQueue
                    }

                    val selectedGraph: HourlyGraph? =
                        freshSite ?: freshTelegram?.let {
                            HourlyGraph(
                                group = it.queue,
                                date = it.date,
                                times = it.times,
                                cached = false
                            )
                        }

                    if (selectedGraph != null) {
                        val saved =
                            HourlyBlackoutRepository.saveSelectedGraph(
                                context,
                                selectedGraph
                            )

                        if (saved) {
                            ElectricityAlarmScheduler.schedule(
                                context,
                                selectedGraph
                            )
                        }
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ошибка света не останавливает другие задачи.
            }
        }

        waterTask.await()
        ecostyleTask.await()
        electricityTask.await()

        Result.success()
    }
}

object BackgroundRefresh {

    private const val PERIODIC_WORK_NAME =
        "kropilive_data_refresh"

    private const val IMMEDIATE_WORK_NAME =
        "kropilive_data_refresh_now"

    private const val AIR_PERIODIC_WORK_NAME =
        "kropilive_air_alert_refresh"

    private const val AIR_IMMEDIATE_WORK_NAME =
        "kropilive_air_alert_refresh_now"

    private fun networkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    /**
     * Регулярное обновление.
     *
     * Оба Worker работают независимо.
     * 15 минут — минимальный период WorkManager,
     * но фактические запуски могут задерживаться.
     */
    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(
            context.applicationContext
        )

        val constraints = networkConstraints()

        val dataRequest =
            PeriodicWorkRequestBuilder<DataRefreshWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

        val airRequest =
            PeriodicWorkRequestBuilder<AirAlertRefreshWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            dataRequest
        )

        workManager.enqueueUniquePeriodicWork(
            AIR_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            airRequest
        )
    }

    /**
     * Внеочередное обновление всех источников.
     *
     * Проверка тревоги ставится в очередь отдельно
     * и не ждёт воду, свет или Экоcтайл.
     */
    fun refreshNow(context: Context) {
        refreshAirNow(context)

        val request =
            OneTimeWorkRequestBuilder<DataRefreshWorker>()
                .setConstraints(networkConstraints())
                .build()

        WorkManager.getInstance(
            context.applicationContext
        ).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Отдельная внеочередная проверка тревоги.
     *
     * Можно вызывать после открытия приложения,
     * изменения адреса или восстановления сети.
     *
     * Важно: WorkManager всё равно не гарантирует
     * мгновенное выполнение.
     */
    fun refreshAirNow(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<AirAlertRefreshWorker>()
                .setConstraints(networkConstraints())
                .build()

        WorkManager.getInstance(
            context.applicationContext
        ).enqueueUniqueWork(
            AIR_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}