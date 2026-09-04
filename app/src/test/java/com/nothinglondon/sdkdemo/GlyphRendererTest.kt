package com.nothinglondon.sdkdemo

import com.nothinglondon.sdkdemo.weather.GlyphRenderer
import com.nothinglondon.sdkdemo.weather.WeatherReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphRendererTest {
    @Test
    fun everyFrameMatchesPhone4aProMatrix() {
        assertEquals(169, GlyphRenderer.temperature(WeatherReading(-99, 0)).size)
        assertEquals(169, GlyphRenderer.temperature(WeatherReading(99, 0)).size)
        (0..99).forEach { code -> assertEquals(169, GlyphRenderer.condition(code).size) }
    }

    @Test
    fun framesContainOnlySupportedBrightnessValues() {
        val frames = listOf(
            GlyphRenderer.temperature(null),
            GlyphRenderer.temperature(WeatherReading(-12, 61)),
            GlyphRenderer.condition(0),
            GlyphRenderer.condition(95)
        )
        assertTrue(frames.all { frame -> frame.all { it == 0 || it == GlyphRenderer.MAX_RAW_BRIGHTNESS } })
    }

    @Test
    fun animationsKeepMatrixSizeAndBrightnessRange() {
        val from = GlyphRenderer.condition(0)
        val to = GlyphRenderer.condition(61)
        GlyphRenderer.fade(from, to).forEach { frame ->
            assertEquals(169, frame.size)
            assertTrue(frame.all { it in 0..GlyphRenderer.MAX_RAW_BRIGHTNESS })
        }
        assertEquals(169, GlyphRenderer.shifted(to, 8).size)
        assertTrue(GlyphRenderer.brightness(to, 10).all { it in 0..205 })
        assertTrue(GlyphRenderer.brightness(to, 100).all {
            it == 0 || it == GlyphRenderer.MAX_RAW_BRIGHTNESS
        })
    }

    @Test
    fun animatedWeatherFramesAreValidAndActuallyMove() {
        listOf(0, 1, 2, 3, 45, 51, 61, 73, 95).forEach { code ->
            val frames = (0..5).map { GlyphRenderer.animatedCondition(code, it, 3) }
            assertTrue(frames.all { it.size == 169 })
            assertTrue(frames.all { frame -> frame.all { it in 0..GlyphRenderer.MAX_RAW_BRIGHTNESS } })
            assertTrue("weather code $code must animate", frames.distinctBy { it.contentHashCode() }.size > 1)
        }
    }

    @Test
    fun clearNightUsesDifferentAnimatedGlyphThanClearDay() {
        val day = GlyphRenderer.animatedCondition(0, 0, 2, isDay = true)
        val night = GlyphRenderer.animatedCondition(0, 0, 2, isDay = false)
        assertTrue(!day.contentEquals(night))
        assertTrue((0..5).map {
            GlyphRenderer.animatedCondition(0, it, 3, isDay = false).contentHashCode()
        }.distinct().size > 1)
    }

    @Test
    fun weatherSwayReturnsToCenterBetweenDirections() {
        assertEquals(listOf(0, -1, 0, 1, 0, 0, -1), (0..6).map(GlyphRenderer::swayOffset))
    }

    @Test
    fun actualCloudCoverOverridesBroadWeatherCode() {
        assertEquals(0, GlyphRenderer.cloudLevel(code = 1, cloudCover = 5))
        assertEquals(1, GlyphRenderer.cloudLevel(code = 1, cloudCover = 25))
        assertEquals(2, GlyphRenderer.cloudLevel(code = 1, cloudCover = 55))
        assertEquals(3, GlyphRenderer.cloudLevel(code = 1, cloudCover = 90))
        assertTrue(!GlyphRenderer.condition(1, true, 5).contentEquals(
            GlyphRenderer.condition(1, true, 55)
        ))
    }

    @Test
    fun sunPulseMovesFromInnerToOuterRays() {
        val inner = GlyphRenderer.animatedCondition(0, 0, 2, true, 0)
        val outer = GlyphRenderer.animatedCondition(0, 1, 2, true, 0)
        assertEquals(GlyphRenderer.MAX_RAW_BRIGHTNESS, inner[3 * 13 + 6])
        assertEquals(GlyphRenderer.MAX_RAW_BRIGHTNESS, outer[1 * 13 + 6])
        assertTrue(!inner.contentEquals(outer))
    }
}
