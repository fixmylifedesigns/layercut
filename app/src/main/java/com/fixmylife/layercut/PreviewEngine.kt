package com.fixmylife.layercut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live preview built from stacked views: one layer for the main track and one per overlay.
 * Each video layer has its own ExoPlayer; all layers follow a single master clock.
 * Export is done separately by Media3 Transformer.
 */
class PreviewEngine(private val ctx: Context, private val canvas: FrameLayout) {

    var project: Project? = null
    var onTime: ((Long) -> Unit)? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    var timeMs = 0L
        private set
    var isPlaying = false
        private set

    private var clockStartReal = 0L
    private var clockStartMs = 0L
    private val tracks = LinkedHashMap<String, Track>()
    private var orderSig = ""
    private val bitmaps = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    init {
        canvas.clipChildren = true
        canvas.setBackgroundColor(Color.BLACK)
        canvas.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) ui.post { update() }
        }
    }

    private inner class Track {
        val container = FrameLayout(ctx).apply { clipChildren = true }
        val texture = TextureView(ctx)
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
        var player: ExoPlayer? = null
        var videoClips: List<Clip> = emptyList()
        var playlistSig = ""
        var lastSeekReal = 0L

        init {
            container.addView(texture, FrameLayout.LayoutParams(1, 1))
            container.addView(image, FrameLayout.LayoutParams(1, 1))
        }

        fun setPlaylist(clips: List<Clip>) {
            val sig = clips.joinToString("|") { "${it.id}:${it.trimStartMs}:${it.trimEndMs}" }
            if (sig == playlistSig && (clips.isEmpty() || player != null)) return
            playlistSig = sig
            videoClips = clips
            if (clips.isEmpty()) {
                releasePlayer()
                return
            }
            val p = player ?: ExoPlayer.Builder(ctx).build().also {
                it.setVideoTextureView(texture)
                player = it
            }
            p.setMediaItems(clips.map { c ->
                MediaItem.Builder()
                    .setUri(Uri.parse(c.uri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(c.trimStartMs)
                            .setEndPositionMs(c.trimEndMs.coerceAtMost(c.sourceDurationMs))
                            .build()
                    )
                    .build()
            })
            p.playWhenReady = false
            p.prepare()
            lastSeekReal = 0L
        }

        fun releasePlayer() {
            player?.release()
            player = null
            playlistSig = ""
        }
    }

    // ------------------------------------------------------------ public API

    /** Call after any project change. */
    fun rebuild() {
        val p = project ?: return
        val keys = listOf(MAIN) + p.overlays.map { it.id }
        val gone = tracks.keys.filter { it !in keys }
        for (k in gone) tracks.remove(k)?.releasePlayer()
        // bottom to top: main first, then overlays from the lowest layer to the top-most
        val order = listOf(MAIN) + p.overlays.reversed().map { it.id }
        val sig = order.joinToString(",")
        for (k in order) if (!tracks.containsKey(k)) tracks[k] = Track()
        if (sig != orderSig) {
            orderSig = sig
            canvas.removeAllViews()
            for (k in order) canvas.addView(tracks.getValue(k).container, FrameLayout.LayoutParams(1, 1))
        }
        tracks.getValue(MAIN).setPlaylist(p.main.filter { !it.isImage })
        loadBitmaps(p)
        val total = p.totalDurationMs()
        if (timeMs > total) timeMs = total
        for (tr in tracks.values) tr.lastSeekReal = 0L
        update()
    }

    /** Re-place layers without touching players (used while dragging an overlay). */
    fun refreshLayout() = update()

    fun play() {
        val total = project?.totalDurationMs() ?: 0L
        if (total <= 0L) return
        if (timeMs >= total - 30) timeMs = 0L
        isPlaying = true
        clockStartMs = timeMs
        clockStartReal = SystemClock.elapsedRealtime()
        for (tr in tracks.values) tr.lastSeekReal = 0L
        update()
        onPlayingChanged?.invoke(true)
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun pause() {
        if (!isPlaying) return
        isPlaying = false
        Choreographer.getInstance().removeFrameCallback(frame)
        for (tr in tracks.values) tr.player?.pause()
        onPlayingChanged?.invoke(false)
    }

    fun seekTo(ms: Long) {
        timeMs = max(0L, ms)
        if (isPlaying) {
            clockStartMs = timeMs
            clockStartReal = SystemClock.elapsedRealtime()
        }
        update()
        ui.removeCallbacks(settle)
        ui.postDelayed(settle, 120)
    }

    /** Frees all decoders (before exporting). [rebuild] brings them back. */
    fun releasePlayers() {
        pause()
        for (tr in tracks.values) tr.releasePlayer()
    }

    fun release() {
        releasePlayers()
        tracks.clear()
        io.shutdown()
    }

    // ------------------------------------------------------------ internals

    private val settle = Runnable {
        for (tr in tracks.values) tr.lastSeekReal = 0L
        update()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isPlaying) return
            val total = project?.totalDurationMs() ?: 0L
            timeMs = clockStartMs + (SystemClock.elapsedRealtime() - clockStartReal)
            if (timeMs >= total) {
                timeMs = total
                update()
                onTime?.invoke(timeMs)
                pause()
                return
            }
            update()
            onTime?.invoke(timeMs)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun loadBitmaps(p: Project) {
        for (c in (p.main + p.overlays)) {
            if (!c.isImage || bitmaps.containsKey(c.uri) || loading.contains(c.uri)) continue
            loading.add(c.uri)
            val clip = c
            io.execute {
                val b = MediaProbe.frame(ctx, clip, 0L, 1440)
                ui.post {
                    loading.remove(clip.uri)
                    if (b != null) {
                        bitmaps[clip.uri] = b
                        update()
                    }
                }
            }
        }
    }

    private fun place(v: View, l: Float, t: Float, w: Float, h: Float) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        val wi = max(1, w.roundToInt())
        val hi = max(1, h.roundToInt())
        if (lp.width != wi || lp.height != hi) {
            lp.width = wi
            lp.height = hi
            v.layoutParams = lp
        }
        v.translationX = l
        v.translationY = t
    }

    private fun mainRect(c: Clip, w: Float, h: Float): RectF {
        val a = c.croppedAspect
        val wide = a > w / h
        val rw: Float
        val rh: Float
        if (wide != c.fill) { rw = w; rh = w / a } else { rh = h; rw = h * a }
        return RectF((w - rw) / 2, (h - rh) / 2, (w + rw) / 2, (h + rh) / 2)
    }

    private fun overlayRect(c: Clip, w: Float, h: Float): RectF {
        val rw = c.widthFrac * w
        val rh = rw / c.croppedAspect
        val cx = c.cx * w
        val cy = c.cy * h
        return RectF(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2)
    }

    private fun update() {
        val p = project ?: return
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = timeMs

        tracks[MAIN]?.let { mt ->
            var acc = 0L
            var active: Clip? = null
            var off = 0L
            for (c in p.main) {
                if (t < acc + c.durationMs) { active = c; off = t - acc; break }
                acc += c.durationMs
            }
            drive(mt, active, off, active?.let { mainRect(it, w, h) })
        }

        for (o in p.overlays) {
            val tr = tracks[o.id] ?: continue
            val end = o.startMs + o.durationMs
            val near = t >= o.startMs - 2500 && t < end
            if (o.isImage || !near) tr.setPlaylist(emptyList()) else tr.setPlaylist(listOf(o))
            val on = t >= o.startMs && t < end
            drive(tr, if (on) o else null, t - o.startMs, if (on) overlayRect(o, w, h) else null)
        }
    }

    private fun drive(tr: Track, c: Clip?, offMs: Long, rect: RectF?) {
        if (c == null || rect == null) {
            tr.container.visibility = View.INVISIBLE
            tr.player?.let { if (it.playWhenReady) it.pause() }
            return
        }
        tr.container.visibility = View.VISIBLE
        place(tr.container, rect.left, rect.top, rect.width(), rect.height())
        val fw = rect.width() / (c.cropR - c.cropL).coerceAtLeast(0.01f)
        val fh = rect.height() / (c.cropB - c.cropT).coerceAtLeast(0.01f)
        val target: View = if (c.isImage) tr.image else tr.texture
        place(target, -c.cropL * fw, -c.cropT * fh, fw, fh)

        if (c.isImage) {
            tr.texture.visibility = View.INVISIBLE
            tr.image.visibility = View.VISIBLE
            if (tr.image.tag != c.uri) {
                val b = bitmaps[c.uri]
                if (b != null) {
                    tr.image.setImageBitmap(b)
                    tr.image.tag = c.uri
                } else {
                    tr.image.setImageDrawable(null)
                }
            }
            tr.player?.let { if (it.playWhenReady) it.pause() }
            return
        }

        tr.image.visibility = View.INVISIBLE
        tr.texture.visibility = View.VISIBLE
        val p = tr.player ?: return
        val idx = tr.videoClips.indexOfFirst { it.id == c.id }
        if (idx < 0) return
        p.volume = c.effectiveVolume.coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        val sameItem = p.currentMediaItemIndex == idx
        val drift = if (sameItem) abs(p.currentPosition - offMs) else Long.MAX_VALUE
        if (isPlaying) {
            if ((!sameItem || drift > 350) && now - tr.lastSeekReal > 900) {
                p.seekTo(idx, offMs)
                tr.lastSeekReal = now
            }
            if (!p.playWhenReady) p.play()
        } else {
            if (p.playWhenReady) p.pause()
            if ((!sameItem || drift > 40) && now - tr.lastSeekReal > 60) {
                p.seekTo(idx, offMs)
                tr.lastSeekReal = now
            }
        }
    }

    companion object {
        private const val MAIN = "__main__"
    }
}
