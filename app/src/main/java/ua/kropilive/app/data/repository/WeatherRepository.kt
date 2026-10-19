
package ua.kropilive.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HourlyWeather(
    val time: String,
    val temperature: Double,
    val weatherCode: Int,
    val precipitationProbability: Int,
    val isDay: Boolean
)

data class DailyWeather(
    val date: String,
    val minTemperature: Double,
    val maxTemperature: Double,
    val weatherCode: Int,
    val precipitationProbability: Int
)

data class WeatherData(
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val description: String,
    val hourly: List<HourlyWeather>,
    val daily: List<DailyWeather>
)

class WeatherRepository {

    companion object {
        private const val CACHE_DURATION_MS = 10 * 60 * 1000L

        private val mutex = Mutex()

        @Volatile
        private var cachedWeather: WeatherData? = null

        @Volatile
        private var cachedAt: Long = 0L

        fun cached(): WeatherData? = cachedWeather
    }

    suspend fun getCurrentWeather(
        forceRefresh: Boolean = false
    ): WeatherData {
        val now = System.currentTimeMillis()
        val existing = cachedWeather

        if (
            !forceRefresh &&
            existing != null &&
            now - cachedAt in 0 until CACHE_DURATION_MS
        ) {
            return existing
        }

        return mutex.withLock {
            val checkTime = System.currentTimeMillis()
            val latest = cachedWeather

            if (
                !forceRefresh &&
                latest != null &&
                checkTime - cachedAt in 0 until CACHE_DURATION_MS
            ) {
                return@withLock latest
            }

            try {
                val fresh = fetchWeather()
                cachedWeather = fresh
                cachedAt = System.currentTimeMillis()
                fresh
            } catch (e: Exception) {
                cachedWeather ?: throw e
            }
        }
    }

    private suspend fun fetchWeather(): WeatherData =
        withContext(Dispatchers.IO) {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=48.51" +
                        "&longitude=32.26" +
                        "&current=temperature_2m,apparent_temperature," +
                        "relative_humidity_2m,wind_speed_10m," +
                        "weather_code,is_day" +
                        "&hourly=temperature_2m,weather_code," +
                        "precipitation_probability,is_day" +
                        "&daily=weather_code,temperature_2m_max," +
                        "temperature_2m_min,precipitation_probability_max" +
                        "&forecast_days=7" +
                        "&timezone=Europe%2FKyiv"
            )

            val connection =
                url.openConnection() as HttpURLConnection

            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            try {
                if (connection.responseCode !in 200..299) {
                    error("Помилка сервера: ${connection.responseCode}")
                }

                val json = JSONObject(
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }
                )

                val current = json.getJSONObject("current")
                val hourlyJson = json.getJSONObject("hourly")
                val dailyJson = json.getJSONObject("daily")

                val currentTime = current.getString("time")
                val hourlyTimes = hourlyJson.getJSONArray("time")
                val hourlyTemperatures =
                    hourlyJson.getJSONArray("temperature_2m")
                val hourlyCodes =
                    hourlyJson.getJSONArray("weather_code")
                val hourlyRain =
                    hourlyJson.getJSONArray("precipitation_probability")
                val hourlyDay =
                    hourlyJson.getJSONArray("is_day")

                val hours = mutableListOf<HourlyWeather>()

                for (i in 0 until hourlyTimes.length()) {
                    val time = hourlyTimes.getString(i)

                    if (time >= currentTime.take(13) + ":00") {
                        hours.add(
                            HourlyWeather(
                                time = time,
                                temperature =
                                    hourlyTemperatures.getDouble(i),
                                weatherCode =
                                    hourlyCodes.getInt(i),
                                precipitationProbability =
                                    hourlyRain.getInt(i),
                                isDay = hourlyDay.getInt(i) == 1
                            )
                        )
                    }

                    if (hours.size == 24) break
                }

                val dailyDates = dailyJson.getJSONArray("time")
                val dailyMin =
                    dailyJson.getJSONArray("temperature_2m_min")
                val dailyMax =
                    dailyJson.getJSONArray("temperature_2m_max")
                val dailyCodes =
                    dailyJson.getJSONArray("weather_code")
                val dailyRain =
                    dailyJson.getJSONArray(
                        "precipitation_probability_max"
                    )

                val days = (0 until dailyDates.length()).map { i ->
                    DailyWeather(
                        date = dailyDates.getString(i),
                        minTemperature = dailyMin.getDouble(i),
                        maxTemperature = dailyMax.getDouble(i),
                        weatherCode = dailyCodes.getInt(i),
                        precipitationProbability = dailyRain.getInt(i)
                    )
                }

                val code = current.getInt("weather_code")

                WeatherData(
                    temperature =
                        current.getDouble("temperature_2m"),
                    feelsLike =
                        current.getDouble("apparent_temperature"),
                    humidity =
                        current.getInt("relative_humidity_2m"),
                    windSpeed =
                        current.getDouble("wind_speed_10m"),
                    weatherCode = code,
                    isDay = current.getInt("is_day") == 1,
                    description = weatherDescription(code),
                    hourly = hours,
                    daily = days
                )
            } finally {
                connection.disconnect()
            }
        }
}

fun weatherDescription(code: Int): String = when (code) {
    0 -> "Ясно"
    1 -> "Переважно ясно"
    2 -> "Мінлива хмарність"
    3 -> "Хмарно"
    45, 48 -> "Туман"
    51, 53, 55, 56, 57 -> "Мряка"
    61, 63, 65, 66, 67 -> "Дощ"
    71, 73, 75, 77 -> "Сніг"
    80, 81, 82 -> "Зливи"
    85, 86 -> "Снігові зливи"
    95, 96, 99 -> "Гроза"
    else -> "Невідомо"
}

fun weatherIcon(
    code: Int,
    isDay: Boolean = true
): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1 -> if (isDay) "🌤️" else "🌙"
    2 -> if (isDay) "⛅" else "☁️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57 -> "🌦️"
    61, 63, 65, 66, 67 -> "🌧️"
    71, 73, 75, 77, 85, 86 -> "🌨️"
    80, 81, 82 -> "🌧️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}
