package com.nothinglondon.sdkdemo

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nothinglondon.sdkdemo.ui.theme.NothingAndroidSDKDemoTheme
import com.nothinglondon.sdkdemo.weather.AnimationStyle
import com.nothinglondon.sdkdemo.weather.CloudThresholds
import com.nothinglondon.sdkdemo.weather.CustomGlyphAnimation
import com.nothinglondon.sdkdemo.weather.CustomGlyphAnimationStore
import com.nothinglondon.sdkdemo.weather.DisplayMode
import com.nothinglondon.sdkdemo.weather.GlyphSettings
import com.nothinglondon.sdkdemo.weather.GlyphSettingsStore
import com.nothinglondon.sdkdemo.weather.GlyphRenderer
import com.nothinglondon.sdkdemo.weather.WeatherReading
import com.nothinglondon.sdkdemo.weather.WeatherRepository
import com.nothinglondon.sdkdemo.weather.WeatherAnimationPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Разрешите геолокацию и обновите данные")
    private var previewReading by mutableStateOf<WeatherReading?>(null)
    private lateinit var settingsStore: GlyphSettingsStore
    private lateinit var customAnimationStore: CustomGlyphAnimationStore
    private lateinit var weatherRepository: WeatherRepository
    private var weatherListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) refreshWeather() else status = "Геолокация не разрешена" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = GlyphSettingsStore(this)
        customAnimationStore = CustomGlyphAnimationStore(this)
        weatherRepository = WeatherRepository(this)
        previewReading = weatherRepository.cached()
        updateWeatherStatus()
        weatherListener = weatherRepository.registerOnChange {
            runOnUiThread {
                previewReading = weatherRepository.cached()
                updateWeatherStatus()
            }
        }
        setContent {
            NothingAndroidSDKDemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        initial = settingsStore.load(),
                        initialPreviewPreset = settingsStore.loadPreviewPreset(),
                        initialCustomAnimation = customAnimationStore.load(),
                        initialCustomPreviewActive = settingsStore.loadCustomPreview() != null,
                        reading = previewReading,
                        status = status,
                        onRefresh = ::ensurePermissionAndRefresh,
                        onOpenGlyphToys = ::openGlyphToys,
                        onPreviewOnGlyph = {
                            settingsStore.setPreviewPreset(it)
                            status = "На Glyph включён предпросмотр: ${it.title}"
                        },
                        onSaveCustomAnimation = {
                            customAnimationStore.save(it)
                            status = "Анимация «${it.name}» сохранена"
                        },
                        onPreviewCustomAnimation = {
                            settingsStore.setCustomPreview(it)
                            status = "На Glyph включена анимация «${it.name}»"
                        },
                        onStopGlyphPreview = {
                            settingsStore.clearPreview()
                            status = "На Glyph снова отображается актуальная погода"
                        },
                        onSave = { settingsStore.save(it); status = "Настройки сохранены" }
                    )
                }
            }
        }
        ensurePermissionAndRefresh()
    }

    private fun openGlyphToys() {
        val activities = listOf(
            "com.nothing.thirdparty.matrix.toys.manager.AodToySelectActivity",
            "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity"
        )
        val opened = activities.any { className ->
            runCatching {
                startActivity(Intent().setComponent(ComponentName("com.nothing.thirdparty", className)))
            }.isSuccess
        }
        if (!opened) status = "Nothing OS не разрешила открыть меню Glyph Toys"
    }

    override fun onResume() {
        super.onResume()
        if (::weatherRepository.isInitialized) {
            previewReading = weatherRepository.cached()
            updateWeatherStatus()
        }
    }

    override fun onDestroy() {
        weatherListener?.let { weatherRepository.unregisterOnChange(it) }
        weatherListener = null
        super.onDestroy()
    }

    private fun updateWeatherStatus() {
        val updatedAt = weatherRepository.lastUpdatedAt()
        val error = weatherRepository.lastError()
        status = when {
            error != null && updatedAt > 0L ->
                "Последнее обновление: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updatedAt))}. Ошибка: $error"
            error != null -> "Фоновое обновление: $error"
            updatedAt > 0L ->
                "Обновлено: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updatedAt))}"
            else -> "Погода ещё не загружена"
        }
    }

    private fun ensurePermissionAndRefresh() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) refreshWeather() else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @Suppress("MissingPermission")
    private fun refreshWeather() {
        status = "Определяем местоположение…"
        val manager = getSystemService(LocationManager::class.java)
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) { status = "Включите геолокацию"; return }
        manager.requestSingleUpdate(provider, object : LocationListener {
            override fun onLocationChanged(location: Location) {
                status = "Загружаем погоду…"
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = runCatching { WeatherRepository(this@MainActivity).fetch(location) }
                    withContext(Dispatchers.Main) {
                        status = result.fold(
                            onSuccess = {
                                previewReading = it
                                "Готово: ${it.temperature} °C, облачность ${it.cloudCover.coerceAtLeast(0)}%"
                            },
                            onFailure = { "Ошибка: ${it.message ?: "нет сети"}" }
                        )
                    }
                }
            }
            override fun onProviderDisabled(provider: String) = Unit
            override fun onProviderEnabled(provider: String) = Unit
        }, mainLooper)
    }
}

