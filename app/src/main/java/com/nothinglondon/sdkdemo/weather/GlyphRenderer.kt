package com.nothinglondon.sdkdemo.weather

object GlyphRenderer {
    const val SIZE = 13
    // Raw setMatrixFrame(int[]) uses the Glyph Matrix 11-bit intensity range.
    // The 0..255 range documented by GDK applies to GlyphMatrixObject brightness.
    const val MAX_RAW_BRIGHTNESS = 2047
    private const val ON = MAX_RAW_BRIGHTNESS

    private val digits = mapOf(
        '0' to listOf("111", "101", "101", "101", "111"),
        '1' to listOf("010", "110", "010", "010", "111"),
        '2' to listOf("111", "001", "111", "100", "111"),
        '3' to listOf("111", "001", "111", "001", "111"),
        '4' to listOf("101", "101", "111", "001", "001"),
        '5' to listOf("111", "100", "111", "001", "111"),
        '6' to listOf("111", "100", "111", "101", "111"),
        '7' to listOf("111", "001", "010", "010", "010"),
        '8' to listOf("111", "101", "111", "101", "111"),
        '9' to listOf("111", "101", "111", "001", "111"),
        '-' to listOf("000", "000", "111", "000", "000")
    )

    fun temperature(reading: WeatherReading?): IntArray {
        val pixels = IntArray(SIZE * SIZE)
        if (reading == null) {
            drawText(pixels, "--", 3, 4)
            return pixels
        }
        val value = reading.temperature.coerceIn(-99, 99).toString()
        val width = value.length * 4 - 1
        drawText(pixels, value, (SIZE - width) / 2, 3)
        // Degree marker and a compact C below the number.
        set(pixels, 9, 2); set(pixels, 10, 2); set(pixels, 9, 3); set(pixels, 10, 3)
        for (x in 5..7) set(pixels, x, 9)
        set(pixels, 5, 10); set(pixels, 5, 11)
        for (x in 5..7) set(pixels, x, 12)
        return pixels
    }

    fun condition(code: Int, isDay: Boolean = true, cloudCover: Int = -1): IntArray = IntArray(SIZE * SIZE).also { pixels ->
        when (code) {
            in 0..3 -> skyByCloudCover(pixels, code, isDay, cloudCover)
            45, 48 -> fog(pixels)
            in 51..57 -> { cloud(pixels); drizzle(pixels) }
            in 61..67, in 80..82 -> { cloud(pixels); rain(pixels) }
            in 71..77, in 85..86 -> { cloud(pixels); snow(pixels) }
            in 95..99 -> { cloud(pixels); lightning(pixels) }
            else -> cloud(pixels)
        }
    }

    fun animatedCondition(
        code: Int,
        phase: Int,
        intensity: Int,
        isDay: Boolean = true,
        cloudCover: Int = -1
    ): IntArray =
        IntArray(SIZE * SIZE).also { pixels ->
            val p = phase.coerceAtLeast(0)
            when (code) {
                in 0..3 -> animatedSkyByCloudCover(pixels, code, isDay, cloudCover, p, intensity)
                45, 48 -> fog(pixels, p)
                in 51..57 -> { cloud(pixels); drizzle(pixels, p) }
                in 51..67, in 80..82 -> {
                    cloud(pixels, if (intensity >= 3) swayOffset(p) else 0)
                    rain(pixels, p, intensity)
                }
                in 71..77, in 85..86 -> { cloud(pixels); snow(pixels, p, intensity) }
                in 95..99 -> { cloud(pixels); lightning(pixels, p) }
                else -> cloud(pixels)
            }
        }

    fun brightness(frame: IntArray, percent: Int): IntArray {
        val factor = percent.coerceIn(10, 100) / 100f
        return IntArray(frame.size) { index ->
            (frame[index] * factor).toInt().coerceIn(0, MAX_RAW_BRIGHTNESS)
        }
    }

    fun fade(from: IntArray, to: IntArray, steps: Int = 4): List<IntArray> =
        (1..steps).map { step ->
            val progress = step.toFloat() / steps
            IntArray(SIZE * SIZE) { index ->
                (from[index] + (to[index] - from[index]) * progress).toInt()
            }
        }

