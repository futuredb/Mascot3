package com.generativemascot.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Selects a usable state without the Mascot backend. Weather is fetched directly by the phone. */
class LocalContextResolver {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(): String = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now()
        if (now.hour >= 23 || now.hour < 7) return@withContext "sleeping"

        val weather = runCatching { currentWeather() }.getOrNull()
        if (weather != null) {
            val (code, apparent, cloud) = weather
            when {
                code >= 95 -> return@withContext "rainy"
                code in 51..67 || code in 80..82 -> return@withContext "rainy"
                cloud >= 95 -> return@withContext "bored"
                cloud >= 80 && inThinkingWindow(now) -> return@withContext "thinking"
                cloud >= 80 -> return@withContext "content"
            }
        }

        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        when {
            // The current joyful asset failed identity validation; keep the same hero.
            weekend -> "content"
            now.hour in 9..17 -> "working"
            else -> "content"
        }
    }

    private fun inThinkingWindow(now: ZonedDateTime): Boolean {
        val slotMinute = (now.hour % 2) * 60 + now.minute
        val startMinute = (now.dayOfYear * 37 + (now.hour / 2) * 17) % 111
        return slotMinute in startMinute until (startMinute + 10)
    }

    private fun currentWeather(): Triple<Int, Double, Int> {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=55.7558&longitude=37.6173" +
            "&current=weather_code,apparent_temperature,cloud_cover&timezone=Europe%2FMoscow"
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) error("weather ${it.code}")
            val current = Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject["current"]!!.jsonObject
            return Triple(
                current["weather_code"]!!.jsonPrimitive.intOrNull ?: 0,
                current["apparent_temperature"]!!.jsonPrimitive.doubleOrNull ?: 20.0,
                current["cloud_cover"]!!.jsonPrimitive.intOrNull ?: 0,
            )
        }
    }
}
