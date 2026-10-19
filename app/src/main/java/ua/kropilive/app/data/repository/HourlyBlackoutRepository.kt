package ua.kropilive.app.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * ГПВ:
 * 0 = світло є
 * 1 = світла немає
 * 10 = можливе перемикання
 */
data class HourlyGraph(
    val group: String,
    val date: String,
    val times: Map<String, String>,
    val cached: Boolean
)

/**
 * Результат живої перевірки офіційного джерела.
 * Відсутність опублікованого графіка НЕ означає відсутність аварійних відключень.
 */
sealed class HourlyGraphCheck {
    data class Published(val graph: HourlyGraph) : HourlyGraphCheck()
    object NotPublished : HourlyGraphCheck()
    object Failed : HourlyGraphCheck()
}

object HourlyBlackoutRepository {

    private const val BASE =
        "https://kiroe.com.ua/electricity-blackout"

    private const val PREFS =
        "hourly_blackout_snapshots_v1"

    private const val SELECTED_PREFS =
        "electricity_selected_graph_v1"

    private val kyivZone =
        ZoneId.of("Europe/Kyiv")

    private fun request(
        url: String,
        form: String? = null,
        cookies: CookieManager? = null
    ): String {

        val conn = (
                URL(url).openConnection() as HttpURLConnection
                ).apply {

                connectTimeout = 8500
                readTimeout = 8500

                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) " +
                            "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                )

                setRequestProperty(
                    "Accept",
                    "application/json, text/html, */*"
                )

                setRequestProperty(
                    "Referer",
                    BASE
                )

                cookies?.get(URL(url).toURI(), emptyMap())
                    ?.forEach { (name, values) ->
                        if (name.equals("Cookie", ignoreCase = true) && values.isNotEmpty()) {
                            setRequestProperty("Cookie", values.joinToString("; "))
                        }
                    }

                if (form != null) {
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    requestMethod = "POST"
                    doOutput = true

                    setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                }
            }

