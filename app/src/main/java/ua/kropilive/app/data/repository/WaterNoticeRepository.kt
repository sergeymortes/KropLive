package ua.kropilive.app.data.repository

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class WaterNotice(val id: String, val date: String, val text: String, val url: String, val affected: Boolean, val restored: Boolean)
data class WaterReport(val notices: List<WaterNotice>, val relevant: List<WaterNotice>, val addressSpecified: Boolean) {
    val hasReportedIssue: Boolean get() = relevant.firstOrNull()?.let { it.affected && !it.restored } == true
}

object WaterNoticeRepository {
    const val OFFICIAL_SITE = "https://dnipro-kirovograd.com.ua"
    const val OFFICIAL_POSTS = "https://t.me/s/dniprokirovograd"

    // Shared by startup, Home and Water tab. Never request Telegram three times at once.
    private val fetchMutex = Mutex()
    @Volatile private var cachedPosts: List<WaterNotice> = emptyList()
    @Volatile private var fetchedAt: Long = 0L
    private const val CACHE_MS = 5 * 60_000L

    suspend fun load(address: UserAddress): WaterReport {
        val posts = fetchMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedPosts.isNotEmpty() && now - fetchedAt in 0..CACHE_MS) {
                cachedPosts
            } else {
                try {
                    val fresh = fetchPostsWithRetry()
                    cachedPosts = fresh
                    fetchedAt = System.currentTimeMillis()
                    fresh
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Old posts are allowed as news, but are not used for an active-outage claim.
                    if (cachedPosts.isEmpty()) throw e
                    cachedPosts
                }
            }
        }
        val today = LocalDate.now()
        val todays = posts.filter { it.date == today.toString() }
        val display = (if (todays.isNotEmpty()) todays else posts).take(3)
        val specified = address.street.isNotBlank() && address.building.isNotBlank()
        val recentEnough = System.currentTimeMillis() - fetchedAt in 0..CACHE_MS
        val relevant = if (specified && recentEnough) posts.filter {
            it.date == today.toString() && matches(it.text, address)
        } else emptyList()
        return WaterReport(display, relevant, specified)
    }

    private suspend fun fetchPostsWithRetry(): List<WaterNotice> {
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                return fetchPosts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt == 0) delay(1500L)
            }
        }
        throw last ?: IllegalStateException("Немає повідомлень")
    }

    private suspend fun fetchPosts(): List<WaterNotice> = withContext(Dispatchers.IO) {
        val connection = (URL(OFFICIAL_POSTS).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "uk-UA,uk;q=0.9,en;q=0.8")
        }
        val html = try {
            if (connection.responseCode !in 200..299) error("Telegram HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
        parse(html).ifEmpty { error("Не вдалося розпізнати повідомлення") }
    }

    private fun norm(s: String) = s.lowercase(Locale("uk", "UA"))
        .replace('і', 'i').replace('ї', 'i').replace('є', 'е').replace('ґ', 'г')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    fun matches(text: String, address: UserAddress): Boolean {
        val street = norm(address.street).replace(Regex("^(вул|вулиця|пров|провулок|проспект) "), "")
        if (street.length < 4) return false
        val source = norm(text)
        if (!listOf("споживачiв", "за адресами", "водопостачання").any { source.contains(it) }) return false
        val pos = Regex("(?:^| )" + Regex.escape(street) + "(?: |$)").find(source) ?: return false
        // Conservative: if a specific house list is present, only flag a listed house.
        val tail = source.substring(pos.range.last + 1).take(120)
        val end = Regex(" (вул|пров|проспект) ").find(tail)?.range?.first ?: tail.length
        val section = tail.take(end)
        val numbers = Regex("\\b\\d+[a-zа-я]?(?:/\\d+)?\\b").findAll(section).map { it.value }.toList()
        val house = norm(address.building).replace(" ", "")
        return numbers.isEmpty() || numbers.any { it == house } || section.contains("-")
    }

    fun parse(html: String): List<WaterNotice> {
        return html.split(Regex("<div[^>]*tgme_widget_message_wrap[^>]*>")).drop(1).mapNotNull { block ->
            val id = Regex("data-post=\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val raw = Regex("<div[^>]*class=\"[^\"]*tgme_widget_message_text[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val text = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' ').trim()
            val date = Regex("datetime=\"(\\d{4}-\\d{2}-\\d{2})").find(block)?.groupValues?.get(1) ?: ""
            val normalized = norm(text)
            val restored = listOf("вiдновлен", "завершен", "вiдновлено").any { normalized.contains(it) }
            val affected = listOf("обмежен", "припинен", "без водопостачання", "вiдсутн", "вiдключен").any { normalized.contains(it) }
            if (text.length < 30 || (!affected && !restored)) return@mapNotNull null
            WaterNotice(id, date, text, "https://t.me/$id", affected, restored)
        }.distinctBy { it.id }.sortedByDescending { it.id.substringAfterLast('/').toLongOrNull() ?: 0L }
    }
}
