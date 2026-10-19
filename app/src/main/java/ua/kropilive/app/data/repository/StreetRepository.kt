
package ua.kropilive.app.data.repository

import android.content.Context
import org.json.JSONArray

class StreetRepository(
    private val context: Context
) {
    fun loadStreets(): List<String> {
        return try {
            val json = context.assets.open("streets.json")
                .bufferedReader()
                .use { it.readText() }

            val array = JSONArray(json)

            val streets = mutableListOf<String>()

            for (i in 0 until array.length()) {
                val street = array.optString(i).trim()

                if (street.isNotEmpty()) {
                    streets.add(street)
                }
            }

            streets.distinct().sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
