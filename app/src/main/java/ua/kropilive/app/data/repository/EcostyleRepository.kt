package ua.kropilive.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.*

/** Public Ecоstyle format-2 dataset. Does not transmit the user's address to the server. */
object EcostyleRepository {
    private const val DATA_URL = "https://www.ecostyle.ua/routes.json?v=20260916"
    private var cached: EcostyleDb? = null

    suspend fun load(): EcostyleDb = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        val connection = URL(DATA_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val db = parse(JSONObject(json))
            cached = db
            db
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: JSONObject): EcostyleDb {
        require(json.getJSONObject("meta").getInt("format") == 2) { "Unsupported Ecostyle format" }
        val streetsJson = json.getJSONArray("streets")
        val streets = (0 until streetsJson.length()).map { streetsJson.getString(it) }
        val rulesJson = json.getJSONArray("rules")
        val rules = (0 until rulesJson.length()).map { i ->
            val r = rulesJson.getJSONObject(i)
            EcostyleRule(
                kind = r.optString("k"),
                weekdays = r.optJSONArray("d").ints(),
                occurrences = r.optJSONArray("o").ints(),
                weekday = r.optInt("w", -1),
                anchor = r.optString("a").takeIf { it.isNotBlank() }
            )
        }
        val pts = json.getJSONArray("pts")
        val points = (0 until pts.length()).mapNotNull { i ->
            val p = pts.getJSONArray(i)
            val si = p.getInt(0)
            val ri = p.getInt(5)
            if (si !in streets.indices || ri !in rules.indices) null
            else EcostylePoint(si, p.optString(1), p.getDouble(2), p.getDouble(3), p.getString(4), rules[ri], p.optInt(6))
        }
        val aliases = mutableListOf<Pair<String, Int>>()
        val aliasArray = json.optJSONArray("aliases") ?: json.getJSONObject("meta").optJSONArray("aliases")
        if (aliasArray != null) for (i in 0 until aliasArray.length()) {
            val a = aliasArray.getJSONArray(i)
            aliases += a.getString(0) to a.getInt(1)
        }
        return EcostyleDb(streets, points, aliases, json.getJSONObject("meta").optString("updated"))
    }

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }
}

