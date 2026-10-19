package ua.kropilive.app.data.repository

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class HouseholdElectricityTariffs(
    val regular: Double,
    val heating: Double,
    val heatingLimit: Int,
    val sourceChecked: Boolean
)

object HouseholdElectricityTariffsRepository {
    const val SOURCE = "https://kresc.com.ua/person-tariffs.shtml"

    // Official prices verified on 17 October 2026. Offline fallback is
    // deliberately identified in the UI as NOT freshly verified.
    val lastVerified = HouseholdElectricityTariffs(4.32, 2.64, 2000, false)

    suspend fun load(): HouseholdElectricityTariffs = withContext(Dispatchers.IO) {
        val connection = (URL(SOURCE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6500
            readTimeout = 6500
            setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; KropiLive/1.0)")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                .toString().replace('\u00A0', ' ').lowercase(Locale.ROOT)
            // Require the official household-tariff section and all three
            // values. Never confuse the business tariff table with household prices.
            require(text.contains("побутових споживачів") &&
                Regex("""4[,.]32""").containsMatchIn(text) &&
                Regex("""2[,.]64""").containsMatchIn(text) &&
                Regex("""2\s*000|2000""").containsMatchIn(text)) {
                "Невідомий формат тарифів на сайті КОЕК"
            }
            lastVerified.copy(sourceChecked = true)
        } finally {
            connection.disconnect()
        }
    }
}