    fun shifted(frame: IntArray, offsetX: Int): IntArray {
        val result = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val sourceX = x - offsetX
            if (sourceX in 0 until SIZE) result[y * SIZE + x] = frame[y * SIZE + sourceX]
        }
        return result
    }

    internal fun swayOffset(phase: Int): Int = SWAY_SEQUENCE[Math.floorMod(phase, SWAY_SEQUENCE.size)]

    private fun drawText(pixels: IntArray, text: String, startX: Int, startY: Int) {
        text.forEachIndexed { index, char ->
            digits[char]?.forEachIndexed { y, row ->
                row.forEachIndexed { x, bit -> if (bit == '1') set(pixels, startX + index * 4 + x, startY + y) }
            }
        }
    }

    private fun sun(p: IntArray) {
        listOf(6 to 1, 6 to 11, 1 to 6, 11 to 6, 3 to 3, 9 to 3, 3 to 9, 9 to 9)
            .forEach { (x, y) -> set(p, x, y) }
        drawDisc(p, 6, 6, 2)
    }

    private fun animatedSun(p: IntArray, phase: Int, intensity: Int) {
        drawDisc(p, 6, 6, 2)
        val inner = listOf(6 to 3, 9 to 6, 6 to 9, 3 to 6, 4 to 4, 8 to 4, 8 to 8, 4 to 8)
        val outer = listOf(6 to 1, 11 to 6, 6 to 11, 1 to 6, 3 to 3, 9 to 3, 9 to 9, 3 to 9)
        when (phase % 3) {
            0 -> inner.forEach { (x, y) -> set(p, x, y) }
            1 -> {
                inner.forEach { (x, y) -> set(p, x, y, if (intensity >= 2) ON / 2 else ON / 4) }
                outer.forEach { (x, y) -> set(p, x, y) }
            }
            else -> outer.forEach { (x, y) -> set(p, x, y, if (intensity >= 3) ON / 2 else ON / 3) }
        }
    }

    private fun moon(p: IntArray) {
        drawDisc(p, 6, 6, 3)
        clearDisc(p, 8, 4, 2)
        set(p, 2, 2, ON / 2); set(p, 10, 3, ON / 2); set(p, 10, 9, ON / 2)
    }

    private fun animatedMoon(p: IntArray, phase: Int, intensity: Int) {
        drawDisc(p, 6, 6, 3)
        clearDisc(p, 8, 4, 2)
        val stars = listOf(2 to 2, 10 to 3, 10 to 9, 3 to 11)
        stars.forEachIndexed { index, point ->
            if ((phase + index) % maxOf(2, 5 - intensity) == 0) set(p, point.first, point.second, ON * 2 / 3)
        }
    }

    private fun skyByCloudCover(p: IntArray, code: Int, isDay: Boolean, cloudCover: Int) {
        when (cloudLevel(code, cloudCover)) {
            0 -> if (isDay) sun(p) else moon(p)
            1 -> mostlyClear(p, isDay)
            2 -> partlyCloudy(p, isDay = isDay)
            else -> cloud(p)
        }
    }

    private fun animatedSkyByCloudCover(
        p: IntArray, code: Int, isDay: Boolean, cloudCover: Int, phase: Int, intensity: Int
    ) {
        when (cloudLevel(code, cloudCover)) {
            0 -> if (isDay) animatedSun(p, phase, intensity) else animatedMoon(p, phase, intensity)
            1 -> mostlyClear(p, isDay, phase, intensity)
            2 -> partlyCloudy(p, phase, isDay)
            else -> cloud(p, swayOffset(phase))
        }
    }

    internal fun cloudLevel(code: Int, cloudCover: Int): Int = when {
        cloudCover in 0..15 -> 0
        cloudCover in 16..35 -> 1
        cloudCover in 36..75 -> 2
        cloudCover in 76..100 -> 3
        code == 0 -> 0
        code == 1 -> 1
        code == 2 -> 2
        else -> 3
    }

    private fun mostlyClear(p: IntArray, isDay: Boolean, phase: Int = 0, intensity: Int = 2) {
        if (isDay) animatedSun(p, phase, intensity) else animatedMoon(p, phase, intensity)
        // A small cloud in the lower-right corner; clear pixels behind it first.
        listOf(8 to 8, 9 to 8, 7 to 9, 10 to 9, 7 to 10, 8 to 10, 9 to 10, 10 to 10)
            .forEach { (x, y) -> set(p, x, y) }
    }

    private fun cloud(p: IntArray, offset: Int = 0) {
        // Thin rounded contour leaves visual space for precipitation below.
        for (x in 5..7) set(p, x + offset, 4)
        set(p, 4 + offset, 5); set(p, 8 + offset, 5)
        set(p, 3 + offset, 6); set(p, 9 + offset, 6)
        set(p, 2 + offset, 7); set(p, 10 + offset, 7)
        set(p, 2 + offset, 8); set(p, 10 + offset, 8)
        for (x in 3..9) set(p, x + offset, 9)
    }

    private fun partlyCloudy(p: IntArray, phase: Int = 0, isDay: Boolean = true) {
        val rays = if (phase % 2 == 0) listOf(3 to 1, 1 to 3, 5 to 3) else listOf(1 to 1, 5 to 1, 3 to 4)
        if (isDay) {
            drawDisc(p, 3, 3, 1)
            rays.forEach { (x, y) -> set(p, x, y, ON * 3 / 4) }
        } else {
            smallCrescent(p)
        }
        cloud(p)
    }

    private fun smallCrescent(p: IntArray) {
        listOf(2 to 1, 3 to 1, 1 to 2, 1 to 3, 2 to 4, 3 to 4, 4 to 3)
            .forEach { (x, y) -> set(p, x, y) }
    }

    private fun fog(p: IntArray, phase: Int = 0) {
        cloud(p)
        val shift = phase % 3 - 1
        for (x in 2..8) set(p, x + shift, 11, ON * 3 / 4)
        for (x in 4..10) set(p, x - shift, 12, ON / 2)
    }

    private fun drizzle(p: IntArray, phase: Int = 0) {
        listOf(4, 7, 10).forEachIndexed { i, x ->
            set(p, x, 10 + ((phase + i) % 3), ON * 2 / 3)
        }
    }

    private fun rain(p: IntArray, phase: Int = 0, intensity: Int = 2) {
        val count = intensity.coerceIn(1, 3)
        listOf(3, 6, 9).take(count).forEachIndexed { i, x ->
            val y = 10 + ((phase + i) % 3)
            set(p, x, y); set(p, x - 1, y + 1)
        }
    }
    private fun snow(p: IntArray, phase: Int = 0, intensity: Int = 2) {
        listOf(3, 6, 9).take(intensity.coerceIn(1, 3)).forEachIndexed { i, x ->
            val y = 10 + ((phase + i * 2) % 3)
            set(p, x, y); set(p, x - 1, y + 1); set(p, x + 1, y + 1)
        }
    }
    private fun lightning(p: IntArray, phase: Int = 0) {
        if (phase % 5 !in 0..1) return
        listOf(7 to 10, 6 to 11, 7 to 11, 6 to 12).forEach { set(p, it.first, it.second) }
    }

    private fun drawDisc(pixels: IntArray, centerX: Int, centerY: Int, radius: Int) {
        for (y in centerY - radius..centerY + radius) {
            for (x in centerX - radius..centerX + radius) {
                if ((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= radius * radius + 1) {
                    set(pixels, x, y)
                }
            }
        }
    }

    private fun clearDisc(pixels: IntArray, centerX: Int, centerY: Int, radius: Int) {
        for (y in centerY - radius..centerY + radius) for (x in centerX - radius..centerX + radius) {
            if ((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= radius * radius + 1 &&
                x in 0 until SIZE && y in 0 until SIZE) pixels[y * SIZE + x] = 0
        }
    }

    private fun set(pixels: IntArray, x: Int, y: Int, brightness: Int = ON) {
        if (x in 0 until SIZE && y in 0 until SIZE) pixels[y * SIZE + x] = brightness
    }

    private val SWAY_SEQUENCE = intArrayOf(0, -1, 0, 1, 0)
}
