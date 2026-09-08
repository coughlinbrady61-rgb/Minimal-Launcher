package com.minimal.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WeatherNow(
    val tempF: Int,
    val description: String,
    val fetchedAt: Long
) {
    val line: String get() = "$description, ${tempF}°"
}

/**
 * Free weather from Open-Meteo — no account, no API key, no tracking.
 * Falls back quietly to the last known reading, then to nothing.
 */
object Weather {
    private const val KEY_TEMP = "weather_temp"
    private const val KEY_DESC = "weather_desc"
    private const val KEY_AT = "weather_at"
    private const val KEY_LAT = "weather_lat"
    private const val KEY_LON = "weather_lon"
    private const val KEY_ENABLED = "weather_enabled"

    private const val FRESH_MS = 30 * 60 * 1000L   // refetch after 30 minutes

    private fun prefs(c: Context) = c.getSharedPreferences("minimal_store", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = runCatching {
        prefs(c).getBoolean(KEY_ENABLED, true)
    }.getOrDefault(true)

    fun setEnabled(c: Context, on: Boolean) {
        runCatching { prefs(c).edit().putBoolean(KEY_ENABLED, on).apply() }
    }

    fun hasLocationPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun cached(c: Context): WeatherNow? = runCatching {
        val p = prefs(c)
        val at = p.getLong(KEY_AT, 0L)
        if (at == 0L) return null
        val desc = p.getString(KEY_DESC, null) ?: return null
        WeatherNow(p.getInt(KEY_TEMP, 0), desc, at)
    }.getOrNull()

    private fun store(c: Context, w: WeatherNow) {
        runCatching {
            prefs(c).edit()
                .putInt(KEY_TEMP, w.tempF)
                .putString(KEY_DESC, w.description)
                .putLong(KEY_AT, w.fetchedAt)
                .apply()
        }
    }

    private fun isStale(w: WeatherNow?): Boolean =
        w == null || System.currentTimeMillis() - w.fetchedAt > FRESH_MS

    /** last known coordinates, cheap and permission-light */
    private fun lastLocation(c: Context): Pair<Double, Double>? {
        if (!hasLocationPermission(c)) return cachedCoords(c)
        return runCatching {
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (provider in lm.getProviders(true)) {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            }
            best?.let {
                prefs(c).edit()
                    .putFloat(KEY_LAT, it.latitude.toFloat())
                    .putFloat(KEY_LON, it.longitude.toFloat())
                    .apply()
                it.latitude to it.longitude
            } ?: cachedCoords(c)
        }.getOrElse { cachedCoords(c) }
    }

    private fun cachedCoords(c: Context): Pair<Double, Double>? = runCatching {
        val p = prefs(c)
        val lat = p.getFloat(KEY_LAT, 0f)
        val lon = p.getFloat(KEY_LON, 0f)
        if (lat == 0f && lon == 0f) null else lat.toDouble() to lon.toDouble()
    }.getOrNull()

    /**
     * Returns cached weather immediately if fresh, otherwise fetches.
     * Safe to call from a coroutine on every home-screen load.
     */
    suspend fun current(c: Context): WeatherNow? {
        if (!enabled(c)) return null
        val cache = cached(c)
        if (!isStale(cache)) return cache

        val coords = lastLocation(c) ?: return cache
        val fetched = withContext(Dispatchers.IO) { fetch(coords.first, coords.second) }
        return if (fetched != null) { store(c, fetched); fetched } else cache
    }

    private fun fetch(lat: Double, lon: Double): WeatherNow? = runCatching {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&temperature_unit=fahrenheit"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "GET"
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val current = JSONObject(body).getJSONObject("current")
        val temp = current.getDouble("temperature_2m")
        val code = current.getInt("weather_code")

        WeatherNow(
            tempF = Math.round(temp).toInt(),
            description = describe(code),
            fetchedAt = System.currentTimeMillis()
        )
    }.getOrNull()

    /** WMO weather codes, in plain lowercase words */
    private fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80, 81 -> "rain showers"
        82 -> "heavy showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "storms with hail"
        else -> "—"
    }
}