@Composable
private fun SettingsScreen(
    initial: GlyphSettings,
    initialPreviewPreset: WeatherAnimationPreset?,
    initialCustomAnimation: CustomGlyphAnimation,
    initialCustomPreviewActive: Boolean,
    reading: WeatherReading?,
    status: String,
    onRefresh: () -> Unit,
    onOpenGlyphToys: () -> Unit,
    onPreviewOnGlyph: (WeatherAnimationPreset) -> Unit,
    onSaveCustomAnimation: (CustomGlyphAnimation) -> Unit,
    onPreviewCustomAnimation: (CustomGlyphAnimation) -> Unit,
    onStopGlyphPreview: () -> Unit,
    onSave: (GlyphSettings) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    var previewPreset by remember {
        mutableStateOf(initialPreviewPreset ?: WeatherAnimationPreset.CLEAR_DAY)
    }
    var activePreview by remember {
        mutableStateOf(
            when {
                initialCustomPreviewActive -> "custom"
                initialPreviewPreset != null -> "weather"
                else -> null
            }
        )
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("WEATHER GLYPH", style = MaterialTheme.typography.headlineMedium)
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) { GlyphScreensPreview(reading, value) }
        }
        AnimationCatalog(
            selected = previewPreset,
            settings = value,
            glyphPreviewActive = activePreview == "weather",
            onSelect = { previewPreset = it },
            onPreviewOnGlyph = {
                activePreview = "weather"
                onPreviewOnGlyph(previewPreset)
            },
            onStopGlyphPreview = {
                activePreview = null
                onStopGlyphPreview()
            }
        )
        CustomAnimationEditor(
            initial = initialCustomAnimation,
            brightness = value.brightness,
            glyphPreviewActive = activePreview == "custom",
            onSave = onSaveCustomAnimation,
            onPreviewOnGlyph = {
                activePreview = "custom"
                onPreviewCustomAnimation(it)
            },
            onStopGlyphPreview = {
                activePreview = null
                onStopGlyphPreview()
            }
        )
        Button(onClick = onOpenGlyphToys, modifier = Modifier.fillMaxWidth()) {
            Text("ВЫБРАТЬ GLYPH TOY")
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text("ОБНОВИТЬ ПОГОДУ")
        }
        Spacer(Modifier.height(4.dp))
        Text("ПАРАМЕТРЫ", style = MaterialTheme.typography.titleLarge)
        EnumSelector("Вариант отображения", value.displayMode, DisplayMode.entries) {
            value = value.copy(displayMode = it)
        }
        EnumSelector("Анимация", value.animation, AnimationStyle.entries) {
            value = value.copy(animation = it)
        }
        ToggleSetting("Бесконечный повтор", value.repeatForever) {
            value = value.copy(repeatForever = it)
        }
        Text(
            if (value.repeatForever) "Glyph будет повторяться, пока активна игрушка."
            else "Остановка определяется общим таймаутом."
        )
        ToggleSetting("Живая анимация погоды", value.animateWeather) {
            value = value.copy(animateWeather = it)
        }
        if (value.animateWeather) {
            SettingSlider(
                "Интенсивность погоды", when (value.weatherIntensity) {
                    1 -> "Спокойная"; 2 -> "Обычная"; else -> "Активная"
                }, value.weatherIntensity.toFloat(), 1f..3f, 1
            ) { value = value.copy(weatherIntensity = it.roundToInt().coerceIn(1, 3)) }
        }
        CloudThresholdSettings(value.cloudThresholds) {
            value = value.copy(cloudThresholds = it)
        }
        WeatherUpdateSelector(value.weatherUpdateIntervalMs) {
            value = value.copy(weatherUpdateIntervalMs = it)
        }
        SettingSlider("Яркость", "${value.brightness}%", value.brightness.toFloat(), 10f..100f, 8) {
            value = value.copy(brightness = it.roundToInt())
        }
        SettingSlider(
            "Длительность экрана", "${value.screenDurationMs / 1000f} с",
            value.screenDurationMs.toFloat(), 1_000f..10_000f, 8
        ) { value = value.copy(screenDurationMs = (it / 500).roundToInt() * 500L) }
        SettingSlider(
            "Скорость анимации", "${value.animationStepMs} мс",
            value.animationStepMs.toFloat(), 40f..400f, 8
        ) { value = value.copy(animationStepMs = (it / 20).roundToInt() * 20L) }
        if (!value.repeatForever) {
            TimeoutSelector(value.timeoutMs) { value = value.copy(timeoutMs = it) }
        }
        Button(onClick = { onSave(value) }, modifier = Modifier.fillMaxWidth()) {
            Text("СОХРАНИТЬ НАСТРОЙКИ")
        }
        Text("Изменения применяются к активному Glyph Toy автоматически.")
    }
}

