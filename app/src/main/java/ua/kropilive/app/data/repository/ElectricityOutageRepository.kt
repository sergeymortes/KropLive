package ua.kropilive.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class OutageKind { EMERGENCY, PLANNED }

data class OutageRecord(
    val kind: OutageKind,
    val start: String,
    val end: String,
    val reason: String,
    val address: String,
    val published: String = ""
)

data class OutageResult(
    val records: List<OutageRecord>,
    val matching: List<OutageRecord>,
    val sourceUrl: String,
    val message: String = ""
)

class ElectricityOutageRepository {
    private val root = "https://kiroe.com.ua"
    private val agent = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    // This is a fallback link for the button. Fetch discovers the actual community ID.
    fun sourceUrl(kind: OutageKind, address: UserAddress): String {
        val section = if (kind == OutageKind.PLANNED) "plan" else "emergency"
        return "$root/energy/$section"
    }

    private fun get(url: String): Document = Jsoup.connect(url)
        .userAgent(agent)
        .timeout(20000)
        .followRedirects(true)
        .get()

    private fun normalized(s: String): String = s.lowercase(Locale.ROOT)
        .replace('’', '\'').replace('ʼ', '\'')
        .replace(Regex("[^\\p{L}\\p{N}/]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun communityKey(name: String): String = normalized(name)
        .replace(Regex("\\b(міська|селищна|сільська|територіальна|громада|отг|тг)\\b"), "")
        .trim().replace(Regex("\\s+"), " ")

    private fun resolveCommunityUrl(kind: OutageKind, address: UserAddress): String {
        val base = sourceUrl(kind, address)
        val page = get(base)
        val target = communityKey(address.communityName)
        if (target.isBlank()) error("У збереженій адресі немає назви громади")

        // The energy pages have their OWN community directory. Never assume that
        // communityId from /electricity-blackout has the same numeric value.
        val options = page.select("select option")
        val candidates = options.mapNotNull { option ->
            val label = communityKey(option.text())
            val value = option.attr("value").trim()
            if (label.isBlank() || value.isBlank()) null else Triple(label, value, option.attr("data-url"))
        }
        val match = candidates.firstOrNull { it.first == target }
            ?: candidates.singleOrNull { it.first.contains(target) || target.contains(it.first) }
            ?: error("Громаду «${address.communityName}» не знайдено в довіднику відключень на сайті")

        val value = match.second
        val route = when {
            value.matches(Regex("\\d+")) -> "$base/$value"
            value.matches(Regex("/energy/(plan|emergency)/\\d+")) -> "$root$value"
            value.matches(Regex("https://kiroe\\.com\\.ua/energy/(plan|emergency)/\\d+")) -> value
            match.third.matches(Regex("/energy/(plan|emergency)/\\d+")) -> "$root${match.third}"
            else -> error("Сайт повернув невідомий ідентифікатор громади: $value")
        }
        return route
    }

    suspend fun fetch(kind: OutageKind, address: UserAddress): OutageResult = withContext(Dispatchers.IO) {
        require(address.hasFullAddress) { "Спочатку збережіть повну адресу" }
        val url = resolveCommunityUrl(kind, address)
        val doc = get(url)
        val noData = doc.text().contains("відомості про відключення відсутні", ignoreCase = true)
        val table = doc.select("table").firstOrNull {
            val header = it.text().lowercase(Locale.ROOT)
            header.contains("початок виконання") && header.contains("закінчення виконання")
        }
        if (table == null && !noData) error("Не вдалося прочитати таблицю на сайті. Формат сторінки міг змінитися.")

        val records = mutableListOf<OutageRecord>()
        if (table != null) {
            var pending: OutageRecord? = null
            for (tr in table.select("tr")) {
                // Jsoup 1.18 does not support the CSS selector ':scope > td'.
                val texts = tr.children().filter { it.tagName() == "td" }.map { it.text().trim() }
                if (texts.isEmpty()) continue
                val startIndex = texts.indexOfFirst {
                    it.matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2}.*"))
                }
                if (startIndex >= 0) {
                    pending?.let(records::add)
                    pending = OutageRecord(
                        kind = kind,
                        start = texts[startIndex],
                        end = texts.getOrElse(startIndex + 1) { "" },
                        reason = texts.getOrElse(startIndex + 2) { "" },
                        address = if (startIndex > 0) texts[0] else "",
                        published = texts.lastOrNull().orEmpty()
                    )
                } else if (pending != null) {
                    val line = texts.joinToString(" ").trim()
                    if (line.isNotBlank()) pending = pending.copy(address = (pending.address + "\n" + line).trim())
                }
            }
            pending?.let(records::add)
        }
        val visible = records.filter { record ->
            val end = parseDate(record.end)
            end == null || end.isAfter(LocalDateTime.now())
        }
        OutageResult(
            records = visible,
            matching = visible.filter { matchesAddress(it.address, address) },
            sourceUrl = url,
            message = if (noData) "На сайті немає відомостей про відключення" else ""
        )
    }

    private fun parseDate(value: String): LocalDateTime? = try {
        LocalDateTime.parse(value.take(16), DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    } catch (_: Exception) { null }

    // Canonical form for comparing a street name without its type.
    private fun streetKey(value: String): String = normalized(value)
        .replace(Regex("\\b(вул|вулиця|пров|провулок|просп|проспект|пл|площа|бульвар|бульв|б р|вулиці)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun houseKey(value: String): String = normalized(value)
        .replace(Regex("\\s+"), "")
        .replace("корпус", "к")

    private fun matchesAddress(raw: String, address: UserAddress): Boolean {
        if (raw.isBlank()) return false
        val targetStreet = streetKey(address.street)
        val targetHouse = houseKey(address.building)
        if (targetStreet.isBlank() || targetHouse.isBlank()) return false

        // Each street and its houses are presented as "City, вул. Street: 1, 2, 3".
        // The check is performed per street entry, never against the entire table row.
        val parts = raw.lines().flatMap { it.split(';') }
        return parts.any { part ->
            val colon = part.lastIndexOf(':')
            if (colon < 0) return@any false
            val location = streetKey(part.substring(0, colon))
            val housesText = part.substring(colon + 1)
            // Word boundaries prevent "Миру" from matching "Перемиру".
            val streetMatches = Regex(
                "(^|\\s)" + Regex.escape(targetStreet) + "($|\\s)"
            ).containsMatchIn(location)
            if (!streetMatches) return@any false

            // Compare a full house token, not a substring: 13 != 113 or 13А.
            housesText.split(',', '，', ';').any { item ->
                val candidate = houseKey(item.trim())
                candidate == targetHouse ||
                        // A single entry can contain "буд. 13" or "№13".
                        candidate.removePrefix("будинок").removePrefix("буд")
                            .removePrefix("№") == targetHouse
            }
        }
    }
}
