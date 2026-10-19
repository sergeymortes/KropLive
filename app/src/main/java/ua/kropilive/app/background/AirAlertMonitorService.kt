package ua.kropilive.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ua.kropilive.app.data.repository.AddressRepository
import ua.kropilive.app.data.repository.AirAlertRepository
import ua.kropilive.app.data.repository.AirAlertLevel
import ua.kropilive.app.data.repository.AirAlertState
import ua.kropilive.app.data.repository.AirThreat
import ua.kropilive.app.data.repository.NeptunAreaNotice
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * User-visible, explicitly started NEPTUN monitoring session.
 * Requires a matching foreground-service declaration in AndroidManifest.xml.
 * Android may still stop the service; no hard delivery-time guarantee.
 */
class AirAlertMonitorService : Service() {

    companion object {
        const val ACTION_START = "ua.kropilive.app.action.START_AIR_MONITOR"
        const val ACTION_STOP = "ua.kropilive.app.action.STOP_AIR_MONITOR"

        private const val LOG_TAG = "KropiNeptunWS"
        private const val CHANNEL_ID = "neptun_monitor_running_v1"
        private const val NOTIFICATION_ID = 3190
        private const val POLL_INTERVAL_MS = 30_000L
        private const val STREAM_URL = "wss://neptun.in.ua/api/v1/stream"

        fun startIntent(context: Context): Intent =
            Intent(context, AirAlertMonitorService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AirAlertMonitorService::class.java).setAction(ACTION_STOP)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(LOG_TAG, "Monitor stop requested")
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        try {
            // Must be called promptly after startForegroundService().
            startForeground(NOTIFICATION_ID, ongoingNotification())
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Foreground service start failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch {
                val address = try {
                    withTimeoutOrNull(5_000L) {
                        AddressRepository(applicationContext).address.first()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val district = address?.let { AirAlertRepository.districtFor(it) }
                if (district.isNullOrBlank()) {
                    Log.w(LOG_TAG, "No district; WebSocket monitoring not started")
                    // No reliable district: never notify for an unrelated area.
                    return@launch
                }

                Log.i(LOG_TAG, "Monitor started; district=$district")
                var backoffMs = 1_000L
                while (isActive) {
                    val disconnected = CompletableDeferred<Unit>()
                    val feed = StreamFeed(district) { state ->
                        Log.i(LOG_TAG, "Stream state: level=${state.level}, district=${state.district}")
                        // Share the same validated stream result with UI observers.
                        AirAlertRepository.publishStreamState(state)
                        Log.d(LOG_TAG, "Published WebSocket state to repository: ${state.level}")
                        try {
                            AlertNotifier.check(applicationContext, state)
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "AlertNotifier.check failed", e)
                        }
                    }
                    val request = Request.Builder().url(STREAM_URL).build()
                    Log.i(LOG_TAG, "Connecting WebSocket: $STREAM_URL")
                    val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            Log.i(LOG_TAG, "WebSocket OPEN, HTTP ${response.code}")
                            backoffMs = 1_000L
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                val event = JSONObject(text)
                                val data = event.optJSONObject("data")
                                val keys = data?.keys()?.asSequence()?.toList()?.joinToString(",") ?: "not-object"
                                Log.d(LOG_TAG, "Frame type=${event.optString("type")}, dataKeys=$keys, bytes=${text.length}")
                                feed.accept(event)
                            } catch (e: Exception) {
                                Log.w(LOG_TAG, "Cannot parse WebSocket frame", e)
                            }
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?
                        ) {
                            Log.e(LOG_TAG, "WebSocket FAILURE, HTTP=${response?.code}", t)
                            disconnected.complete(Unit)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            Log.w(LOG_TAG, "WebSocket CLOSED: code=$code reason=$reason")
                            disconnected.complete(Unit)
                        }
                    })
                    socket = ws
                    try {
                        // REST provides a fallback when the stream disconnects.
                        // While connected, events are processed immediately by onMessage.
                        disconnected.await()
                    } finally {
                        ws.cancel()
                        if (socket === ws) socket = null
                    }
                    if (!isActive) break
                    Log.i(LOG_TAG, "WebSocket disconnected; checking REST fallback")
                    try {
                        val state = withTimeoutOrNull(18_000L) {
                            AirAlertRepository.load(district)
                        }
                        if (state != null) {
                            Log.i(LOG_TAG, "REST fallback: level=${state.level}")
                            AirAlertRepository.publishStreamState(state)
                            Log.d(LOG_TAG, "Published REST fallback state to repository: ${state.level}")
                            AlertNotifier.check(applicationContext, state)
                        } else {
                            Log.w(LOG_TAG, "REST fallback timed out")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "REST fallback failed", e)
                    }
                    Log.i(LOG_TAG, "Reconnecting in ${backoffMs}ms")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(POLL_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    /**
     * The stream sends a full alerts list and a separate threat snapshot.
     * RED can be reported as soon as a matching official alert arrives.
     * GREEN/YELLOW require both snapshots so an absent alert is not mistaken
     * for an absence of tracked threats.
     */
    private class StreamFeed(
        private val district: String,
        private val onState: (AirAlertState) -> Unit
    ) {
        private var alertsKnown = false
        private var threatsKnown = false
        private var raions = JSONArray()
        private var oblasts = JSONArray()
        private var alertUpdatedAt = ""
        private val threats = linkedMapOf<String, JSONObject>()

        @Synchronized
        fun accept(envelope: JSONObject) {
            when (envelope.optString("type")) {
                "alerts" -> {
                    val data = envelope.optJSONObject("data") ?: return
                    val newRaions = data.optJSONArray("raions") ?: return
                    val newOblasts = data.optJSONArray("oblasts") ?: return
                    raions = newRaions
                    oblasts = newOblasts
                    alertUpdatedAt = data.optString("updatedAt")
                    alertsKnown = true
                    Log.i(LOG_TAG, "alerts: raions=${raions.length()}, oblasts=${oblasts.length()}")
                }
                "snapshot" -> {
                    val data = envelope.optJSONObject("data") ?: return
                    val items = data.optJSONArray("threats") ?: return
                    threats.clear()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        item.optString("id").takeIf { it.isNotBlank() }?.let { threats[it] = item }
                    }
                    threatsKnown = true
                    Log.i(LOG_TAG, "snapshot: threats=${items.length()}")
                    val snapshotRaions = data.optJSONArray("alerts")
                    val snapshotOblasts = data.optJSONArray("alertOblasts")
                    if (snapshotRaions != null && snapshotOblasts != null) {
                        raions = snapshotRaions
                        oblasts = snapshotOblasts
                        alertUpdatedAt = data.optString("updatedAt")
                        alertsKnown = true
                    }
                }
                "upsert" -> {
                    val item = envelope.optJSONObject("data") ?: return
                    item.optString("id").takeIf { it.isNotBlank() }?.let { threats[it] = item }
                }
                "remove" -> {
                    val id = envelope.optJSONObject("data")?.optString("id").orEmpty()
                    if (id.isNotBlank()) threats.remove(id)
                }
                else -> return
            }
            if (!alertsKnown) return

            val matching = entries(raions).filter {
                isOurOblast(it.optString("oblast")) && sameDistrict(it.optString("name"))
            } + entries(oblasts).filter {
                isOurOblast(it.optString("name")) || isOurOblast(it.optString("oblast"))
            }
            val elsewhere = entries(raions).any {
                isOurOblast(it.optString("oblast")) && !sameDistrict(it.optString("name"))
            }
            // A live WebSocket "alerts" frame is a freshly received full snapshot.
            // updatedAt may describe the last *change* to the dataset, not the
            // delivery time; unchanged areas can legitimately have an old timestamp.
            // Do not reject a just-delivered snapshot solely for that reason.
            val sourceTime = runCatching { Instant.parse(alertUpdatedAt).epochSecond }.getOrNull()
            val nowSeconds = Instant.now().epochSecond
            if (sourceTime == null || sourceTime > nowSeconds + 60L || nowSeconds - sourceTime > 150L) {
                Log.w(LOG_TAG, "Alerts updatedAt not recent (value=$alertUpdatedAt); using live WebSocket receipt time")
            }
            Log.i(LOG_TAG, "Matched area names=${matching.joinToString(";") { it.optString("name") }}")
            val reportedLevels = matching.map { it.optString("level").trim().lowercase(Locale.ROOT) }
            // Unknown/missing level is NOT proof of a red alert or of safety.
            // Do not turn a yellow area notice into a red siren notification.
            val hasUnclassified = reportedLevels.any { it !in setOf("red", "yellow", "green") }
            val level = when {
                "red" in reportedLevels -> AirAlertLevel.RED
                hasUnclassified -> AirAlertLevel.UNKNOWN
                "yellow" in reportedLevels -> AirAlertLevel.YELLOW
                !threatsKnown -> AirAlertLevel.UNKNOWN
                threats.values.any(::relevantThreat) -> AirAlertLevel.YELLOW
                else -> AirAlertLevel.GREEN
            }
            Log.i(LOG_TAG, "Area levels=${reportedLevels.joinToString(",")}, matching=${matching.size}, result=$level")
            if (level == AirAlertLevel.UNKNOWN) {
                Log.w(LOG_TAG, "Unverified alert level; not publishing a safe or red state")
                return
            }

            val notices = entries(raions).map { notice(it, false) } +
                    entries(oblasts).map { notice(it, true) }
            val allThreats = threats.values.mapNotNull(::parseThreat)
            val relevant = allThreats.filter {
                isOurOblast(it.region) && (it.district.isBlank() || sameDistrict(it.district))
            }
            val startedAt = matching.mapNotNull {
                runCatching { Instant.parse(it.optString("since")).epochSecond }.getOrNull()
            }.minOrNull()
            onState(AirAlertState(
                district = district,
                active = level == AirAlertLevel.RED,
                partialElsewhere = elsewhere,
                startedAt = startedAt,
                endedAt = null,
                sourceUpdated = Instant.now().toString(),
                level = level,
                threats = relevant,
                allThreats = allThreats,
                areaNotices = notices,
                threatsUpdated = Instant.now().toString()
            ))
        }

        private fun entries(array: JSONArray): List<JSONObject> =
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        private fun notice(item: JSONObject, oblast: Boolean): NeptunAreaNotice {
            val reasons = item.optJSONArray("reasons")
            return NeptunAreaNotice(
                name = item.optString("name"),
                oblast = item.optString("oblast"),
                level = item.optString("level"),
                since = item.optString("since"),
                reasons = if (reasons == null) emptyList() else
                    (0 until reasons.length()).mapNotNull { reasons.optString(it).takeIf(String::isNotBlank) },
                isOblast = oblast
            )
        }

        private fun parseThreat(item: JSONObject): AirThreat? {
            if (!item.optString("status", "active").equals("active", true)) return null
            val lat = item.optDouble("lat", Double.NaN).takeIf { it.isFinite() && it in -90.0..90.0 }
            val lon = item.optDouble("lon", Double.NaN).takeIf { it.isFinite() && it in -180.0..180.0 }
            val areaOnly = item.optBoolean("areaOnly", false)
            val trail = item.optJSONArray("trail")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val point = array.optJSONObject(index) ?: return@mapNotNull null
                    val x = point.optDouble("lat", Double.NaN)
                    val y = point.optDouble("lon", Double.NaN)
                    if (x.isFinite() && y.isFinite() && x in -90.0..90.0 && y in -180.0..180.0) x to y else null
                }
            }.orEmpty()
            return AirThreat(
                id = item.optString("id"), type = item.optString("type"),
                title = item.optString("title").ifBlank { "Повітряна загроза" },
                region = item.optString("region"), district = item.optString("district"),
                locality = item.optString("locality"),
                latitude = if (areaOnly) null else lat,
                longitude = if (areaOnly) null else lon,
                heading = item.optDouble("heading", Double.NaN).takeIf { !areaOnly && it.isFinite() },
                confidenceLevel = item.optString("confidenceLevel"),
                updatedAt = item.optString("updatedAt"),
                advisory = item.optBoolean("advisory", false), areaOnly = areaOnly,
                explanation = item.optString("explanationShort"),
                sourceCount = item.optInt("sourceCount"),
                uncertaintyKm = item.optDouble("uncertaintyKm", Double.NaN).takeIf { it.isFinite() },
                positionQuality = item.optString("positionQuality"),
                lifecycle = item.optString("lifecycle"),
                count = item.optInt("count", 1), trail = trail
            )
        }

        private fun relevantThreat(item: JSONObject): Boolean {
            if (!item.optString("status", "active").equals("active", true)) return false
            if (item.optBoolean("advisory", false)) return false
            if (!isOurOblast(item.optString("region"))) return false
            val area = item.optString("district")
            return area.isBlank() || sameDistrict(area)
        }

        private fun sameDistrict(name: String): Boolean = normalize(name) == normalize(district) && name.isNotBlank()

        private fun isOurOblast(name: String): Boolean =
            normalize(name).contains("кировоград")

        private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace('і', 'и').replace('ї', 'и').replace('є', 'е')
            .replace('ё', 'е').replace("’", "'")
            .replace(Regex("\\s+"), " ").trim()
            .removeSuffix(" район").trim()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Моніторинг NEPTUN",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Постійне сповіщення про активний моніторинг повітряної обстановки"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun ongoingNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            android.app.PendingIntent.getActivity(
                this,
                3191,
                it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("КропіLive · моніторинг NEPTUN")
            .setContentText("Перевірка повітряної обстановки активна")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "Monitor service destroyed")
        monitorJob?.cancel()
        socket?.cancel()
        socket = null
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        serviceScope.cancel()
        super.onDestroy()
    }
}