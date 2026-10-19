
package ua.kropilive.app.ui.weather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ua.kropilive.app.data.repository.WeatherData
import ua.kropilive.app.data.repository.WeatherRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val WeatherBlue = Color(0xFF38BDF8)
private val WeatherYellow = Color(0xFFFACC15)
private val WeatherMuted = Color(0xFF94A3B8)

private val UkrainianLocale = Locale.forLanguageTag("uk-UA")

@Composable
fun WeatherScreen() {
    val repository = remember { WeatherRepository() }
    val scope = rememberCoroutineScope()

    var weather by remember {
        mutableStateOf<WeatherData?>(WeatherRepository.cached())
    }

    var loading by remember {
        mutableStateOf(WeatherRepository.cached() == null)
    }

    var error by remember {
        mutableStateOf<String?>(null)
    }

    fun loadWeather(forceRefresh: Boolean = false) {
        scope.launch {
            loading = true
            error = null

            try {
                weather = repository.getCurrentWeather(
                    forceRefresh = forceRefresh
                )
            } catch (e: Exception) {
                error = "Не вдалося завантажити погоду. Перевірте інтернет."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadWeather()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Погода",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )

                    Text(
                        text = "Кропивницький",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = { loadWeather(forceRefresh = true) },
                enabled = !loading
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Оновити погоду",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        when {
            weather == null && loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            weather == null -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )

                        Text(
                            text = "Погода тимчасово недоступна",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = error ?: "Немає даних",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Button(onClick = { loadWeather(forceRefresh = true) }) {
                            Text("Спробувати ще раз")
                        }
                    }
                }
            }

            else -> {
                val data = weather!!
                val today = data.daily.firstOrNull()

                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (error != null) {
                    Text(
                        text = "Не вдалося оновити прогноз. Показано попередні дані.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Основна картка погоди
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ПОГОДА ЗАРАЗ",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            WeatherConditionIcon(
                                code = data.weatherCode,
                                isDay = data.isDay,
                                size = 25
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "${data.temperature.roundToInt()}°",
                                    fontSize = 72.sp,
                                    lineHeight = 78.sp,
                                    fontWeight = FontWeight.Light,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Text(
                                    text = data.description,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            WeatherConditionIcon(
                                code = data.weatherCode,
                                isDay = data.isDay,
                                size = 76
                            )
                        }

                        Text(
                            text = "Відчувається як ${data.feelsLike.roundToInt()}°",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        if (today != null) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "↑ ${today.maxTemperature.roundToInt()}°",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Spacer(Modifier.width(18.dp))

                                Text(
                                    text = "↓ ${today.minTemperature.roundToInt()}°",
                                    color = WeatherMuted
                                )

                                Spacer(Modifier.weight(1f))

                                Text(
                                    text = "Сьогодні",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // Показники
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WeatherMetricCard(
                        icon = Icons.Default.WaterDrop,
                        title = "Вологість",
                        value = "${data.humidity}%",
                        modifier = Modifier.weight(1f)
                    )

                    WeatherMetricCard(
                        icon = Icons.Default.Air,
                        title = "Вітер",
                        value = "${data.windSpeed.roundToInt()} км/год",
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WeatherMetricCard(
                        icon = Icons.Default.Thermostat,
                        title = "Відчувається",
                        value = "${data.feelsLike.roundToInt()}°",
                        modifier = Modifier.weight(1f)
                    )

                    WeatherMetricCard(
                        icon = Icons.Default.Umbrella,
                        title = "Ймовірність опадів",
                        value = today?.let {
                            "${it.precipitationProbability}%"
                        } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }

                WeatherSectionHeader(
                    icon = Icons.Default.Schedule,
                    title = "Прогноз за годинами"
                )

                WeatherPanel {
                    if (data.hourly.isEmpty()) {
                        Text(
                            text = "Погодинний прогноз недоступний",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(data.hourly) { hour ->
                                Column(
                                    modifier = Modifier.width(68.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = hour.time.takeLast(5),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    WeatherConditionIcon(
                                        code = hour.weatherCode,
                                        isDay = hour.isDay,
                                        size = 30
                                    )

                                    Text(
                                        text = "${hour.temperature.roundToInt()}°",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.WaterDrop,
                                            contentDescription = null,
                                            tint = WeatherBlue,
                                            modifier = Modifier.size(13.dp)
                                        )

                                        Text(
                                            text = "${hour.precipitationProbability}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                WeatherSectionHeader(
                    icon = Icons.Default.CalendarMonth,
                    title = "Прогноз на 7 днів"
                )

                WeatherPanel {
                    if (data.daily.isEmpty()) {
                        Text(
                            text = "Прогноз на тиждень недоступний",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            data.daily.forEachIndexed { index, day ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (index == 0) {
                                            "Сьогодні"
                                        } else {
                                            formatWeatherDay(day.date)
                                        },
                                        modifier = Modifier.weight(1.4f),
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Box(
                                        modifier = Modifier.weight(0.65f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        WeatherConditionIcon(
                                            code = day.weatherCode,
                                            size = 24
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.weight(0.9f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.WaterDrop,
                                            contentDescription = null,
                                            tint = WeatherBlue,
                                            modifier = Modifier.size(12.dp)
                                        )

                                        Text(
                                            text = "${day.precipitationProbability}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Text(
                                        text = "${day.minTemperature.roundToInt()}°",
                                        modifier = Modifier.weight(0.5f),
                                        textAlign = TextAlign.End,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Text(
                                        text = "${day.maxTemperature.roundToInt()}°",
                                        modifier = Modifier.weight(0.5f),
                                        textAlign = TextAlign.End,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (index != data.daily.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )

                        Text(
                            text = "Джерело: Open-Meteo. Прогноз для Кропивницького. " +
                                    "Дані оновлюються під час відкриття екрана " +
                                    "або за натисканням кнопки оновлення.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = { loadWeather(forceRefresh = true) },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(Modifier.width(8.dp))

                    Text("Оновити прогноз")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Єдині Material Icons для всіх погодних умов.
 * Коди відповідають WMO/Open-Meteo.
 */
@Composable
private fun WeatherConditionIcon(
    code: Int,
    isDay: Boolean = true,
    size: Int = 32
) {
    val icon: ImageVector
    val color: Color

    when (code) {
        0 -> {
            icon = if (isDay) {
                Icons.Default.WbSunny
            } else {
                Icons.Default.NightsStay
            }
            color = if (isDay) WeatherYellow else WeatherBlue
        }

        1, 2 -> {
            icon = if (isDay) {
                Icons.Default.WbCloudy
            } else {
                Icons.Default.NightsStay
            }
            color = if (isDay) WeatherYellow else WeatherBlue
        }

        3 -> {
            icon = Icons.Default.Cloud
            color = WeatherMuted
        }

        45, 48 -> {
            icon = Icons.Default.Dehaze
            color = WeatherMuted
        }

        51, 53, 55, 56, 57,
        61, 63, 65, 66, 67,
        80, 81, 82 -> {
            icon = Icons.Default.Grain
            color = WeatherBlue
        }

        71, 73, 75, 77, 85, 86 -> {
            icon = Icons.Default.AcUnit
            color = WeatherBlue
        }

        95, 96, 99 -> {
            icon = Icons.Default.FlashOn
            color = WeatherYellow
        }

        else -> {
            icon = Icons.Default.Cloud
            color = WeatherMuted
        }
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(size.dp)
    )
}

@Composable
private fun WeatherSectionHeader(
    icon: ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun WeatherPanel(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun WeatherMetricCard(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp)
            )

            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatWeatherDay(date: String): String {
    return try {
        LocalDate.parse(date).format(
            DateTimeFormatter.ofPattern(
                "EEE, d MMM",
                UkrainianLocale
            )
        )
    } catch (e: Exception) {
        date
    }
}