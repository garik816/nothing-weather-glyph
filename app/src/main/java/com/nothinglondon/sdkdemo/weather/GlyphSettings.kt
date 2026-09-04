package com.nothinglondon.sdkdemo.weather

import android.content.Context
import android.content.SharedPreferences

enum class DisplayMode(val title: String) {
    BOTH("Погода + температура"), TEMPERATURE("Только температура"), CONDITION("Только погода")
}

enum class AnimationStyle(val title: String) {
    INSTANT("Без анимации"), FADE("Затухание"), BLINK("Мигание"), SLIDE("Сдвиг")
}

enum class WeatherAnimationPreset(
    val title: String,
    val weatherCode: Int,
    val isDay: Boolean,
    val cloudCover: Int
) {
    CLEAR_DAY("Ясно · день", 0, true, 0),
    MOSTLY_CLEAR_DAY("Малооблачно · день", 1, true, 25),
    PARTLY_CLOUDY_DAY("Переменная облачность · день", 2, true, 55),
    OVERCAST("Пасмурно", 3, true, 90),
    CLEAR_NIGHT("Ясно · ночь", 0, false, 0),
    MOSTLY_CLEAR_NIGHT("Малооблачно · ночь", 1, false, 25),
    PARTLY_CLOUDY_NIGHT("Переменная облачность · ночь", 2, false, 55),
    FOG("Туман", 45, true, 100),
    DRIZZLE("Морось", 51, true, 100),
    RAIN("Дождь", 61, true, 100),
    SHOWER("Ливень", 80, true, 100),
    SNOW("Снег", 71, true, 100),
    THUNDERSTORM("Гроза", 95, true, 100)
}

data class GlyphSettings(
    val displayMode: DisplayMode = DisplayMode.BOTH,
    val animation: AnimationStyle = AnimationStyle.FADE,
    val brightness: Int = 80,
    val screenDurationMs: Long = 2_500,
    val animationStepMs: Long = 100,
    val timeoutMs: Long = 60_000,
    val repeatForever: Boolean = true,
    val animateWeather: Boolean = true,
    val weatherIntensity: Int = 2,
    val weatherUpdateIntervalMs: Long = 15 * 60_000L
)

class GlyphSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load() = GlyphSettings(
        displayMode = enumValueOrDefault(prefs.getString(MODE, null), DisplayMode.BOTH),
        animation = enumValueOrDefault(prefs.getString(ANIMATION, null), AnimationStyle.FADE),
        brightness = prefs.getInt(BRIGHTNESS, 80).coerceIn(10, 100),
        screenDurationMs = prefs.getLong(SCREEN_DURATION, 2_500).coerceIn(1_000, 10_000),
        animationStepMs = prefs.getLong(ANIMATION_STEP, 100).coerceIn(40, 400),
        timeoutMs = prefs.getLong(TIMEOUT, 60_000).coerceAtLeast(0),
        repeatForever = prefs.getBoolean(REPEAT_FOREVER, true),
        animateWeather = prefs.getBoolean(ANIMATE_WEATHER, true),
        weatherIntensity = prefs.getInt(WEATHER_INTENSITY, 2).coerceIn(1, 3),
        weatherUpdateIntervalMs = prefs.getLong(WEATHER_UPDATE_INTERVAL, 15 * 60_000L)
            .coerceIn(5 * 60_000L, 180 * 60_000L)
    )

    fun save(value: GlyphSettings) {
        prefs.edit()
            .putString(MODE, value.displayMode.name)
            .putString(ANIMATION, value.animation.name)
            .putInt(BRIGHTNESS, value.brightness)
            .putLong(SCREEN_DURATION, value.screenDurationMs)
            .putLong(ANIMATION_STEP, value.animationStepMs)
            .putLong(TIMEOUT, value.timeoutMs)
            .putBoolean(REPEAT_FOREVER, value.repeatForever)
            .putBoolean(ANIMATE_WEATHER, value.animateWeather)
            .putInt(WEATHER_INTENSITY, value.weatherIntensity)
            .putLong(WEATHER_UPDATE_INTERVAL, value.weatherUpdateIntervalMs)
            .apply()
    }

    fun loadPreviewPreset(): WeatherAnimationPreset? = prefs.getString(PREVIEW_PRESET, null)?.let { name ->
        runCatching { enumValueOf<WeatherAnimationPreset>(name) }.getOrNull()
    }

    fun setPreviewPreset(value: WeatherAnimationPreset?) {
        prefs.edit().apply {
            if (value == null) remove(PREVIEW_PRESET) else putString(PREVIEW_PRESET, value.name)
        }.apply()
    }

    fun registerOnChange(callback: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> callback() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterOnChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, fallback: T): T =
        runCatching { enumValueOf<T>(name.orEmpty()) }.getOrDefault(fallback)

    private companion object {
        const val PREFS = "glyph_settings"
        const val MODE = "display_mode"
        const val ANIMATION = "animation"
        const val BRIGHTNESS = "brightness"
        const val SCREEN_DURATION = "screen_duration"
        const val ANIMATION_STEP = "animation_step"
        const val TIMEOUT = "timeout"
        const val REPEAT_FOREVER = "repeat_forever"
        const val ANIMATE_WEATHER = "animate_weather"
        const val WEATHER_INTENSITY = "weather_intensity"
        const val WEATHER_UPDATE_INTERVAL = "weather_update_interval"
        const val PREVIEW_PRESET = "preview_preset"
    }
}
