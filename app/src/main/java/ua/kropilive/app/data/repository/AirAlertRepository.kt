package ua.kropilive.app.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

enum class AirAlertLevel {
    GREEN,
    YELLOW,
    RED,
    UNKNOWN
}

data class AirThreat(
    val id: String,
    val type: String,
    val title: String,
    val region: String,
    val district: String,
    val locality: String,
    val latitude: Double?,
    val longitude: Double?,
    val heading: Double?,
    val confidenceLevel: String,
    val updatedAt: String,
    val advisory: Boolean,
    val areaOnly: Boolean,
    val explanation: String,
    val sourceCount: Int = 0,
    val uncertaintyKm: Double? = null,
    val positionQuality: String = "",
    val lifecycle: String = "",
    val count: Int = 1,
    val trail: List<Pair<Double, Double>> = emptyList()
)

data class NeptunAreaNotice(
    val name: String,
    val oblast: String,
    val level: String,
    val since: String,
    val reasons: List<String>,
    val isOblast: Boolean
)

data class AirAlertState(
    val district: String,
    val active: Boolean,
    val partialElsewhere: Boolean,
    val startedAt: Long?,
    val endedAt: Long?,
    val sourceUpdated: String,
    val level: AirAlertLevel =
        if (active) AirAlertLevel.RED else AirAlertLevel.UNKNOWN,
    val threats: List<AirThreat> = emptyList(),
    val allThreats: List<AirThreat> = emptyList(),
    val areaNotices: List<NeptunAreaNotice> = emptyList(),
    val threatsUpdated: String = ""
)

object AirAlertRepository {

    // Shared, in-process state. The monitor service will publish validated
    // WebSocket events here; HomeScreen can observe the same source later.
    private val mutableStreamState = MutableStateFlow<AirAlertState?>(null)
    val streamState: StateFlow<AirAlertState?> = mutableStreamState.asStateFlow()

    fun publishStreamState(state: AirAlertState) {
        if (state.district.isBlank() || state.level == AirAlertLevel.UNKNOWN) return
        val timestamp = parseTimestamp(state.sourceUpdated) ?: return
        val now = Instant.now().epochSecond
        if (timestamp > now + MAX_CLOCK_SKEW_SECONDS ||
            now - timestamp !in 0L..MAX_ALERT_AGE_SECONDS
        ) return
        mutableStreamState.value = state
    }


    private const val ALERTS_ENDPOINT =
        "https://neptun.in.ua/api/v1/alerts"

    private const val THREATS_ENDPOINT =
        "https://neptun.in.ua/api/v1/threats"

    const val SOURCE = "https://neptun.in.ua/"

    private const val MAX_THREAT_AGE_SECONDS = 15L * 60L
    private const val MAX_ALERT_AGE_SECONDS = 150L
    private const val MAX_CLOCK_SKEW_SECONDS = 60L

    // Only a recently verified API response may be reused on the next screen.
    // Never treat a saved GREEN result as current after the source's freshness window.
    private const val CACHE_PREFS = "neptun_verified_snapshot_v1"

    suspend fun loadAndCache(district: String?, context: Context): AirAlertState {
        val state = load(district)
        if (state.level != AirAlertLevel.UNKNOWN) {
            val sourceSeconds = parseTimestamp(state.sourceUpdated)
            val now = Instant.now().epochSecond
            if (sourceSeconds != null &&
                sourceSeconds <= now + MAX_CLOCK_SKEW_SECONDS &&
                now - sourceSeconds in 0L..MAX_ALERT_AGE_SECONDS
            ) {
                withContext(Dispatchers.IO) {
                    context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString("district", state.district)
                        .putString("level", state.level.name)
                        .putString("sourceUpdated", state.sourceUpdated)
                        .putLong("savedAt", now)
                        .putLong("startedAt", state.startedAt ?: -1L)
                        .putBoolean("partialElsewhere", state.partialElsewhere)
                        .apply()
                }
            }
        }
        return state
    }

