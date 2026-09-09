package com.nothinglondon.sdkdemo.weather

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CustomGlyphAnimation(
    val name: String = "Моя анимация",
    val frameDurationMs: Long = 120L,
    val frames: List<IntArray> = listOf(IntArray(GlyphRenderer.SIZE * GlyphRenderer.SIZE))
) {
    fun normalized(): CustomGlyphAnimation {
        val safeFrames = frames.take(MAX_FRAMES).map { source ->
            IntArray(FRAME_SIZE) { index ->
                if (index < source.size && source[index] > 0) GlyphRenderer.MAX_RAW_BRIGHTNESS else 0
            }
        }.ifEmpty { listOf(IntArray(FRAME_SIZE)) }
        return copy(
            name = name.trim().take(MAX_NAME_LENGTH).ifEmpty { "Моя анимация" },
            frameDurationMs = frameDurationMs.coerceIn(40L, 1_000L),
            frames = safeFrames
        )
    }

    companion object {
        const val MAX_FRAMES = 48
        const val MAX_NAME_LENGTH = 40
        const val FRAME_SIZE = GlyphRenderer.SIZE * GlyphRenderer.SIZE
    }
}

class CustomGlyphAnimationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): CustomGlyphAnimation = decode(prefs.getString(SAVED_ANIMATION, null))
        ?: CustomGlyphAnimation()

    fun save(animation: CustomGlyphAnimation) {
        prefs.edit().putString(SAVED_ANIMATION, encode(animation.normalized())).apply()
    }

    companion object {
        private const val PREFS = "custom_glyph_animation"
        private const val SAVED_ANIMATION = "saved_animation"

        fun encode(animation: CustomGlyphAnimation): String {
            val safe = animation.normalized()
            return JSONObject().apply {
                put("name", safe.name)
                put("frameDurationMs", safe.frameDurationMs)
                put("frames", JSONArray().apply {
                    safe.frames.forEach { frame ->
                        put(frame.joinToString(separator = "") { if (it > 0) "1" else "0" })
                    }
                })
            }.toString()
        }

        fun decode(raw: String?): CustomGlyphAnimation? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(raw)
                val encodedFrames = root.getJSONArray("frames")
                val frames = buildList {
                    for (frameIndex in 0 until minOf(encodedFrames.length(), CustomGlyphAnimation.MAX_FRAMES)) {
                        val encoded = encodedFrames.getString(frameIndex)
                        add(IntArray(CustomGlyphAnimation.FRAME_SIZE) { index ->
                            if (encoded.getOrNull(index) == '1') GlyphRenderer.MAX_RAW_BRIGHTNESS else 0
                        })
                    }
                }
                CustomGlyphAnimation(
                    name = root.optString("name", "Моя анимация"),
                    frameDurationMs = root.optLong("frameDurationMs", 120L),
                    frames = frames
                ).normalized()
            }.getOrNull()
        }
    }
}
