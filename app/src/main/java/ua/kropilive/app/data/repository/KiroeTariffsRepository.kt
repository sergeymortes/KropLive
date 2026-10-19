package ua.kropilive.app.data.repository

import android.os.Build
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

data class KiroeTariffPublication(
    val published: String,
    val effective: String,
    val regulation: String,
    val class1: String,
    val class2: String
)

object KiroeTariffsRepository {
    const val URL_SOURCE = "https://kiroe.com.ua/tarifi"

    suspend fun latest(): KiroeTariffPublication = withContext(Dispatchers.IO) {
        val connection = (URL(URL_SOURCE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "KropiLive/1.0 (Android; tariff viewer)")
            setRequestProperty("Accept", "text/html")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(html) ?: error("Не вдалося розпізнати останню публікацію з тарифами.")
        } finally {
            connection.disconnect()
        }
    }

    // The page is a list of publications. We select the latest DATED entry with both
    // distribution tariff classes, rather than a balancing-market price or older post.
    fun parse(html: String): KiroeTariffPublication? {
        val plain = if (Build.VERSION.SDK_INT >= 24)
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        else Html.fromHtml(html).toString()
        val text = plain.replace('\u00a0', ' ').replace(Regex("[ \\t]+"), " ")
        val dateRegex = Regex("""Дата публікації:\s*(\d{2}\.\d{2}\.\d{4})""", RegexOption.IGNORE_CASE)
        val matches = dateRegex.findAll(text).toList()
        val number = """([\d\s]+,\d{2})"""
        val first = Regex("""для\s*1\s*класу\s*напруги\s*[-–—:]?\s*${number}\s*грн""", RegexOption.IGNORE_CASE)
        val second = Regex("""для\s*2\s*класу\s*напруги\s*[-–—:]?\s*${number}\s*грн""", RegexOption.IGNORE_CASE)
        val effective = Regex("""(?:на\s*період\s*)?з\s*(\d{2}\s+[а-яіїєґ]+\s+\d{4}\s*року)\s+по\s+(\d{2}\s+[а-яіїєґ]+\s+\d{4}\s*року)""", RegexOption.IGNORE_CASE)
        val regulation = Regex("""постанов[иі]\s+НКРЕКП\s+від\s+([\d.]+\s*року)\s*№\s*(\d+)""", RegexOption.IGNORE_CASE)
        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT)
        dateFormat.isLenient = false
        return matches.mapNotNull { match ->
            val end = matches.firstOrNull { it.range.first > match.range.first }?.range?.first ?: text.length
            val body = text.substring(match.range.last + 1, end).take(3500)
            val a = first.find(body)?.groupValues?.get(1)?.trim()
            val b = second.find(body)?.groupValues?.get(1)?.trim()
            if (a == null || b == null) null else {
                val dates = effective.find(body)
                val reg = regulation.find(body)
                val publication = KiroeTariffPublication(
                    published = match.groupValues[1],
                    effective = if (dates != null) "з ${dates.groupValues[1]} по ${dates.groupValues[2]}" else "Уточнюйте період дії на офіційному сайті",
                    regulation = if (reg != null) "Постанова НКРЕКП № ${reg.groupValues[2]} від ${reg.groupValues[1]}" else "Дані з офіційної публікації",
                    class1 = a,
                    class2 = b
                )
                publication
            }
        }.maxByOrNull { dateFormat.parse(it.published)?.time ?: 0L }
    }
}
