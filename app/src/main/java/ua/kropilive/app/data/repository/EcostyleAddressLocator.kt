package ua.kropilive.app.data.repository

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Finds a building through the geocoding provider configured on the Android device.
 * Does not use public Nominatim (its public API is unsuitable for automatic app-wide requests).
 * Only an address whose house number is confirmed is accepted; a street centroid is NOT a house.
 */
object EcostyleAddressLocator {
    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    @Suppress("DEPRECATION")
    suspend fun find(context: Context, city: String, street: String, house: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            if (street.isBlank() || house.isBlank() || !Geocoder.isPresent()) return@withContext null
            val prefs = context.applicationContext.getSharedPreferences("ecostyle_geocoding_v1", Context.MODE_PRIVATE)
            val key = "$city|$street|$house"
            val saved = prefs.getString(key, null)
            if (saved != null) {
                if (saved == "NONE") return@withContext null
                val ll = saved.split(',').mapNotNull { it.toDoubleOrNull() }
                if (ll.size == 2) return@withContext ll[0] to ll[1]
            }
            val targetHouse = normalize(house)
            val query = listOf(street, house, city.ifBlank { "Кропивницький" }, "Україна").joinToString(", ")
            val result = runCatching {
                Geocoder(context.applicationContext, Locale.forLanguageTag("uk"))
                    .getFromLocationName(query, 5).orEmpty()
                    .firstOrNull { address ->
                        val actual = normalize(address.subThoroughfare.orEmpty())
                        val feature = normalize(address.featureName.orEmpty())
                        // Avoid accepting a nearby house or an entire street as the requested building.
                        (actual == targetHouse || (actual.isBlank() && feature == targetHouse)) &&
                                address.latitude in 48.3..48.8 && address.longitude in 31.9..32.7
                    }
            }.getOrNull()
            if (result != null) {
                prefs.edit().putString(key, "${result.latitude},${result.longitude}").apply()
                result.latitude to result.longitude
            } else null // do not cache failures: a temporarily unavailable provider can recover
        }
}
