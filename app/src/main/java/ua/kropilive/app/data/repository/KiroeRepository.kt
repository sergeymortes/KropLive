
package ua.kropilive.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class KiroeHouse(
    val id: String,
    val name: String,
    val group: String
)

class KiroeRepository {

    private val baseUrl = "https://kiroe.com.ua/electricity-blackout"
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)

    private var ajaxToken: String? = null

    suspend fun loadHouses(streetId: String): Result<List<KiroeHouse>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(streetId.matches(Regex("\\d+"))) {
                    "Неверный StreetId"
                }

                if (ajaxToken == null) {
                    initializeSession()
                }

                requestHouses(streetId)
            }
        }

    private fun initializeSession() {
        val connection = URL(baseUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        try {
            val html = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val headers = connection.headerFields
                .filterKeys { it != null }

            cookies.put(URL(baseUrl).toURI(), headers)

            // Ищем значение ajaxToken в настройках страницы.
            val patterns = listOf(
                Regex("""ajaxToken\s*:\s*['"]([^'"]+)['"]"""),
                Regex("""["']ajaxToken["']\s*:\s*['"]([^'"]+)['"]""")
            )

            ajaxToken = patterns.firstNotNullOfOrNull { regex ->
                regex.find(html)?.groupValues?.getOrNull(1)
            }

            if (ajaxToken.isNullOrBlank()) {
                error("Не удалось найти ajaxToken на странице облэнерго")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestHouses(streetId: String): List<KiroeHouse> {
        val url = URL("$baseUrl/house/$streetId?t=${System.currentTimeMillis()}")
        val connection = url.openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.doOutput = true
        connection.setRequestProperty(
            "Content-Type",
            "application/x-www-form-urlencoded; charset=UTF-8"
        )
        connection.setRequestProperty(
            "X-Requested-With",
            "XMLHttpRequest"
        )

        val cookieHeaders = cookies.get(url.toURI(), emptyMap())
        android.util.Log.d(
            "KIROE_TEST",
            "Cookies перед запросом: " +
                    if (cookieHeaders["Cookie"].isNullOrEmpty()) {
                        "ОТСУТСТВУЮТ"
                    } else {
                        "ПРИСУТСТВУЮТ"
                    }
        )
        cookieHeaders.forEach { (key, values) ->
            if (key.equals("Cookie", ignoreCase = true)) {
                connection.setRequestProperty(key, values.joinToString("; "))
            }
        }

        val body = "ajax=1&token=" +
                URLEncoder.encode(ajaxToken.orEmpty(), "UTF-8")

        try {
            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode

            if (responseCode !in 200..299) {
                val errorText = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                android.util.Log.d(
                    "KIROE_TEST",
                    "HTTP $responseCode, тип ответа: " +
                            connection.contentType +
                            ", размер ответа: " +
                            errorText.length
                )
                val pageTitle = Regex(
                    "<title[^>]*>(.*?)</title>",
                    RegexOption.IGNORE_CASE
                ).find(errorText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?: "Заголовок отсутствует"

                android.util.Log.d(
                    "KIROE_TEST",
                    "Заголовок страницы ошибки: $pageTitle"
                )

                error("Ошибка сервера: HTTP $responseCode")
            }

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val json = JSONObject(response)

            if (!json.optBoolean("success")) {
                error("Сервер не подтвердил запрос")
            }

            val data = json.getJSONArray("data")

            return buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)

                    if (item.optString("visible", "1") != "1") {
                        continue
                    }

                    add(
                        KiroeHouse(
                            id = item.optString("HouseId"),
                            name = item.optString("HouseName"),
                            group = item.optString("chergGpv")
                        )
                    )
                }
            }.distinctBy { it.id }
        } finally {
            connection.disconnect()
        }
    }
}