        try {

            if (form != null) {
                conn.outputStream.use {
                    it.write(
                        form.toByteArray(Charsets.UTF_8)
                    )
                }
            }

            if (conn.responseCode !in 200..299) {
                error("HTTP ${conn.responseCode}")
            }

            cookies?.put(URL(url).toURI(), conn.headerFields.filterKeys { it != null })
            return conn.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

        } finally {
            conn.disconnect()
        }
    }

    private fun normalizedQueue(
        queue: String
    ): String {

        return queue
            .trim()
            .replace(
                Regex(
                    """(?i)^(черга|підчерга|група)\s*[:№#-]?\s*"""
                ),
                ""
            )
            .replace(',', '.')
            .replace(' ', '.')
    }

    private fun parse(
        json: JSONObject,
        queue: String,
        date: String,
        cached: Boolean
    ): HourlyGraph? {

        val data =
            json.optJSONObject("data") ?: return null

        val groups =
            data.optJSONObject("groups") ?: data

        val q = normalizedQueue(queue)

        val variants = listOf(
            q,
            queue.trim(),
            q.replace('.', '-'),
            q.replace('.', '_')
        )

        var item: JSONObject? = null

        for (key in variants) {
            item = groups.optJSONObject(key)

            if (item != null) {
                break
            }
        }

        if (item == null) {

            val keys = groups.keys()

            while (keys.hasNext()) {

                val key = keys.next()

                if (normalizedQueue(key) == q) {
                    item = groups.optJSONObject(key)
                    break
                }
            }
        }

        val times =
            item?.optJSONObject("times") ?: return null

        val map = mutableMapOf<String, String>()

        for (hour in 0..23) {
            for (minute in listOf("00", "30")) {

                val key = "%02d:%s".format(
                    Locale.US,
                    hour,
                    minute
                )

                if (
                    times.has(key) &&
                    !times.isNull(key)
                ) {
                    map[key] = times.optString(key)
                }
            }
        }

        return if (map.isEmpty()) {
            null
        } else {
            HourlyGraph(
                group = q,
                date = date,
                times = map,
                cached = cached
            )
        }
    }

    private fun readCached(
        context: Context,
        queue: String
    ): HourlyGraph? {

        val q = normalizedQueue(queue)

        val raw = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(
            "group:$q",
            null
        ) ?: return null

        return try {

            val obj = JSONObject(raw)

            val date = obj.optString("date")

            val times =
                obj.optJSONObject("times") ?: return null

            val values =
                mutableMapOf<String, String>()

            val keys = times.keys()

            while (keys.hasNext()) {

                val key = keys.next()
                values[key] = times.optString(key)
            }

            if (values.isEmpty()) {
                null
            } else {
                HourlyGraph(
                    group = q,
                    date = date,
                    times = values,
                    cached = true
                )
            }

        } catch (_: Exception) {
            null
        }
    }

    /**
     * Последний график официального сайта.
     * Может быть устаревшим.
     */
    fun cached(
        context: Context,
        queue: String
    ): HourlyGraph? {

        return if (queue.isBlank()) {
            null
        } else {
            readCached(context, queue)
        }
    }

    /**
     * Сохраняем именно тот график,
     * который выбрала фоновая задача.
     *
     * Это может быть официальный сайт
     * либо резервный Telegram.
     *
     * Сохранение синхронное, чтобы Receiver
     * не прочитал предыдущую версию.
     */
    fun saveSelectedGraph(
        context: Context,
        graph: HourlyGraph
    ): Boolean {

        if (
            graph.cached ||
            graph.times.isEmpty() ||
            graph.group.isBlank()
        ) {
            return false
        }

        val today =
            LocalDate.now(kyivZone).toString()

        if (graph.date != today) {
            return false
        }

        val times = JSONObject()

        for ((key, value) in graph.times) {
            times.put(key, value)
        }

        val snapshot = JSONObject()
            .put(
                "queue",
                normalizedQueue(graph.group)
            )
            .put("date", graph.date)
            .put("times", times)
            .put(
                "receivedAt",
                System.currentTimeMillis()
            )

        return context.getSharedPreferences(
            SELECTED_PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putString(
                "selected",
                snapshot.toString()
            )
            .commit()
    }

    /**
     * Читаем последний график, выбранный
     * фоновой задачей.
     *
     * maxAgeMillis ограничивает возраст
     * данных относительно момента сохранения.
     */
    fun selectedGraph(
        context: Context,
        queue: String,
        maxAgeMillis: Long
    ): HourlyGraph? {

        if (
            queue.isBlank() ||
            maxAgeMillis <= 0L
        ) {
            return null
        }

        val raw = context.getSharedPreferences(
            SELECTED_PREFS,
            Context.MODE_PRIVATE
        ).getString(
            "selected",
            null
        ) ?: return null

        return try {

            val obj = JSONObject(raw)

            val storedQueue =
                obj.optString("queue")

            val date =
                obj.optString("date")

            val receivedAt =
                obj.optLong("receivedAt", -1L)

            val now =
                System.currentTimeMillis()

            if (
                normalizedQueue(storedQueue) !=
                normalizedQueue(queue)
            ) {
                return null
            }

            if (
                date != LocalDate.now(
                    kyivZone
                ).toString()
            ) {
                return null
            }

            if (
                receivedAt <= 0L ||
                receivedAt > now ||
                now - receivedAt > maxAgeMillis
            ) {
                return null
            }

            val times =
                obj.optJSONObject("times")
                    ?: return null

            val values =
                mutableMapOf<String, String>()

            val keys = times.keys()

            while (keys.hasNext()) {

                val key = keys.next()

                values[key] =
                    times.optString(key)
            }

            if (values.isEmpty()) {
                null
            } else {
                HourlyGraph(
                    group = normalizedQueue(storedQueue),
                    date = date,
                    times = values,
                    cached = false
                )
            }

        } catch (_: Exception) {
            null
        }
    }

    /**
     * The site sometimes returns a queue plus a textual no-outage notice
     * instead of a 48-slot graph. Accept only an explicit negative notice;
     * a missing/empty graph is never proof of no outages.
     */
    private fun explicitlyNoSchedule(value: Any?, queue: String, depth: Int = 0, queueContext: Boolean = false): Boolean {
        if (value == null || depth > 8) return false
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (explicitlyNoSchedule(value.opt(key), queue, depth + 1, queueContext)) return true
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until value.length()) {
                    if (explicitlyNoSchedule(value.opt(i), queue, depth + 1, queueContext)) return true
                }
            }
            is String -> {
                val message = org.jsoup.Jsoup.parse(value).text()
                    .lowercase(Locale.ROOT)
                    .replace('’', '\'')
                    .replace(Regex("""\s+"""), " ")
                val noOutages = listOf(
                    "відключень немає", "відключення відсутні",
                    "відключень не заплановано", "відключення не заплановані",
                    "графіки не застосовуються", "графік не застосовується",
                    "графіки не діють", "графік не діє",
                    "відключень не передбачено"
                ).any { message.contains(it) }
                if (noOutages) {
                    // Explicit no-outage messages are valid only if tied to
                    // the selected queue, or in a queue-specific JSON object.
                    val normalizedText = message.replace(',', '.')
                    if (queueContext || normalizedText.contains(queue)) return true
                }
            }
        }
        return false
    }

    /**
     * Жива перевірка ГПВ. Не підміняємо результат кешем:
     * відсутність графіка і помилка мережі — різні стани.
     */
    suspend fun check(
        context: Context,
        queue: String
    ): HourlyGraphCheck = withContext(Dispatchers.IO) {
        if (queue.isBlank()) return@withContext HourlyGraphCheck.Failed

        val today = LocalDate.now(kyivZone).toString()
        try {
            // The GPV AJAX endpoint shares the same session as the page.
            // The address picker already preserves these cookies.
            val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
            val html = request(BASE, cookies = cookies)
            val token = Regex(
                """ajaxToken\s*:\s*['"]([^'"]+)['"]"""
            ).find(html)?.groupValues?.get(1)
                ?: error("Токен запиту не знайдено")

            val body = "ajax=1&token=" + URLEncoder.encode(token, "UTF-8")
            val response = JSONObject(
                request(
                    "$BASE/actual-gpv-graphs?t=${System.currentTimeMillis()}",
                    body,
                    cookies
                )
            )

            // HTTP 200 alone does not prove that GPV was not published.
            // Validate the response shape before returning NotPublished.
            val data = response.optJSONObject("data")
                ?: return@withContext HourlyGraphCheck.Failed
            val groups = data.optJSONObject("groups") ?: data
            val q = normalizedQueue(queue)
            val keys = groups.keys()
            var matchingEntry: JSONObject? = null
            var hasRecognizableGraph = false
            while (keys.hasNext()) {
                val key = keys.next()
                val item = groups.optJSONObject(key) ?: continue
                if (item.optJSONObject("times") != null) {
                    hasRecognizableGraph = true
                }
                if (normalizedQueue(key) == q) matchingEntry = item
            }
            // A queue-specific explicit text notice is a legitimate negative
            // result even when there are no published hourly slots.
            if (matchingEntry != null &&
                explicitlyNoSchedule(matchingEntry, q, queueContext = true)
            ) return@withContext HourlyGraphCheck.NotPublished
            if (explicitlyNoSchedule(response, q)) {
                return@withContext HourlyGraphCheck.NotPublished
            }

            // Unknown/empty schema or malformed matching entry is not
            // evidence that there are no scheduled outages.
            if (!hasRecognizableGraph ||
                (matchingEntry != null && matchingEntry.optJSONObject("times") == null)
            ) return@withContext HourlyGraphCheck.Failed

            val graph = parse(response, queue, today, cached = false)
            if (graph == null) {
                if (matchingEntry != null) HourlyGraphCheck.Failed
                else HourlyGraphCheck.NotPublished
            } else {
                val values = JSONObject()
                graph.times.forEach { (key, value) -> values.put(key, value) }
                val stored = JSONObject()
                    .put("date", today)
                    .put("times", values)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("group:${graph.group}", stored.toString())
                    .apply()
                HourlyGraphCheck.Published(graph)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KropiLive_GPV", "Official GPV check failed: ${e.javaClass.simpleName}: ${e.message}")
            HourlyGraphCheck.Failed
        }
    }

    /**
     * Зворотна сумісність: для наявних екранів і фонових задач
     * повертаємо кеш, якщо живий графік недоступний.
     */
    suspend fun load(
        context: Context,
        queue: String
    ): HourlyGraph? {
        if (queue.isBlank()) return null
        return when (val result = check(context, queue)) {
            is HourlyGraphCheck.Published -> result.graph
            HourlyGraphCheck.NotPublished,
            HourlyGraphCheck.Failed -> readCached(context, queue)
        }
    }
}
