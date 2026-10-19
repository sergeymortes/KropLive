package ua.kropilive.app.ui.schedules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ua.kropilive.app.data.repository.HourlyBlackoutRepository
import ua.kropilive.app.data.repository.HourlyGraph
import ua.kropilive.app.data.repository.TelegramBlackoutRepository
import ua.kropilive.app.data.repository.TelegramQueueGraph
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

@Composable
fun HourlyBlackoutCard(queue: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var graph by remember(queue) { mutableStateOf(HourlyBlackoutRepository.cached(context, queue)) }
    var telegramGraph by remember(queue) { mutableStateOf(TelegramBlackoutRepository.cached(context, queue)) }
    var loading by remember(queue) { mutableStateOf(true) }
    var refresh by remember(queue) { mutableIntStateOf(0) }
    LaunchedEffect(queue, refresh) {
        if (queue.isBlank()) { loading = false; return@LaunchedEffect }
        loading = true
        coroutineScope {
            val site = async { HourlyBlackoutRepository.load(context, queue) }
            val channel = async { TelegramBlackoutRepository.load(context, queue) }
            graph = site.await()
            telegramGraph = channel.await()
        }
        loading = false
    }
    // Automatic refresh while screen is visible. No requests when user leaves the screen.
    LaunchedEffect(queue) {
        while (true) { delay(2 * 60_000L); refresh++ }
    }
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, null, tint = Color(0xFFFACC15))
                Spacer(Modifier.width(8.dp))
                Text("Погодинний графік", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh++ }, enabled = !loading && queue.isNotBlank()) {
                    Icon(Icons.Default.Refresh, "Оновити графік")
                }
            }
            if (loading && graph == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            val today = LocalDate.now().toString()
            val site = graph?.takeIf { it.date == today && !it.cached }
            val channel = telegramGraph?.takeIf { it.date == today }
            // Fresh site schedule wins. When the site is unavailable, use today's
            // dated Telegram post. Never silently substitute yesterday's graph.
            val g = site ?: channel?.let { HourlyGraph(it.queue, it.date, it.times, it.cached) }
                ?: graph?.takeIf { it.date == today }
            val source = when {
                site != null -> "Кіровоградобленерго"
                channel != null -> "Telegram · офіційний канал"
                g != null -> "Збережені дані сайту"
                else -> "Даних немає"
            }
            if (queue.isBlank()) {
                Text("Номер черги невідомий. Оберіть адресу в налаштуваннях.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Черга $queue · $source" + if (g != null) " · ${g.date}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (g == null) {
                    Text("Таблиця відображається постійно. Сірі клітинки означають, що оператор не надав даних, а не те, що світло є.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (g.cached || g.date != LocalDate.now().toString()) {
                    Text("Збережений графік. Може бути застарілим — не використовуйте як підтвердження поточного стану мережі.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LegendDot(Color(0xFF22C55E), "Є")
                    LegendDot(Color(0xFFEF4444), "Немає")
                    LegendDot(Color(0xFFF59E0B), "Перемикання")
                    LegendDot(Color.Gray, "Невідомо")
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Година", modifier = Modifier.width(53.dp), style = MaterialTheme.typography.labelSmall)
                    Text("00–30", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text("30–60", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                }
                for (hour in 0..23) {
                    val time = "%02d".format(java.util.Locale.US, hour)
                    val first = normalize(g?.times?.get("$time:00"), true)
                    val second = normalize(g?.times?.get("$time:30"), false)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$time:00", modifier = Modifier.width(53.dp),
                            style = MaterialTheme.typography.labelMedium)
                        StatusHalf(first, Modifier.weight(1f))
                        StatusHalf(second, Modifier.weight(1f))
                    }
                }
                Text("Оновлення кожні 2 хвилини, поки екран відкритий. Кожна смуга — 30 хвилин. Дані про планові та аварійні роботи завантажуються окремо.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading && graph != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Джерела: kiroe.com.ua та t.me/SvitloKropyvnytskyiMisto. Свіжі дані сайту мають пріоритет; якщо їх немає — графік за сьогодні з Telegram. Поза датою графіка стан невідомий.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun normalize(raw: String?, firstHalf: Boolean): String = when (raw) {
    "0" -> "0"; "1" -> "1"; "10" -> "10"; else -> "?"
}

@Composable
private fun StatusHalf(status: String, modifier: Modifier = Modifier) {
    val color = when (status) {
        "0" -> Color(0xFF22C55E)
        "1" -> Color(0xFFEF4444)
        "10" -> Color(0xFFF59E0B)
        else -> Color.Gray
    }
    Surface(modifier = modifier.height(15.dp), color = color, shape = RoundedCornerShape(5.dp)) { }
}

@Composable
private fun RowScope.LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Surface(modifier = Modifier.size(8.dp), color = color, shape = RoundedCornerShape(3.dp)) { }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
