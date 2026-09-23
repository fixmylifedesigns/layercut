package com.fixmylife.layercut

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * One piece of media on the timeline. Used for both the main track and overlay layers.
 * width/height are the displayed dimensions (rotation already applied).
 */
class Clip(
    var id: String = UUID.randomUUID().toString(),
    var uri: String,
    var mime: String,
    var isImage: Boolean,
    var sourceDurationMs: Long,
    var width: Int,
    var height: Int,
    var hasAudio: Boolean,
    var trimStartMs: Long = 0L,
    var trimEndMs: Long,
    var volume: Float = 1f,
    var muted: Boolean = false,
    var cropL: Float = 0f,
    var cropT: Float = 0f,
    var cropR: Float = 1f,
    var cropB: Float = 1f,
    /** Main track only: fill the canvas (crop edges) instead of fitting with bars. */
    var fill: Boolean = false,
    /** Overlay only: where the overlay starts on the timeline. */
    var startMs: Long = 0L,
    /** Overlay only: center (0..1 of canvas) and width as a fraction of canvas width. */
    var cx: Float = 0.5f,
    var cy: Float = 0.5f,
    var widthFrac: Float = 0.5f,
) {
    val durationMs: Long get() = trimEndMs - trimStartMs

    val hasCrop: Boolean
        get() = cropL > 0.002f || cropT > 0.002f || cropR < 0.998f || cropB < 0.998f

    /** Width / height after cropping. */
    val croppedAspect: Float
        get() {
            val w = width * (cropR - cropL)
            val h = height * (cropB - cropT)
            return if (h <= 0f || w <= 0f) 1f else w / h
        }

    val effectiveVolume: Float get() = if (muted) 0f else volume

    fun duplicate(): Clip = fromJson(toJson()).also { it.id = UUID.randomUUID().toString() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("uri", uri); put("mime", mime); put("isImage", isImage)
        put("sourceDurationMs", sourceDurationMs); put("width", width); put("height", height)
        put("hasAudio", hasAudio); put("trimStartMs", trimStartMs); put("trimEndMs", trimEndMs)
        put("volume", volume.toDouble()); put("muted", muted)
        put("cropL", cropL.toDouble()); put("cropT", cropT.toDouble())
        put("cropR", cropR.toDouble()); put("cropB", cropB.toDouble())
        put("fill", fill); put("startMs", startMs)
        put("cx", cx.toDouble()); put("cy", cy.toDouble()); put("widthFrac", widthFrac.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = Clip(
            id = o.getString("id"),
            uri = o.getString("uri"),
            mime = o.optString("mime", ""),
            isImage = o.getBoolean("isImage"),
            sourceDurationMs = o.getLong("sourceDurationMs"),
            width = o.getInt("width"),
            height = o.getInt("height"),
            hasAudio = o.optBoolean("hasAudio", true),
            trimStartMs = o.getLong("trimStartMs"),
            trimEndMs = o.getLong("trimEndMs"),
            volume = o.optDouble("volume", 1.0).toFloat(),
            muted = o.optBoolean("muted", false),
            cropL = o.optDouble("cropL", 0.0).toFloat(),
            cropT = o.optDouble("cropT", 0.0).toFloat(),
            cropR = o.optDouble("cropR", 1.0).toFloat(),
            cropB = o.optDouble("cropB", 1.0).toFloat(),
            fill = o.optBoolean("fill", false),
            startMs = o.optLong("startMs", 0L),
            cx = o.optDouble("cx", 0.5).toFloat(),
            cy = o.optDouble("cy", 0.5).toFloat(),
            widthFrac = o.optDouble("widthFrac", 0.5).toFloat(),
        )
    }
}

class Project {
    var aspect: String = "9:16"
    val main: MutableList<Clip> = mutableListOf()

    /** Overlay layers. Index 0 is the top-most layer. */
    val overlays: MutableList<Clip> = mutableListOf()

    fun mainDurationMs(): Long = main.sumOf { it.durationMs }

    fun totalDurationMs(): Long {
        val overlayEnd = overlays.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
        return maxOf(mainDurationMs(), overlayEnd)
    }

    fun mainStartOf(clip: Clip): Long {
        var t = 0L
        for (c in main) {
            if (c === clip) return t
            t += c.durationMs
        }
        return t
    }

    fun find(id: String?): Clip? {
        if (id == null) return null
        return main.firstOrNull { it.id == id } ?: overlays.firstOrNull { it.id == id }
    }

    fun isOverlay(id: String?): Boolean = id != null && overlays.any { it.id == id }

    fun aspectRatio(): Float {
        val (w, h) = parseAspect()
        return w.toFloat() / h.toFloat()
    }

    private fun parseAspect(): Pair<Int, Int> {
        val parts = aspect.split(":")
        val w = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val h = parts.getOrNull(1)?.toIntOrNull() ?: 16
        return w to h
    }

    /** Canvas size in pixels where the short side equals [shortSide]. Always even. */
    fun canvasSize(shortSide: Int): Pair<Int, Int> {
        val (aw, ah) = parseAspect()
        return if (aw >= ah) {
            even((shortSide * aw.toFloat() / ah).roundToInt()) to even(shortSide)
        } else {
            even(shortSide) to even((shortSide * ah.toFloat() / aw).roundToInt())
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("aspect", aspect)
        put("main", JSONArray().apply { main.forEach { put(it.toJson()) } })
        put("overlays", JSONArray().apply { overlays.forEach { put(it.toJson()) } })
    }

    fun loadFrom(o: JSONObject) {
        aspect = o.optString("aspect", "9:16")
        main.clear(); overlays.clear()
        o.optJSONArray("main")?.let { a -> for (i in 0 until a.length()) main.add(Clip.fromJson(a.getJSONObject(i))) }
        o.optJSONArray("overlays")?.let { a -> for (i in 0 until a.length()) overlays.add(Clip.fromJson(a.getJSONObject(i))) }
    }

    companion object {
        fun even(v: Int): Int = maxOf(2, v - (v % 2))
    }
}