data class EcostyleRule(
    val kind: String,
    val weekdays: List<Int> = emptyList(),
    val occurrences: List<Int> = emptyList(),
    val weekday: Int = -1,
    val anchor: String? = null
) {
    fun isDated(): Boolean = kind == "w" || kind == "m" || kind == "d" || (kind == "eo" && anchor != null)
    fun matches(day: LocalDate): Boolean {
        val jsDay = day.dayOfWeek.value % 7 // Sunday = 0, like JavaScript Date.getDay()
        return when (kind) {
            "w" -> jsDay in weekdays
            "d" -> true
            "m" -> jsDay == weekday && ((day.dayOfMonth + 6) / 7) in occurrences
            "eo" -> anchor?.let { runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it), day) % 2L == 0L }.getOrDefault(false) } ?: true
            else -> false
        }
    }
    fun next(from: LocalDate): LocalDate? = if (!isDated()) null else (0L until 120L).asSequence().map { from.plusDays(it) }.firstOrNull { matches(it) }
    fun description(): String {
        val days = listOf("неділя", "понеділок", "вівторок", "середа", "четвер", "п’ятниця", "субота")
        return when (kind) {
            "w" -> if (weekdays.size == 7) "Щодня" else weekdays.sortedBy { (it + 6) % 7 }.joinToString(", ") { listOf("Нд", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб").getOrElse(it) { "?" } }
            "d" -> "Щодня"
            "eo" -> "Через день"
            "call" -> "За викликом"
            "m" -> occurrences.joinToString(" та ") { listOf("", "1-й", "2-й", "3-й", "4-й", "5-й").getOrElse(it) { "$it-й" } } + " ${days.getOrElse(weekday) { "день" }} місяця"
            else -> "Графік уточнюється"
        }
    }
}

data class EcostylePoint(
    val streetIndex: Int, val label: String, val lat: Double, val lon: Double,
    val type: String, val rule: EcostyleRule, val containers: Int
)

data class EcostyleSelection(
    val point: EcostylePoint?, val rule: EcostyleRule?, val description: String,
    val inferred: Boolean = false, val distanceMeters: Double? = null
)

data class EcostyleResult(
    val street: String, val house: String, val updated: String,
    val locationWarning: String?, val mixed: EcostyleSelection?,
    val bulky: EcostyleSelection?, val construction: EcostyleSelection?,
    val bunker: EcostyleSelection?, val recycling: EcostyleSelection?
)

class EcostyleDb(
    private val streets: List<String>, private val points: List<EcostylePoint>,
    private val aliases: List<Pair<String, Int>>, val updated: String
) {
    private fun norm(s: String): String = s.lowercase(java.util.Locale.ROOT)
        .replace('ё', 'е').replace('ы', 'и').replace('э', 'е').replace("ъ", "")
        .replace(Regex("['’ʼ`´]"), "")
        .replace(Regex("[/.,-]+"), " ").replace(Regex("\\s+"), " ").trim()
    private fun core(s: String): String = norm(s).replace(Regex("^(вулиця|вул|провулок|пров|проспект|просп|проїзд|пр|тупик|туп|площа|пл|бульвар|бульв|бул|б р|узвіз|шосе|набережна|наб)\\.?\\s+"), "")
    private fun houseNumber(s: String): Int? = Regex("\\d+").find(s)?.value?.toIntOrNull()
    private fun houseEq(a: String, b: String): Boolean = norm(a) == norm(b) ||
            (houseNumber(a) != null && houseNumber(a) == houseNumber(b) && norm(a).replaceFirst(Regex("\\d+"), "") == norm(b).replaceFirst(Regex("\\d+"), ""))
    private fun distance(a: Double, b: Double, c: Double, d: Double): Double {
        val x = sin(Math.toRadians(c - a) / 2)
        val y = sin(Math.toRadians(d - b) / 2)
        val h = x * x + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * y * y
        return 2 * 6371000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    private fun nearest(types: Set<String>, lat: Double, lon: Double): Pair<EcostylePoint, Double>? =
        points.asSequence().filter { it.type in types }.map { it to distance(lat, lon, it.lat, it.lon) }.minByOrNull { it.second }

    fun resolve(street: String, house: String, verifiedHouseLocation: Pair<Double, Double>? = null): EcostyleResult? {
        val query = core(street)
        if (query.isBlank()) return null
        val exact = streets.indexOfFirst { core(it) == query }
        val alias = aliases.firstOrNull { core(it.first) == query }?.second ?: -1
        val si = when {
            exact >= 0 -> exact
            alias in streets.indices -> alias
            else -> {
                val matches = streets.indices.filter { core(streets[it]).startsWith(query) }
                matches.singleOrNull() ?: return null // never silently pick an ambiguous street
            }
        }
        val streetPoints = points.filter { it.streetIndex == si }
        if (streetPoints.isEmpty()) return null
        val q = houseNumber(house)
        val exactPoint = if (house.isNotBlank()) streetPoints.firstOrNull { houseEq(it.label, house) } else null
        val near = if (exactPoint == null && q != null) streetPoints.mapNotNull { p ->
            houseNumber(p.label)?.let { n -> p to (abs(n - q) * 2 + if (n % 2 == q % 2) 0 else 1) }
        }.minByOrNull { it.second }?.first else null
        val lat = verifiedHouseLocation?.first ?: exactPoint?.lat ?: near?.lat ?: streetPoints.map { it.lat }.average()
        val lon = verifiedHouseLocation?.second ?: exactPoint?.lon ?: near?.lon ?: streetPoints.map { it.lon }.average()
        val warning = when {
            verifiedHouseLocation != null -> "Координати будинку визначено геокодером Android. Найближчий контейнер обрано за відстанню по прямій; належність до маршруту не підтверджена."
            house.isBlank() || exactPoint != null -> null
            near != null -> "Точних координат будинку $house не знайдено. Показано приблизну точку за будинком №${near.label}; найближчий контейнер може бути іншим."
            else -> "Точних координат будинку $house немає. Відстані приблизні — від центру вулиці."
        }
        val own = streetPoints.firstOrNull { it.type == "mm" && house.isNotBlank() && houseEq(it.label, house) }
        // Official map marker verified for this address. The house itself is missing
        // from routes.json; do not let an unreliable geocoder or house-number proxy
        // override the container that was visually verified on Ecostyle's map.
        // This is a narrow address-specific association, NOT a general geocoding rule.
        val confirmedPoint = if (
            core(streets[si]) == core("вул. Панаса Михалевича") &&
            norm(house).replace(" ", "") == "13"
        ) streetPoints.firstOrNull { p ->
            p.type == "mc" &&
                    (norm(p.label).contains("севастопольськ") ||
                            (abs(p.lat - 48.53491) < 0.00002 && abs(p.lon - 32.26655) < 0.00002))
        } else null
        val mix = confirmedPoint?.let {
            EcostyleSelection(it, it.rule, "Точка з офіційної карти Екостайл (підтверджено для цього будинку)")
        } ?: own?.let { EcostyleSelection(it, it.rule, "Контейнер біля будинку") }
        ?: nearest(setOf("mm", "mc", "mp"), lat, lon)?.let { (p, d) ->
            EcostyleSelection(p, p.rule, "Найближчий контейнер або точка збору" + if (verifiedHouseLocation == null && exactPoint == null) " (орієнтовно)" else " (за координатами будинку)", true, d)
        }
        fun houseRule(type: String): EcostyleSelection? {
            val candidates = streetPoints.filter { it.type == type }
            val found = candidates.firstOrNull { house.isNotBlank() && houseEq(it.label, house) }
            if (found != null) return EcostyleSelection(found, found.rule, "Графік для будинку")
            val distinct = candidates.map { it.rule }.distinct()
            return when {
                distinct.size == 1 -> EcostyleSelection(null, distinct.first(), "Графік за вулицею (точку збору для будинку не підтверджено)", true)
                candidates.isNotEmpty() -> EcostyleSelection(null, null, "Графік відрізняється в межах вулиці — уточніть в Екостайл", true)
                else -> null
            }
        }
        val bk = nearest(setOf("bk"), lat, lon)?.let { (p, d) -> EcostyleSelection(p, p.rule, "Найближчий бункер", true, d) }
        val sep = nearest(setOf("sep"), lat, lon)?.let { (p, d) -> EcostyleSelection(p, p.rule, "Найближчий пункт роздільного збору", true, d) }
        val finalWarning = if (confirmedPoint != null) {
            "Побутовий контейнер визначено за перевіреною точкою на карті Екостайл. Інші відстані можуть бути приблизними."
        } else warning
        return EcostyleResult(streets[si], house, updated, finalWarning, mix, houseRule("vg"), houseRule("bud"), bk, sep)
    }
}

fun ecostyleToday(): LocalDate = LocalDate.now(ZoneId.of("Europe/Kyiv"))
