
package ua.kropilive.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UserAddress(
    val street: String = "",
    val building: String = "",

    val communityId: String = "",
    val communityName: String = "",

    val cityId: String = "",
    val cityName: String = "",

    val streetId: String = "",
    val houseId: String = "",

    val electricityQueue: String = "",
    val queueUpdatedAt: Long = 0L
) {
    val isFilled: Boolean
        get() = street.isNotBlank() && building.isNotBlank()

    val hasFullAddress: Boolean
        get() =
            communityId.isNotBlank() &&
                    cityId.isNotBlank() &&
                    streetId.isNotBlank() &&
                    houseId.isNotBlank() &&
                    isFilled

    val formattedAddress: String
        get() {
            if (!isFilled) return ""

            val parts = mutableListOf<String>()

            if (cityName.isNotBlank()) {
                parts.add(cityName)
            }

            parts.add("$street, $building")

            return parts.joinToString(", ")
        }
}

private val Context.addressDataStore by preferencesDataStore(
    name = "kropilive_address"
)

class AddressRepository(
    private val context: Context
) {
    companion object {
        private val STREET_KEY =
            stringPreferencesKey("street")

        private val BUILDING_KEY =
            stringPreferencesKey("building")

        private val COMMUNITY_ID_KEY =
            stringPreferencesKey("community_id")

        private val COMMUNITY_NAME_KEY =
            stringPreferencesKey("community_name")

        private val CITY_ID_KEY =
            stringPreferencesKey("city_id")

        private val CITY_NAME_KEY =
            stringPreferencesKey("city_name")

        private val STREET_ID_KEY =
            stringPreferencesKey("street_id")

        private val HOUSE_ID_KEY =
            stringPreferencesKey("house_id")

        private val ELECTRICITY_QUEUE_KEY =
            stringPreferencesKey("electricity_queue")

        private val QUEUE_UPDATED_AT_KEY =
            stringPreferencesKey("queue_updated_at")
    }

    val address: Flow<UserAddress> =
        context.addressDataStore.data.map { preferences ->
            UserAddress(
                street = preferences[STREET_KEY] ?: "",
                building = preferences[BUILDING_KEY] ?: "",

                communityId =
                    preferences[COMMUNITY_ID_KEY] ?: "",

                communityName =
                    preferences[COMMUNITY_NAME_KEY] ?: "",

                cityId =
                    preferences[CITY_ID_KEY] ?: "",

                cityName =
                    preferences[CITY_NAME_KEY] ?: "",

                streetId =
                    preferences[STREET_ID_KEY] ?: "",

                houseId =
                    preferences[HOUSE_ID_KEY] ?: "",

                electricityQueue =
                    preferences[ELECTRICITY_QUEUE_KEY] ?: "",

                queueUpdatedAt =
                    preferences[QUEUE_UPDATED_AT_KEY]
                        ?.toLongOrNull() ?: 0L
            )
        }

    // Старый метод оставляем для совместимости.
    // Если адрес меняется через старую форму,
    // сбрасываем привязку к очереди предыдущего дома.
    suspend fun saveAddress(
        street: String,
        building: String
    ) {
        context.addressDataStore.edit { preferences ->
            preferences[STREET_KEY] = street.trim()
            preferences[BUILDING_KEY] = building.trim()

            preferences[COMMUNITY_ID_KEY] = ""
            preferences[COMMUNITY_NAME_KEY] = ""

            preferences[CITY_ID_KEY] = ""
            preferences[CITY_NAME_KEY] = ""

            preferences[STREET_ID_KEY] = ""
            preferences[HOUSE_ID_KEY] = ""

            preferences[ELECTRICITY_QUEUE_KEY] = ""
            preferences[QUEUE_UPDATED_AT_KEY] = "0"
        }
    }

    // Новый метод: сохранение полного адреса
    // после выбора через сайт облэнерго.
    suspend fun saveFullAddress(
        communityId: String,
        communityName: String,
        cityId: String,
        cityName: String,
        streetId: String,
        streetName: String,
        houseId: String,
        houseName: String,
        electricityQueue: String
    ) {
        context.addressDataStore.edit { preferences ->
            preferences[COMMUNITY_ID_KEY] =
                communityId.trim()

            preferences[COMMUNITY_NAME_KEY] =
                communityName.trim()

            preferences[CITY_ID_KEY] =
                cityId.trim()

            preferences[CITY_NAME_KEY] =
                cityName.trim()

            preferences[STREET_ID_KEY] =
                streetId.trim()

            preferences[STREET_KEY] =
                streetName.trim()

            preferences[HOUSE_ID_KEY] =
                houseId.trim()

            preferences[BUILDING_KEY] =
                houseName.trim()

            preferences[ELECTRICITY_QUEUE_KEY] =
                electricityQueue.trim()

            preferences[QUEUE_UPDATED_AT_KEY] =
                System.currentTimeMillis().toString()
        }
    }

    // Обновление только очереди.
    // Адрес и его ID остаются прежними.
    suspend fun updateElectricityQueue(
        newQueue: String,
        expectedHouseId: String
    ): Boolean {
        var updated = false

        context.addressDataStore.edit { preferences ->
            val savedHouseId =
                preferences[HOUSE_ID_KEY] ?: ""

            if (
                expectedHouseId.isNotBlank() &&
                savedHouseId == expectedHouseId
            ) {
                preferences[ELECTRICITY_QUEUE_KEY] =
                    newQueue.trim()

                preferences[QUEUE_UPDATED_AT_KEY] =
                    System.currentTimeMillis().toString()

                updated = true
            }
        }

        return updated
    }
}
