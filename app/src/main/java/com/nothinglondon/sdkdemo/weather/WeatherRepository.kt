package com.nothinglondon.sdkdemo.weather

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class WeatherReading(
    val temperature: Int,
    val weatherCode: Int,
    val isDay: Boolean = true,
    val cloudCover: Int = -1
)

class WeatherRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("MissingPermission")
    fun lastLocation(): Location? {
        if (!hasLocationPermission()) return cachedLocation()
        val manager = context.getSystemService(LocationManager::class.java)
        return manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
            ?: cachedLocation()
    }

    @Suppress("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!hasLocationPermission()) return cachedLocation()
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return lastLocation()
        }
        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                runCatching {
                    manager.getCurrentLocation(provider, cancellation, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } ?: lastLocation()
    }

    suspend fun fetchLatest(): Result<WeatherReading> {
        val location = currentLocation()
        if (location == null) {
            val error = IllegalStateException("Нет сохранённой или текущей геопозиции")
            saveError(error.message.orEmpty())
            return Result.failure(error)
        }
        return runCatching { fetch(location) }
            .onFailure { saveError(it.message ?: "Ошибка фонового обновления") }
    }

    fun fetch(location: Location): WeatherReading {
        val endpoint = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&current=temperature_2m,weather_code,is_day,cloud_cover&temperature_unit=celsius"
        )
        val connection = endpoint.openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        try {
            check(connection.responseCode in 200..299) { "Weather HTTP ${connection.responseCode}" }
            val current = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .getJSONObject("current")
            return WeatherReading(
                temperature = current.getDouble("temperature_2m").toInt(),
                weatherCode = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1,
                cloudCover = current.optInt("cloud_cover", -1).coerceIn(-1, 100)
            ).also { save(it, location) }
        } finally {
            connection.disconnect()
        }
    }

    fun cached(): WeatherReading? {
        if (!prefs.contains(KEY_TEMPERATURE)) return null
        return WeatherReading(
            temperature = prefs.getInt(KEY_TEMPERATURE, 0),
            weatherCode = prefs.getInt(KEY_WEATHER_CODE, 0),
            isDay = prefs.getBoolean(KEY_IS_DAY, true),
            cloudCover = prefs.getInt(KEY_CLOUD_COVER, -1)
        )
    }

    fun lastUpdatedAt(): Long = prefs.getLong(KEY_UPDATED_AT, 0L)
    fun lastError(): String? = prefs.getString(KEY_LAST_ERROR, null)?.takeIf { it.isNotBlank() }

    fun registerOnChange(callback: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> callback() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterOnChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun save(reading: WeatherReading, location: Location) {
        prefs.edit()
            .putInt(KEY_TEMPERATURE, reading.temperature)
            .putInt(KEY_WEATHER_CODE, reading.weatherCode)
            .putBoolean(KEY_IS_DAY, reading.isDay)
            .putInt(KEY_CLOUD_COVER, reading.cloudCover)
            .putString(KEY_LATITUDE, location.latitude.toString())
            .putString(KEY_LONGITUDE, location.longitude.toString())
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    private fun saveError(message: String) {
        prefs.edit().putString(KEY_LAST_ERROR, message).apply()
    }

    private fun cachedLocation(): Location? {
        val latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return Location("weather-cache").apply {
            this.latitude = latitude
            this.longitude = longitude
            time = prefs.getLong(KEY_UPDATED_AT, 0L)
        }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PREFS = "weather"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_WEATHER_CODE = "weather_code"
        const val KEY_IS_DAY = "is_day"
        const val KEY_CLOUD_COVER = "cloud_cover"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_LAST_ERROR = "last_error"
        const val LOCATION_TIMEOUT_MS = 12_000L
    }
}
