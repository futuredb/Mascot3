package com.generativemascot.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore("mascot_prefs")

class SessionStore(private val context: Context) {
    val appContext: Context
        get() = context.applicationContext

    private val deviceId = stringPreferencesKey("device_id")
    private val mascotId = stringPreferencesKey("mascot_id")
    private val acceptedMascotId = stringPreferencesKey("accepted_mascot_id")
    private val cityName = stringPreferencesKey("city_name")
    private val onboarding = stringPreferencesKey("onboarding_done")
    private val currentState = stringPreferencesKey("current_state")
    private val widgetFrame = intPreferencesKey("widget_frame")

    suspend fun deviceId(): String {
        val existing = context.dataStore.data.map { it[deviceId] }.first()
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        context.dataStore.edit { it[deviceId] = created }
        return created
    }

    suspend fun saveMascot(id: String) {
        context.dataStore.edit { it[mascotId] = id }
    }

    suspend fun mascotId(): String? = context.dataStore.data.map { it[mascotId] }.first()

    suspend fun saveAcceptedMascot(id: String) {
        context.dataStore.edit {
            it[mascotId] = id
            it[acceptedMascotId] = id
        }
    }

    suspend fun acceptedMascotId(): String? = context.dataStore.data.map { it[acceptedMascotId] }.first()

    suspend fun saveCurrentState(stateKey: String) {
        context.dataStore.edit { it[currentState] = stateKey }
    }

    suspend fun currentState(): String? = context.dataStore.data.map { it[currentState] }.first()

    suspend fun saveWidgetFrame(index: Int) {
        context.dataStore.edit { it[widgetFrame] = index }
    }

    suspend fun widgetFrame(): Int = context.dataStore.data.map { it[widgetFrame] ?: 0 }.first()

    suspend fun saveCityName(name: String) {
        context.dataStore.edit { it[cityName] = name }
    }

    suspend fun cityName(): String? = context.dataStore.data.map { it[cityName] }.first()

    suspend fun markOnboarded() {
        context.dataStore.edit { it[onboarding] = "1" }
    }

    suspend fun onboarded(): Boolean = context.dataStore.data.map { it[onboarding] == "1" }.first()

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
