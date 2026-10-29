package io.github.alamo3.openblue.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.round

/** The weather right now at one place. */
data class Weather(
    val tempC: Double,
    /** What it feels like (wind and humidity included). */
    val feelsLikeC: Double?,
    /** WMO weather code, see [describe]. */
    val code: Int?,
    val isDay: Boolean,
    /** When the app fetched it (ms since epoch). */
    val fetchedAt: Long,
) {
    val summary: String get() = describe(code, isDay)

    companion object {
        /** Plain words for a WMO weather code. */
        fun describe(code: Int?, isDay: Boolean = true): String = when (code) {
            0 -> if (isDay) "Sunny" else "Clear"
            1 -> if (isDay) "Mostly sunny" else "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71, 73 -> "Snow"
            75 -> "Heavy snow"
            77 -> "Snow grains"
            80, 81 -> "Showers"
            82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> ""
        }
    }
}

/**
 * Current weather from Open-Meteo (free, no key, CC BY 4.0). Coordinates are rounded to about a
 * kilometre before they leave the phone: plenty for the weather, and no more precise than it needs.
 */
class WeatherClient(private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun current(lat: Double, lon: Double): Weather {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,apparent_temperature,weather_code,is_day",
            round2(lat), round2(lon),
        )
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Weather service answered ${r.code}")
            return parse(r.body?.string() ?: throw IOException("Empty weather reply"))
        }
    }

    fun parse(body: String, now: Long = System.currentTimeMillis()): Weather {
        val cur = json.parseToJsonElement(body).jsonObject["current"] as? JsonObject ?: throw IOException("No current weather in reply")
        val temp = cur.dbl("temperature_2m") ?: throw IOException("No temperature in reply")
        return Weather(
            tempC = temp,
            feelsLikeC = cur.dbl("apparent_temperature"),
            code = cur.int("weather_code"),
            isDay = cur.int("is_day") != 0,
            fetchedAt = now,
        )
    }

    private fun round2(v: Double) = round(v * 100) / 100
}

/**
 * Seat settings that don't suit the weather: heated seats on a warm day, cooled seats on a cold
 * one. Null when they suit it (or it's mild, or the weather is unknown).
 */
object SeatWeather {
    /** At or above this, heated seats are probably a mistake. */
    const val WARM_C = 20.0
    /** At or below this, cooled seats are probably a mistake. */
    const val COLD_C = 12.0

    fun warning(seats: List<Int>, weather: Weather?): String? {
        weather ?: return null
        val t = weather.tempC
        val levels = seats.map { SeatLevel.of(it) }
        val heated = levels.count { it.isHeat }
        val cooled = levels.count { it.isCool }
        val shown = ClimateSettings.fmt(round(t * 2) / 2)
        return when {
            heated > 0 && t >= WARM_C ->
                "It's $shown° outside. Heat ${if (heated == 1) "a seat" else "$heated seats"} anyway?"
            cooled > 0 && t <= COLD_C ->
                "It's $shown° outside. Cool ${if (cooled == 1) "a seat" else "$cooled seats"} anyway?"
            else -> null
        }
    }
}

/** [settings] with every seat that doesn't suit the weather (heated when warm, cooled when cold) turned off. */
fun ClimateSettings.withoutMismatchedSeats(weather: Weather?): ClimateSettings {
    weather ?: return this
    var out = this
    seats.forEachIndexed { i, code ->
        val l = SeatLevel.of(code)
        if ((l.isHeat && weather.tempC >= SeatWeather.WARM_C) || (l.isCool && weather.tempC <= SeatWeather.COLD_C)) out = out.withSeat(i, 0)
    }
    return out
}
