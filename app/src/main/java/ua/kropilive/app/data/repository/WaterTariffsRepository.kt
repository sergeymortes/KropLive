package ua.kropilive.app.data.repository

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class WaterTariffsPage(val lines: List<String>)

object WaterTariffsRepository {
    const val PHYSICAL = "https://dnipro-kirovograd.com.ua/tarifsFiz"
    const val LEGAL = "https://dnipro-kirovograd.com.ua/tarifsUr"

    suspend fun load(url: String): WaterTariffsPage = withContext(Dispatchers.IO) {
        require(url == PHYSICAL || url == LEGAL)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "text/html")
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(html)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(html: String): WaterTariffsPage {
        val cleaned = html.replace(Regex("(?is)<(script|style|nav|footer|header)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)</(p|tr|li|div|h[1-6]|br)>"), "\n")
        val text = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
        val lines = text.lines().map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 18..450 }
        val selected = lines.filter {
            val low = it.lowercase()
            ("грн" in low || "тариф" in low || "водопостачан" in low || "водовідведен" in low || "постанов" in low) &&
                    ("м³" in low || "куб" in low || "грн" in low || "тариф" in low || "постанов" in low)
        }.distinct().take(14)
        return WaterTariffsPage(if (selected.any { "грн" in it.lowercase() }) selected else emptyList())
    }
}
