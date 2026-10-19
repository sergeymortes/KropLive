package ua.kropilive.app.data.repository

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/** 30-minute values: 1 = outage, 0 = no scheduled outage, 10 = switching. */
data class TelegramQueueGraph(
    val queue: String,
    val date: String,
    val times: Map<String, String>,
    val postTime: String,
    val cached: Boolean
)

/** Result of a LIVE Telegram page check; never inferred from cached graphs. */
sealed class TelegramGraphCheck {
    data class Published(val graph: TelegramQueueGraph) : TelegramGraphCheck()
    /** No graph for today was found among the messages actually returned by Telegram. */
    data class NoCurrentGraphInVisiblePosts(
        val lastPostTime: String,
        val visiblePostCount: Int,
        val lastPostDate: String
    ) : TelegramGraphCheck()
    object Failed : TelegramGraphCheck()
}

object TelegramBlackoutRepository {
    const val SOURCE = "https://t.me/SvitloKropyvnytskyiMisto"
    private const val PUBLIC = "https://t.me/s/SvitloKropyvnytskyiMisto"
    private const val PREFS = "telegram_queue_graph_v2"
    private val kyivZone = ZoneId.of("Europe/Kyiv")
    private val range = Regex("""([01]?\d|2[0-3]):([0-5]\d)\s*[-–—]\s*([01]?\d|2[0-4]):([0-5]\d)""")
    private val datePattern = Regex("""\b([0-3]?\d)\.([01]?\d)\.(20\d{2})\b""")
    private val queueLine = Regex("""(?i)^\s*(?:черга|підчерга)\s*([1-6])[.,]([12])\s*[:：-]\s*(.*)$""")
    private fun normalized(q: String) = q.trim().replace(',', '.').replace(Regex("""(?i)^черга\s*"""), "")

    fun cached(context: Context, queue: String): TelegramQueueGraph? {
        val q = normalized(queue)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(q, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val json = obj.getJSONObject("times")
            val times = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                times[key] = json.getString(key)
            }
            TelegramQueueGraph(q, obj.getString("date"), times, obj.optString("postTime"), true)
        } catch (_: Exception) { null }
    }

    private fun parseDate(text: String): LocalDate? {
        val match = datePattern.find(text) ?: return null
        return runCatching {
            LocalDate.of(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt())
        }.getOrNull()
    }

    private fun extract(text: String, queue: String): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        var foundQueue = false
        for (line in text.lines()) {
            val match = queueLine.find(line) ?: continue
            if ("${match.groupValues[1]}.${match.groupValues[2]}" != queue) continue
            foundQueue = true
            val intervals = range.findAll(match.groupValues[3]).toList()
            if (intervals.isEmpty()) return null
            for (m in intervals) {
                val start = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
                val end = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
                if (start >= end || end > 1440) return null
                for (slot in 0 until 48) {
                    val a = slot * 30
                    val b = a + 30
                    if (start < b && end > a) {
                        val key = "%02d:%02d".format(Locale.US, a / 60, a % 60)
                        result[key] = if (start <= a && end >= b) "1" else "10"
                    }
                }
            }
        }
        if (!foundQueue || result.isEmpty()) return null
        for (slot in 0 until 48) {
            val key = "%02d:%02d".format(Locale.US, slot / 2, (slot % 2) * 30)
            result.putIfAbsent(key, "0")
        }
        return result
    }

    private fun save(context: Context, graph: TelegramQueueGraph) {
        val json = JSONObject()
        graph.times.forEach { (k, v) -> json.put(k, v) }
        val stored = JSONObject()
            .put("date", graph.date)
            .put("postTime", graph.postTime)
            .put("times", json)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(graph.queue, stored.toString()).apply()
    }

    /**
     * Checks the Telegram public page and records the date of the newest visible post.
     * A missing graph is NOT automatically proof that no schedule exists: Telegram's
     * public HTML page may expose only a limited window of channel history.
     */
    suspend fun check(context: Context, queue: String): TelegramGraphCheck = withContext(Dispatchers.IO) {
        val q = normalized(queue)
        if (!Regex("""[1-6]\.[12]""").matches(q)) {
            return@withContext TelegramGraphCheck.Failed
        }
        try {
            val doc = Jsoup.connect(PUBLIC)
                .userAgent("Mozilla/5.0 (Android; KropiLive)")
                .timeout(8000)
                .get()
            val posts = doc.select(".tgme_widget_message_wrap")
            if (posts.isEmpty()) return@withContext TelegramGraphCheck.Failed
            val today = LocalDate.now(kyivZone)
            var newestInstant: Instant? = null
            var newestTime = ""
            var parsedPosts = 0
            val candidates = mutableListOf<TelegramQueueGraph>()
            for (post in posts) {
                val time = post.selectFirst("time[datetime]")?.attr("datetime") ?: continue
                val instant = runCatching { OffsetDateTime.parse(time).toInstant() }.getOrNull() ?: continue
                parsedPosts++
                if (newestInstant == null || instant.isAfter(newestInstant)) {
                    newestInstant = instant
                    newestTime = time
                }
                val message = post.selectFirst(".tgme_widget_message_text")?.wholeText()?.trim() ?: continue
                if (parseDate(message) != today) continue
                val slots = extract(message, q) ?: continue
                candidates += TelegramQueueGraph(q, today.toString(), slots, time, false)
            }
            if (parsedPosts == 0 || newestInstant == null) {
                return@withContext TelegramGraphCheck.Failed
            }
            val best = candidates.maxByOrNull {
                runCatching { OffsetDateTime.parse(it.postTime).toInstant() }.getOrDefault(Instant.EPOCH)
            }
            if (best != null) {
                save(context, best)
                TelegramGraphCheck.Published(best)
            } else {
                TelegramGraphCheck.NoCurrentGraphInVisiblePosts(
                    lastPostTime = newestTime,
                    visiblePostCount = parsedPosts,
                    lastPostDate = newestInstant.atZone(kyivZone).toLocalDate().toString()
                )
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { TelegramGraphCheck.Failed }
    }

    /** Backward-compatible API used by the existing home screen and background jobs. */
    suspend fun load(context: Context, queue: String): TelegramQueueGraph? {
        return when (val result = check(context, queue)) {
            is TelegramGraphCheck.Published -> result.graph
            is TelegramGraphCheck.NoCurrentGraphInVisiblePosts,
            TelegramGraphCheck.Failed -> cached(context, queue)
        }
    }
}