    fun cached(context: Context, district: String?): AirAlertState? {
        if (district.isNullOrBlank()) return null
        val prefs = context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("district", null) != district) return null
        val level = runCatching {
            AirAlertLevel.valueOf(prefs.getString("level", "") ?: "")
        }.getOrNull() ?: return null
        if (level == AirAlertLevel.UNKNOWN) return null
        val sourceUpdated = prefs.getString("sourceUpdated", null) ?: return null
        val sourceSeconds = parseTimestamp(sourceUpdated) ?: return null
        val now = Instant.now().epochSecond
        val savedAt = prefs.getLong("savedAt", -1L)
        if (savedAt <= 0L || savedAt > now + MAX_CLOCK_SKEW_SECONDS ||
            now - savedAt !in 0L..MAX_ALERT_AGE_SECONDS ||
            sourceSeconds > now + MAX_CLOCK_SKEW_SECONDS ||
            now - sourceSeconds !in 0L..MAX_ALERT_AGE_SECONDS
        ) return null
        val startedAt = prefs.getLong("startedAt", -1L).takeIf { it >= 0L }
        return AirAlertState(
            district = district,
            active = level == AirAlertLevel.RED,
            partialElsewhere = prefs.getBoolean("partialElsewhere", false),
            startedAt = startedAt,
            endedAt = null,
            sourceUpdated = sourceUpdated,
            level = level
        )
    }

    private val districts = mapOf(
        "голованів" to "Голованівський район",
        "голованов" to "Голованівський район",
        "олександрій" to "Олександрійський район",
        "александрий" to "Олександрійський район",
        "олександрія" to "Олександрійський район",
        "новоукраїн" to "Новоукраїнський район",
        "новоукраин" to "Новоукраїнський район",
        "кропивниць" to "Кропивницький район",
        "кропивницк" to "Кропивницький район",
        "кировоград" to "Кропивницький район"
    )

    fun districtFor(address: UserAddress): String? {
        val location =
            "${address.cityName} ${address.communityName}"
                .lowercase(Locale.ROOT)

        return districts.entries.firstOrNull {
            it.key in location
        }?.value
    }

    suspend fun load(
        district: String?
    ): AirAlertState = withContext(Dispatchers.IO) {

        require(!district.isNullOrBlank()) {
            "Район адреси не визначено"
        }

        // First fetch the official alert state. A slow threats endpoint
        // must not delay reporting an active air alert.
        val alertsJson = requestJson(ALERTS_ENDPOINT)

        val raions = alertsJson.optJSONArray("raions")
            ?: error("NEPTUN: немає даних районів")
        val oblasts = alertsJson.optJSONArray("oblasts")
            ?: error("NEPTUN: немає даних областей")

        // NEPTUN returns only listed active areas. Each entry has
        // a level (yellow/red), not just a presence flag.
        val regionalAlerts = filterObjects(oblasts) {
            isOurOblast(it.optString("name")) ||
                    isOurOblast(it.optString("oblast"))
        }
        val localAlerts = filterObjects(raions) {
            isOurOblast(it.optString("oblast")) &&
                    sameDistrict(it.optString("name"), district)
        }
        val elsewhere = filterObjects(raions) {
            isOurOblast(it.optString("oblast")) &&
                    !sameDistrict(it.optString("name"), district)
        }.isNotEmpty()

        // NEPTUN documents /alerts without an updatedAt field.
        // A successful uncached HTTP response is a fresh observation of
        // the active-area list, even if the JSON has no server timestamp.
        // If the API supplies a timestamp, validate it rather than
        // silently replacing an explicitly stale value.
        val observedAt = Instant.now()
        val rawServerTime = alertsJson.optString("updatedAt")
        val serverSeconds = parseTimestamp(rawServerTime)
        val now = observedAt.epochSecond
        val freshAlerts = if (rawServerTime.isBlank()) {
            true
        } else {
            serverSeconds != null &&
                    serverSeconds <= now + MAX_CLOCK_SKEW_SECONDS &&
                    now - serverSeconds in 0L..MAX_ALERT_AGE_SECONDS
        }
        val verifiedAt = if (rawServerTime.isBlank()) {
            observedAt.toString()
        } else {
            rawServerTime
        }

        val allNotices = parseNotices(raions, false) + parseNotices(oblasts, true)

        val matchingAlerts = regionalAlerts + localAlerts
        // /alerts lists active official alert areas; the documented
        // entries have no level field. Their presence means RED.
        val redAlerts = matchingAlerts.filter {
            val reportedLevel = it.optString("level")
            reportedLevel.isBlank() ||
                    reportedLevel.equals("red", ignoreCase = true)
        }
        val yellowAlerts = matchingAlerts.filter {
            it.optString("level").equals("yellow", ignoreCase = true)
        }
        val startedAt = matchingAlerts
            .mapNotNull { parseTimestamp(it.optString("since")) }
            .minOrNull()

        // An outdated feed cannot prove either an active or cleared alert.
        if (!freshAlerts) {
            return@withContext AirAlertState(
                district = district,
                active = false,
                partialElsewhere = elsewhere,
                startedAt = null,
                endedAt = null,
                sourceUpdated = verifiedAt,
                level = AirAlertLevel.UNKNOWN,
                areaNotices = allNotices
            )
        }

        // NEPTUN red is a source-reported red level. Whether it is
        // an official siren activation must be verified separately.
        if (redAlerts.isNotEmpty()) {
            return@withContext AirAlertState(
                district = district,
                active = true,
                partialElsewhere = false,
                startedAt = redAlerts
                    .mapNotNull { parseTimestamp(it.optString("since")) }
                    .minOrNull(),
                endedAt = null,
                sourceUpdated = verifiedAt,
                level = AirAlertLevel.RED,
                threats = emptyList(),
                areaNotices = allNotices
            )
        }

        // NEPTUN yellow must not be interpreted as an official red alert.
        if (yellowAlerts.isNotEmpty()) {
            return@withContext AirAlertState(
                district = district,
                active = false,
                partialElsewhere = elsewhere,
                startedAt = startedAt,
                endedAt = null,
                sourceUpdated = verifiedAt,
                level = AirAlertLevel.YELLOW,
                threats = emptyList(),
                areaNotices = allNotices
            )
        }

        // Unknown levels are not evidence that the area is safe.
        if (matchingAlerts.isNotEmpty()) {
            return@withContext AirAlertState(
                district = district,
                active = false,
                partialElsewhere = elsewhere,
                startedAt = null,
                endedAt = null,
                sourceUpdated = verifiedAt,
                level = AirAlertLevel.UNKNOWN,
                areaNotices = allNotices
            )
        }

        val threatsJson = try {
            requestJson(THREATS_ENDPOINT)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        val threatsArray = threatsJson?.optJSONArray("threats")
        val threatsUpdated = threatsJson?.optString("serverTime").orEmpty()
        val threatSeconds = parseTimestamp(threatsUpdated)
        val freshThreats = threatSeconds != null &&
                threatSeconds <= now + MAX_CLOCK_SKEW_SECONDS &&
                now - threatSeconds in 0L..MAX_ALERT_AGE_SECONDS
        val allThreats = if (freshThreats && threatsArray != null) {
            parseThreats(threatsArray, district, includeAll = true)
        } else emptyList()
        val relevantThreats = allThreats.filter {
            isOurOblast(it.region) &&
                    (it.district.isBlank() || sameDistrict(it.district, district))
        }
        val level = when {
            !freshThreats || threatsArray == null -> AirAlertLevel.UNKNOWN
            relevantThreats.isNotEmpty() -> AirAlertLevel.YELLOW
            else -> AirAlertLevel.GREEN
        }

        AirAlertState(
            district = district,
            active = false,
            partialElsewhere = elsewhere,
            startedAt = null,
            endedAt = null,
            sourceUpdated = verifiedAt,
            level = level,
            threats = relevantThreats,
            allThreats = allThreats,
            areaNotices = allNotices,
            threatsUpdated = threatsUpdated
        )
    }

    private fun requestJson(url: String): JSONObject {

        // Avoid intermediary and device caches returning an old snapshot.
        val separator = if ("?" in url) "&" else "?"
        val freshUrl = "$url${separator}_ts=${System.currentTimeMillis()}"
        val conn =
            URL(freshUrl).openConnection() as HttpURLConnection

        conn.requestMethod = "GET"
        conn.connectTimeout = 6500
        conn.readTimeout = 6500
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
        conn.setRequestProperty("Pragma", "no-cache")

        conn.setRequestProperty(
            "Accept",
            "application/json"
        )

        conn.setRequestProperty(
            "User-Agent",
            "KropiLive-Android/1.0"
        )

        try {
            if (conn.responseCode !in 200..299) {
                error("HTTP ${conn.responseCode}")
            }

            return JSONObject(
                conn.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun parseThreats(
        array: JSONArray,
        selectedDistrict: String,
        includeAll: Boolean = false
    ): List<AirThreat> {

        val result = mutableListOf<AirThreat>()
        val now = Instant.now().epochSecond

        for (i in 0 until array.length()) {

            val item = array.optJSONObject(i) ?: continue

            if (
                !item.optString("status")
                    .equals("active", ignoreCase = true)
            ) {
                continue
            }

            val updatedAt =
                item.optString("updatedAt")

            val updatedSeconds =
                parseTimestamp(updatedAt) ?: continue

            if (
                updatedSeconds > now + 60L ||
                now - updatedSeconds > MAX_THREAT_AGE_SECONDS
            ) {
                continue
            }

            val region =
                item.optString("region")

            val district =
                item.optString("district")

            val areaOnly =
                item.optBoolean("areaOnly", false)

            if (!includeAll && !isOurOblast(region)) {
                continue
            }

            /*
             * Если указан конкретный район,
             * учитываем только выбранный район.
             *
             * Если район отсутствует, но
             * указана наша область, считаем
             * запись областной информацией.
             */
            if (
                !includeAll && district.isNotBlank() &&
                !sameDistrict(
                    district,
                    selectedDistrict
                )
            ) {
                continue
            }

            val lat = nullableDouble(item, "lat")
            val lon = nullableDouble(item, "lon")

            val validCoordinates =
                !areaOnly &&
                        lat != null &&
                        lon != null &&
                        lat in -90.0..90.0 &&
                        lon in -180.0..180.0

            result.add(
                AirThreat(
                    id = item.optString("id"),
                    type = item.optString("type", "unknown"),
                    title = item.optString("title")
                        .ifBlank { "Повітряна загроза" },
                    region = region,
                    district = district,
                    locality = item.optString("locality"),
                    latitude =
                        if (validCoordinates) lat else null,
                    longitude =
                        if (validCoordinates) lon else null,
                    heading =
                        if (validCoordinates) {
                            nullableDouble(item, "heading")
                        } else {
                            null
                        },
                    confidenceLevel =
                        item.optString("confidenceLevel"),
                    updatedAt = updatedAt,
                    advisory =
                        item.optBoolean("advisory", false),
                    areaOnly = areaOnly,
                    explanation = item.optString("explanationShort"),
                    sourceCount = item.optInt("sourceCount", 0),
                    uncertaintyKm = nullableDouble(item, "uncertaintyKm"),
                    positionQuality = item.optString("positionQuality"),
                    lifecycle = item.optString("lifecycle"),
                    count = item.optInt("count", 1),
                    trail = parseTrail(item.optJSONArray("trail"))
                )
            )
        }

        return result
    }

    private fun parseTrail(array: JSONArray?): List<Pair<Double, Double>> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val point = array.optJSONObject(i) ?: return@mapNotNull null
            val lat = nullableDouble(point, "lat") ?: return@mapNotNull null
            val lon = nullableDouble(point, "lon") ?: return@mapNotNull null
            if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
        }
    }

    private fun parseNotices(array: JSONArray, isOblast: Boolean): List<NeptunAreaNotice> =
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val reasonsArray = obj.optJSONArray("reasons")
            val reasons = if (reasonsArray == null) emptyList() else
                (0 until reasonsArray.length()).mapNotNull { j ->
                    reasonsArray.optString(j).takeIf { it.isNotBlank() }
                }
            NeptunAreaNotice(
                name = obj.optString("name"),
                oblast = obj.optString("oblast"),
                level = obj.optString("level"),
                since = obj.optString("since"),
                reasons = reasons,
                isOblast = isOblast
            )
        }

    private fun nullableDouble(
        obj: JSONObject,
        key: String
    ): Double? {

        if (!obj.has(key) || obj.isNull(key)) {
            return null
        }

        val value = obj.optDouble(key, Double.NaN)

        return value.takeIf { it.isFinite() }
    }

    private fun filterObjects(
        array: JSONArray,
        predicate: (JSONObject) -> Boolean
    ): List<JSONObject> {

        val result = mutableListOf<JSONObject>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            if (predicate(item)) {
                result.add(item)
            }
        }

        return result
    }

    private fun isOurOblast(value: String): Boolean {
        val name = normalize(value)

        return "кировоград" in name
    }

    private fun sameDistrict(
        first: String,
        second: String
    ): Boolean {

        return normalize(first) == normalize(second) &&
                first.isNotBlank() &&
                second.isNotBlank()
    }

    private fun normalize(value: String): String {

        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace('і', 'и')
            .replace('ї', 'и')
            .replace('є', 'е')
            .replace(
                Regex("\\s+"),
                " "
            )
    }

    private fun parseTimestamp(value: String): Long? {

        if (value.isBlank()) return null

        return try {
            Instant.parse(value).epochSecond
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(value).toEpochSecond()
            } catch (_: Exception) {
                null
            }
        }
    }
}