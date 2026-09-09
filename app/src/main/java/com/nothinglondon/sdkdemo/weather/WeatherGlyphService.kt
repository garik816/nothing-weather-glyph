package com.nothinglondon.sdkdemo.weather

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.nothing.ketchum.GlyphMatrixManager
import com.nothinglondon.sdkdemo.demos.GlyphMatrixService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WeatherGlyphService : GlyphMatrixService("Weather") {
    private data class Screen(
        val frame: IntArray,
        val weatherCode: Int? = null,
        val isDay: Boolean = true,
        val cloudCover: Int = -1
    )

    private var serviceScope: CoroutineScope? = null
    private var renderJob: Job? = null
    private var weatherUpdateJob: Job? = null
    private var settingsChangeJob: Job? = null
    private var weatherChangeJob: Job? = null
    private var activeManager: GlyphMatrixManager? = null
    private var reading: WeatherReading? = null
    private var settingsStore: GlyphSettingsStore? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var weatherRepository: WeatherRepository? = null
    private var weatherListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun performOnServiceConnected(context: Context, glyphMatrixManager: GlyphMatrixManager) {
        // Defensive cleanup in case Nothing OS reconnects without a clean unbind.
        stopRuntime()
        activeManager = glyphMatrixManager
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serviceScope = scope
        val store = GlyphSettingsStore(context)
        settingsStore = store
        settingsListener = store.registerOnChange {
            // save() changes several keys; debounce them into one hot restart.
            settingsChangeJob?.cancel()
            settingsChangeJob = scope.launch {
                delay(150)
                startRenderer()
                startWeatherUpdates(immediate = false)
            }
        }

        val repository = WeatherRepository(context)
        weatherRepository = repository
        weatherListener = repository.registerOnChange {
            weatherChangeJob?.cancel()
            weatherChangeJob = scope.launch {
                delay(100)
                val fresh = repository.cached()
                if (fresh != null && fresh != reading) {
                    reading = fresh
                    startRenderer()
                }
            }
        }
        reading = repository.cached()
        startRenderer()
        startWeatherUpdates(immediate = true)
    }

    override fun performOnServiceDisconnected(context: Context) {
        stopRuntime()
    }

    override fun onAodEvent() {
        // Nothing OS sends this for the selected AOD toy. Reassert the current
        // frame only when rendering was suspended or completed.
        if (renderJob?.isActive != true) startRenderer()
        val repository = weatherRepository
        val interval = settingsStore?.load()?.weatherUpdateIntervalMs ?: return
        if (repository != null && System.currentTimeMillis() - repository.lastUpdatedAt() >= interval) {
            startWeatherUpdates(immediate = true)
        }
    }

    private fun stopRuntime() {
        settingsListener?.let { listener -> settingsStore?.unregisterOnChange(listener) }
        settingsListener = null
        settingsStore = null
        weatherListener?.let { listener -> weatherRepository?.unregisterOnChange(listener) }
        weatherListener = null
        weatherRepository = null
        weatherChangeJob?.cancel()
        weatherChangeJob = null
        settingsChangeJob?.cancel()
        settingsChangeJob = null
        renderJob?.cancel()
        renderJob = null
        weatherUpdateJob?.cancel()
        weatherUpdateJob = null
        serviceScope?.cancel()
        serviceScope = null
        activeManager = null
    }

    private fun startRenderer() {
        val scope = serviceScope ?: return
        val manager = activeManager ?: return
        val store = settingsStore ?: return
        val settings = store.load()
        val previewPreset = store.loadPreviewPreset()
        val customPreview = store.loadCustomPreview()
        renderJob?.cancel()
        renderJob = scope.launch {
            renderLoop(manager, settings, reading, previewPreset, customPreview)
        }
    }

    private fun startWeatherUpdates(immediate: Boolean) {
        val scope = serviceScope ?: return
        val store = settingsStore ?: return
        weatherUpdateJob?.cancel()
        weatherUpdateJob = scope.launch {
            if (!immediate) delay(store.load().weatherUpdateIntervalMs)
            while (isActive) {
                val repository = weatherRepository ?: WeatherRepository(applicationContext)
                val previous = reading
                val fresh = repository.fetchLatest().getOrNull()
                if (fresh != null) {
                    reading = fresh
                    if (fresh != previous) startRenderer()
                    delay(store.load().weatherUpdateIntervalMs)
                } else {
                    if (reading == null) reading = repository.cached()
                    // Network/location may be temporarily unavailable; retry soon.
                    delay(minOf(RETRY_INTERVAL_MS, store.load().weatherUpdateIntervalMs))
                }
            }
        }
    }

    private suspend fun renderLoop(
        manager: GlyphMatrixManager,
        settings: GlyphSettings,
        currentReading: WeatherReading?,
        previewPreset: WeatherAnimationPreset?,
        customPreview: CustomGlyphAnimation?
    ) {
        if (customPreview != null) {
            renderCustomAnimation(manager, settings, customPreview)
            return
        }
        val screens = buildScreens(settings, currentReading, previewPreset)
        val startedAt = SystemClock.elapsedRealtime()
        var index = 0
        var previous = IntArray(GlyphRenderer.SIZE * GlyphRenderer.SIZE)
        do {
            val screen = screens[index]
            val current = GlyphRenderer.brightness(screen.frame, settings.brightness)
            animate(manager, previous, current, settings)
            previous = current
            showScreen(manager, screen, settings)
            index = (index + 1) % screens.size
        } while (currentCoroutineContext().isActive && shouldContinue(settings, startedAt, index))

        if (currentCoroutineContext().isActive && activeManager === manager) {
            withContext(Dispatchers.Main) { manager.turnOff() }
        }
    }

    private suspend fun renderCustomAnimation(
        manager: GlyphMatrixManager,
        settings: GlyphSettings,
        animation: CustomGlyphAnimation
    ) {
        val safe = animation.normalized()
        var frameIndex = 0
        while (currentCoroutineContext().isActive) {
            val frame = GlyphRenderer.brightness(safe.frames[frameIndex], settings.brightness)
            withContext(Dispatchers.Main) { manager.setMatrixFrame(frame) }
            frameIndex = (frameIndex + 1) % safe.frames.size
            delay(safe.frameDurationMs)
        }
    }

    private fun buildScreens(
        settings: GlyphSettings,
        currentReading: WeatherReading?,
        previewPreset: WeatherAnimationPreset?
    ): List<Screen> {
        if (previewPreset != null) return listOf(
            Screen(
                GlyphRenderer.condition(
                    previewPreset.weatherCode,
                    previewPreset.isDay,
                    previewPreset.cloudCover,
                    settings.cloudThresholds
                ),
                previewPreset.weatherCode,
                previewPreset.isDay,
                previewPreset.cloudCover
            )
        )
        if (currentReading == null) return listOf(Screen(GlyphRenderer.temperature(null)))
        return when (settings.displayMode) {
            DisplayMode.BOTH -> listOf(
                Screen(
                    GlyphRenderer.condition(
                        currentReading.weatherCode,
                        currentReading.isDay,
                        currentReading.cloudCover,
                        settings.cloudThresholds
                    ),
                    currentReading.weatherCode,
                    currentReading.isDay,
                    currentReading.cloudCover
                ),
                Screen(GlyphRenderer.temperature(currentReading))
            )
            DisplayMode.TEMPERATURE -> listOf(Screen(GlyphRenderer.temperature(currentReading)))
            DisplayMode.CONDITION -> listOf(
                Screen(
                    GlyphRenderer.condition(
                        currentReading.weatherCode,
                        currentReading.isDay,
                        currentReading.cloudCover,
                        settings.cloudThresholds
                    ),
                    currentReading.weatherCode,
                    currentReading.isDay,
                    currentReading.cloudCover
                )
            )
        }
    }

    private fun shouldContinue(settings: GlyphSettings, startedAt: Long, nextIndex: Int): Boolean {
        if (settings.repeatForever) return true
        if (settings.timeoutMs == 0L) return nextIndex != 0
        return SystemClock.elapsedRealtime() - startedAt < settings.timeoutMs
    }

    private suspend fun showScreen(
        manager: GlyphMatrixManager,
        screen: Screen,
        settings: GlyphSettings
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var phase = 0
        do {
            if (screen.weatherCode != null && settings.animateWeather) {
                val frame = GlyphRenderer.animatedCondition(
                    screen.weatherCode,
                    phase++,
                    settings.weatherIntensity,
                    screen.isDay,
                    screen.cloudCover,
                    settings.cloudThresholds
                )
                withContext(Dispatchers.Main) {
                    manager.setMatrixFrame(GlyphRenderer.brightness(frame, settings.brightness))
                }
            }
            val remaining = settings.screenDurationMs - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) delay(minOf(settings.animationStepMs, remaining))
        } while (currentCoroutineContext().isActive &&
            SystemClock.elapsedRealtime() - startedAt < settings.screenDurationMs)
    }

    private suspend fun animate(
        manager: GlyphMatrixManager,
        previous: IntArray,
        current: IntArray,
        settings: GlyphSettings
    ) {
        val animationFrames = when (settings.animation) {
            AnimationStyle.INSTANT -> listOf(current)
            AnimationStyle.FADE -> GlyphRenderer.fade(previous, current)
            AnimationStyle.BLINK -> listOf(IntArray(current.size), current)
            AnimationStyle.SLIDE -> listOf(12, 8, 4, 0).map { GlyphRenderer.shifted(current, it) }
        }
        animationFrames.forEachIndexed { index, frame ->
            withContext(Dispatchers.Main) { manager.setMatrixFrame(frame) }
            if (index < animationFrames.lastIndex) delay(settings.animationStepMs)
        }
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 2 * 60_000L
    }
}