@Composable
private fun CustomAnimationEditor(
    initial: CustomGlyphAnimation,
    brightness: Int,
    glyphPreviewActive: Boolean,
    onSave: (CustomGlyphAnimation) -> Unit,
    onPreviewOnGlyph: (CustomGlyphAnimation) -> Unit,
    onStopGlyphPreview: () -> Unit
) {
    var animation by remember { mutableStateOf(initial.normalized()) }
    var selectedFrame by remember { mutableStateOf(0) }
    var playbackFrame by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(playing, animation.frameDurationMs, animation.frames.size) {
        if (!playing) return@LaunchedEffect
        playbackFrame = selectedFrame.coerceIn(0, animation.frames.lastIndex)
        while (true) {
            delay(animation.frameDurationMs)
            playbackFrame = (playbackFrame + 1) % animation.frames.size
        }
    }

    fun replaceFrame(index: Int, frame: IntArray) {
        val updated = animation.frames.toMutableList()
        updated[index] = frame
        animation = animation.copy(frames = updated)
    }

    val visibleFrame = if (playing) playbackFrame else selectedFrame
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("РЕДАКТОР АНИМАЦИЙ", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = animation.name,
                onValueChange = {
                    animation = animation.copy(name = it.take(CustomGlyphAnimation.MAX_NAME_LENGTH))
                },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            EditableGlyphGrid(
                frame = animation.frames[visibleFrame],
                brightness = brightness,
                enabled = !playing,
                onToggle = { pixelIndex ->
                    val frame = animation.frames[selectedFrame].copyOf()
                    frame[pixelIndex] = if (frame[pixelIndex] > 0) 0 else GlyphRenderer.MAX_RAW_BRIGHTNESS
                    replaceFrame(selectedFrame, frame)
                }
            )
            Text(
                "Кадр ${visibleFrame + 1} из ${animation.frames.size}" +
                    if (playing) " · воспроизведение" else " · коснитесь пикселя",
                style = MaterialTheme.typography.labelLarge
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { selectedFrame = (selectedFrame - 1).coerceAtLeast(0) },
                    enabled = !playing && selectedFrame > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("НАЗАД") }
                OutlinedButton(
                    onClick = { selectedFrame = (selectedFrame + 1).coerceAtMost(animation.frames.lastIndex) },
                    enabled = !playing && selectedFrame < animation.frames.lastIndex,
                    modifier = Modifier.weight(1f)
                ) { Text("ВПЕРЁД") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (animation.frames.size < CustomGlyphAnimation.MAX_FRAMES) {
                            val updated = animation.frames.toMutableList()
                            updated.add(selectedFrame + 1, animation.frames[selectedFrame].copyOf())
                            animation = animation.copy(frames = updated)
                            selectedFrame++
                        }
                    },
                    enabled = !playing && animation.frames.size < CustomGlyphAnimation.MAX_FRAMES,
                    modifier = Modifier.weight(1f)
                ) { Text("КОПИЯ") }
                OutlinedButton(
                    onClick = {
                        if (animation.frames.size < CustomGlyphAnimation.MAX_FRAMES) {
                            val updated = animation.frames.toMutableList()
                            updated.add(selectedFrame + 1, IntArray(CustomGlyphAnimation.FRAME_SIZE))
                            animation = animation.copy(frames = updated)
                            selectedFrame++
                        }
                    },
                    enabled = !playing && animation.frames.size < CustomGlyphAnimation.MAX_FRAMES,
                    modifier = Modifier.weight(1f)
                ) { Text("ПУСТОЙ") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { replaceFrame(selectedFrame, IntArray(CustomGlyphAnimation.FRAME_SIZE)) },
                    enabled = !playing,
                    modifier = Modifier.weight(1f)
                ) { Text("ОЧИСТИТЬ") }
                OutlinedButton(
                    onClick = {
                        if (animation.frames.size == 1) {
                            replaceFrame(0, IntArray(CustomGlyphAnimation.FRAME_SIZE))
                        } else {
                            val updated = animation.frames.toMutableList().apply { removeAt(selectedFrame) }
                            animation = animation.copy(frames = updated)
                            selectedFrame = selectedFrame.coerceAtMost(updated.lastIndex)
                        }
                    },
                    enabled = !playing,
                    modifier = Modifier.weight(1f)
                ) { Text("УДАЛИТЬ") }
            }
            SettingSlider(
                "Скорость кадров",
                "${animation.frameDurationMs} мс",
                animation.frameDurationMs.toFloat(),
                40f..1_000f,
                23
            ) { newValue ->
                animation = animation.copy(frameDurationMs = (newValue / 20).roundToInt() * 20L)
            }
            Button(
                onClick = { playing = !playing },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (playing) "ОСТАНОВИТЬ" else "ВОСПРОИЗВЕСТИ") }
            OutlinedButton(
                onClick = { onSave(animation.normalized()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("СОХРАНИТЬ АНИМАЦИЮ") }
            Button(
                onClick = { onPreviewOnGlyph(animation.normalized()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ПОКАЗАТЬ НА GLYPH") }
            if (glyphPreviewActive) {
                OutlinedButton(onClick = onStopGlyphPreview, modifier = Modifier.fillMaxWidth()) {
                    Text("ВЕРНУТЬ АКТУАЛЬНУЮ ПОГОДУ")
                }
            }
            Text(
                "До ${CustomGlyphAnimation.MAX_FRAMES} кадров. Яркость берётся из общих настроек.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EditableGlyphGrid(
    frame: IntArray,
    brightness: Int,
    enabled: Boolean,
    onToggle: (Int) -> Unit
) {
    val displayed = GlyphRenderer.brightness(frame, brightness)
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .pointerInput(enabled, frame.contentHashCode()) {
                    detectTapGestures { position ->
                        if (!enabled) return@detectTapGestures
                        val cellWidth = size.width.toFloat() / GlyphRenderer.SIZE
                        val cellHeight = size.height.toFloat() / GlyphRenderer.SIZE
                        val x = (position.x / cellWidth).toInt().coerceIn(0, GlyphRenderer.SIZE - 1)
                        val y = (position.y / cellHeight).toInt().coerceIn(0, GlyphRenderer.SIZE - 1)
                        onToggle(y * GlyphRenderer.SIZE + x)
                    }
                }
        ) {
            val cell = size.minDimension / GlyphRenderer.SIZE
            val radius = cell * 0.31f
            for (y in 0 until GlyphRenderer.SIZE) for (x in 0 until GlyphRenderer.SIZE) {
                val pixel = displayed[y * GlyphRenderer.SIZE + x]
                val level = pixel.toFloat() / GlyphRenderer.MAX_RAW_BRIGHTNESS
                drawCircle(
                    color = if (pixel == 0) Color.White.copy(alpha = 0.055f)
                    else Color.White.copy(alpha = 0.2f + level * 0.8f),
                    radius = radius,
                    center = androidx.compose.ui.geometry.Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
                )
            }
        }
    }
}

@Composable
private fun CloudThresholdSettings(
    thresholds: CloudThresholds,
    onChange: (CloudThresholds) -> Unit
) {
    val value = thresholds.normalized()
    Text("ПОРОГИ ОБЛАЧНОСТИ", style = MaterialTheme.typography.titleLarge)
    Text(
        "Анимация выбирается по фактическому проценту облачности.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingSlider(
        "Ясно до", "${value.clearMax}%", value.clearMax.toFloat(),
        0f..(value.mostlyClearMax - 1).toFloat(), 0
    ) { newValue ->
        onChange(value.copy(clearMax = newValue.roundToInt()).normalized())
    }
    SettingSlider(
        "Малооблачно до", "${value.mostlyClearMax}%", value.mostlyClearMax.toFloat(),
        (value.clearMax + 1).toFloat()..(value.partlyCloudyMax - 1).toFloat(), 0
    ) { newValue ->
        onChange(value.copy(mostlyClearMax = newValue.roundToInt()).normalized())
    }
    SettingSlider(
        "Переменная облачность до", "${value.partlyCloudyMax}%",
        value.partlyCloudyMax.toFloat(), (value.mostlyClearMax + 1).toFloat()..99f, 0
    ) { newValue ->
        onChange(value.copy(partlyCloudyMax = newValue.roundToInt()).normalized())
    }
    Text(
        "0–${value.clearMax}% ясно · ${value.clearMax + 1}–${value.mostlyClearMax}% малооблачно · " +
            "${value.mostlyClearMax + 1}–${value.partlyCloudyMax}% переменная · " +
            "${value.partlyCloudyMax + 1}–100% пасмурно",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AnimationCatalog(
    selected: WeatherAnimationPreset,
    settings: GlyphSettings,
    glyphPreviewActive: Boolean,
    onSelect: (WeatherAnimationPreset) -> Unit,
    onPreviewOnGlyph: () -> Unit,
    onStopGlyphPreview: () -> Unit
) {
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(settings.animationStepMs, selected) {
        phase = 0
        while (true) {
            delay(settings.animationStepMs.coerceAtLeast(40))
            phase++
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("КАТАЛОГ АНИМАЦИЙ", style = MaterialTheme.typography.titleLarge)
            EnumSelector("Погодная сцена", selected, WeatherAnimationPreset.entries, onSelect)
            GlyphPreviewCard(
                selected.title,
                GlyphRenderer.animatedCondition(
                    selected.weatherCode,
                    phase,
                    settings.weatherIntensity,
                    selected.isDay,
                    selected.cloudCover,
                    settings.cloudThresholds
                ),
                settings.brightness,
                Modifier.fillMaxWidth()
            )
            Button(onClick = onPreviewOnGlyph, modifier = Modifier.fillMaxWidth()) {
                Text("ПОКАЗАТЬ НА GLYPH")
            }
            if (glyphPreviewActive) {
                OutlinedButton(onClick = onStopGlyphPreview, modifier = Modifier.fillMaxWidth()) {
                    Text("ВЕРНУТЬ АКТУАЛЬНУЮ ПОГОДУ")
                }
                Text(
                    "Тестовая сцена активна на выбранном Glyph Toy.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Для аппаратного предпросмотра Weather должен быть выбран как Glyph Toy.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GlyphScreensPreview(reading: WeatherReading?, settings: GlyphSettings) {
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(settings.animationStepMs, settings.animateWeather, reading?.weatherCode) {
        phase = 0
        while (true) {
            delay(settings.animationStepMs.coerceAtLeast(40))
            phase++
        }
    }
    val condition = if (reading == null) null else {
        if (settings.animateWeather) {
            GlyphRenderer.animatedCondition(
                reading.weatherCode,
                phase,
                settings.weatherIntensity,
                reading.isDay,
                reading.cloudCover,
                settings.cloudThresholds
            )
        } else GlyphRenderer.condition(
            reading.weatherCode,
            reading.isDay,
            reading.cloudCover,
            settings.cloudThresholds
        )
    }
    val temperature = GlyphRenderer.temperature(reading)
    Text("Экраны Glyph", style = MaterialTheme.typography.titleLarge)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (settings.displayMode != DisplayMode.TEMPERATURE) {
            GlyphPreviewCard("Погода", condition ?: GlyphRenderer.condition(3), settings.brightness, Modifier.weight(1f))
        }
        if (settings.displayMode != DisplayMode.CONDITION) {
            GlyphPreviewCard("Температура", temperature, settings.brightness, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GlyphPreviewCard(label: String, rawFrame: IntArray, brightness: Int, modifier: Modifier = Modifier) {
    val frame = GlyphRenderer.brightness(rawFrame, brightness)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val cell = size.minDimension / GlyphRenderer.SIZE
                val radius = cell * 0.31f
                for (y in 0 until GlyphRenderer.SIZE) for (x in 0 until GlyphRenderer.SIZE) {
                    val value = frame[y * GlyphRenderer.SIZE + x]
                    val level = value.toFloat() / GlyphRenderer.MAX_RAW_BRIGHTNESS
                    drawCircle(
                        color = if (value == 0) Color.White.copy(alpha = 0.055f)
                        else Color.White.copy(alpha = 0.2f + level * 0.8f),
                        radius = radius,
                        center = androidx.compose.ui.geometry.Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
                    )
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ToggleSetting(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> EnumSelector(label: String, selected: T, values: List<T>, onSelect: (T) -> Unit)
    where T : Enum<T> {
    var expanded by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.titleMedium)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(when (selected) {
                is DisplayMode -> selected.title
                is AnimationStyle -> selected.title
                is WeatherAnimationPreset -> selected.title
                else -> selected.name
            })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { item ->
                DropdownMenuItem(
                    text = { Text(when (item) {
                        is DisplayMode -> item.title
                        is AnimationStyle -> item.title
                        is WeatherAnimationPreset -> item.title
                        else -> item.name
                    }) },
                    onClick = { onSelect(item); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun SettingSlider(
    title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, onChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium); Text(valueText)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
}

@Composable
private fun TimeoutSelector(selected: Long, onSelect: (Long) -> Unit) {
    val options = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 0L)
    var expanded by remember { mutableStateOf(false) }
    fun label(ms: Long) = when (ms) {
        0L -> "Без ограничения"; 15_000L -> "15 секунд"; 30_000L -> "30 секунд"
        60_000L -> "1 минута"; 120_000L -> "2 минуты"; else -> "5 минут"
    }
    Text("Общий таймаут", style = MaterialTheme.typography.titleMedium)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(label(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { timeout ->
                DropdownMenuItem(text = { Text(label(timeout)) }, onClick = { onSelect(timeout); expanded = false })
            }
        }
    }
}

@Composable
private fun WeatherUpdateSelector(selected: Long, onSelect: (Long) -> Unit) {
    val options = listOf(5L, 15L, 30L, 60L, 180L).map { it * 60_000L }
    var expanded by remember { mutableStateOf(false) }
    fun label(ms: Long): String = when (val minutes = ms / 60_000L) {
        60L -> "Каждый час"
        180L -> "Каждые 3 часа"
        else -> "Каждые $minutes мин"
    }
    Text("Обновление погоды", style = MaterialTheme.typography.titleMedium)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { interval ->
                DropdownMenuItem(
                    text = { Text(label(interval)) },
                    onClick = { onSelect(interval); expanded = false }
                )
            }
        }
    }
}
