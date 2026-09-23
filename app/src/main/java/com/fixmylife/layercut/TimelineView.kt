package com.fixmylife.layercut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Multi-track timeline. The playhead is fixed in the middle and content scrolls under it.
 * Top rows are overlay layers (top-most first); the bottom row is the main track.
 *
 * Long-press any clip and drag it to rearrange:
 *  - within the main row: reorder main clips
 *  - onto an overlay row: move in time / change layer (or turn a main clip into an overlay)
 *  - from an overlay row down onto the main row: turn it into a main clip
 */
class TimelineView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onScrub(ms: Long)
        fun onSelect(id: String?)
        fun onEditBegin()
        fun onEdited()
    }

    var project: Project? = null
    var listener: Listener? = null
    var selectedId: String? = null
        set(v) { field = v; invalidate() }
    val thumbs = HashMap<String, Bitmap>()

    var timeMs = 0L
        private set

    private val d = resources.displayMetrics.density
    private var pxPerMs = 0.07f * d
    private var vScroll = 0f

    private val rulerH = 22 * d
    private val rowH = 46 * d
    private val mainH = 58 * d
    private val gap = 6 * d
    private val handleW = 12 * d
    private val minMs = 200L

    private val bgPaint = Paint().apply { color = Color.parseColor("#15171C") }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5A5F6B"); strokeWidth = d }
    private val tickText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A8F9B"); textSize = 10 * d }
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1F6E68") }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4E3A99") }
    private val shade = Paint().apply { color = Color.parseColor("#66000000") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11 * d; isFakeBoldText = true }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleGrip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#15171C"); strokeWidth = 2 * d }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * d }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A8F9B"); textSize = 13 * d; textAlign = Paint.Align.CENTER }
    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val dimOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#88FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val insertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF2BD4C0"); strokeWidth = 3 * d }
    private val dropText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF2BD4C0"); textSize = 11 * d; isFakeBoldText = true }

    private class Block(val clip: Clip, val rect: RectF, val overlay: Boolean)

    fun setTime(ms: Long) {
        if (mode == Mode.SCROLL) return
        timeMs = ms
        invalidate()
    }

    private fun originX() = width / 2f - timeMs * pxPerMs
    private fun xOf(ms: Long) = originX() + ms * pxPerMs
    private fun msAt(x: Float): Long = ((x - originX()) / pxPerMs).toLong()

    private fun contentHeight(): Float {
        val n = project?.overlays?.size ?: 0
        return rulerH + gap + (n + 1) * (rowH + gap) + mainH + gap
    }

    private fun blocks(): List<Block> {
        val p = project ?: return emptyList()
        val out = ArrayList<Block>()
        var y = rulerH + gap - vScroll
        for (o in p.overlays) {
            out.add(Block(o, RectF(xOf(o.startMs), y, xOf(o.startMs + o.durationMs), y + rowH), true))
            y += rowH + gap
        }
        var t = 0L
        for (c in p.main) {
            out.add(Block(c, RectF(xOf(t), y, xOf(t + c.durationMs), y + mainH), false))
            t += c.durationMs
        }
        return out
    }

    private fun mainRowTop(): Float {
        val n = project?.overlays?.size ?: 0
        return rulerH + gap + n * (rowH + gap) - vScroll
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawRuler(canvas)
        val p = project
        if (p != null) {
            val bl = blocks()
            for (b in bl) {
                if (mode == Mode.DRAG && b.clip === dragClip) {
                    canvas.drawRoundRect(b.rect, 6 * d, 6 * d, dimOutline)
                } else {
                    drawBlock(canvas, b)
                }
            }
            if (p.main.isEmpty() && mode != Mode.DRAG) {
                canvas.drawText("Main track is empty - tap + Media", width / 2f, mainRowTop() + mainH / 2 + 5 * d, hintPaint)
            }
            if (mode == Mode.DRAG) drawDrag(canvas) else {
                bl.firstOrNull { it.clip.id == selectedId }?.let { drawSelection(canvas, it) }
            }
        }
        val cx = width / 2f
        canvas.drawLine(cx, 0f, cx, height.toFloat(), playheadPaint)
        val tri = Path().apply { moveTo(cx - 6 * d, 0f); lineTo(cx + 6 * d, 0f); lineTo(cx, 8 * d); close() }
        canvas.drawPath(tri, handlePaint)
    }

    private fun drawRuler(canvas: Canvas) {
        val steps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 5f, 10f, 30f, 60f)
        var stepS = 60f
        for (s in steps) {
            if (s * 1000 * pxPerMs >= 56 * d) { stepS = s; break }
        }
        val stepMs = (stepS * 1000).toLong()
        var ms = max(0L, ((-originX()) / pxPerMs).toLong() / stepMs * stepMs)
        while (true) {
            val x = xOf(ms)
            if (x > width) break
            canvas.drawLine(x, rulerH - 7 * d, x, rulerH, tickPaint)
            canvas.drawText(formatTime(ms, stepS < 1f), x + 3 * d, rulerH - 9 * d, tickText)
            val half = xOf(ms + stepMs / 2)
            canvas.drawLine(half, rulerH - 3 * d, half, rulerH, tickPaint)
            ms += stepMs
        }
    }

    private fun drawBlockAt(canvas: Canvas, c: Clip, r: RectF, overlay: Boolean) {
        if (r.right < 0 || r.left > width || r.bottom < rulerH || r.top > height) return
        val rr = 6 * d
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(r, rr, rr, Path.Direction.CW) })
        canvas.drawRect(r, if (overlay) overlayPaint else mainPaint)
        val bmp = thumbs[c.id]
        if (bmp != null && bmp.height > 0) {
            val th = r.height()
            val tw = th * bmp.width / bmp.height.toFloat()
            if (tw > 1) {
                var x = r.left
                if (x < 0) x += ((-x) / tw).toInt() * tw
                val src = Rect(0, 0, bmp.width, bmp.height)
                while (x < r.right && x < width) {
                    canvas.drawBitmap(bmp, src, RectF(x, r.top, x + tw, r.bottom), null)
                    x += tw
                }
                canvas.drawRect(r, shade)
            }
        }
        val kind = when {
            c.isImage -> "IMG"
            c.muted || c.volume == 0f || !c.hasAudio -> "VID \uD83D\uDD07"
            abs(c.volume - 1f) > 0.01f -> "VID \uD83D\uDD0A ${(c.volume * 100).toInt()}%"
            else -> "VID \uD83D\uDD0A"
        }
        val crop = if (c.hasCrop) " \u2702" else ""
        canvas.drawText("$kind$crop  ${formatTime(c.durationMs, true)}", max(r.left, 0f) + 6 * d, r.top + 15 * d, labelPaint)
        canvas.restore()
    }

    private fun drawBlock(canvas: Canvas, b: Block) = drawBlockAt(canvas, b.clip, b.rect, b.overlay)

    private fun drawSelection(canvas: Canvas, b: Block) {
        val r = b.rect
        canvas.drawRoundRect(r, 6 * d, 6 * d, selPaint)
        val lh = RectF(r.left - handleW, r.top, r.left, r.bottom)
        val rh = RectF(r.right, r.top, r.right + handleW, r.bottom)
        canvas.drawRoundRect(lh, 4 * d, 4 * d, handlePaint)
        canvas.drawRoundRect(rh, 4 * d, 4 * d, handlePaint)
        canvas.drawLine(lh.centerX(), r.centerY() - 8 * d, lh.centerX(), r.centerY() + 8 * d, handleGrip)
        canvas.drawLine(rh.centerX(), r.centerY() - 8 * d, rh.centerX(), r.centerY() + 8 * d, handleGrip)
    }

    // ------------------------------------------------------------ drag & drop

    private var dragOffsetX = 0f
    private var dragW = 0f
    private var curX = 0f
    private var curY = 0f

    private fun dropToOverlay(): Boolean = curY < mainRowTop() - gap / 2

    private fun overlayRowAt(y: Float, count: Int): Int {
        val row = floor((y - (rulerH + gap - vScroll)) / (rowH + gap)).toInt()
        return row.coerceIn(0, count)
    }

    /** Main-track insertion index for the finger position, ignoring the dragged clip. */
    private fun mainInsert(p: Project, c: Clip): Pair<Int, Long> {
        val t = msAt(curX)
        var acc = 0L
        var idx = 0
        for (m in p.main) {
            if (m === c) continue
            if (t < acc + m.durationMs / 2) return idx to acc
            acc += m.durationMs
            idx++
        }
        return idx to acc
    }

    private fun drawDrag(canvas: Canvas) {
        val p = project ?: return
        val c = dragClip ?: return
        val left = curX - dragOffsetX
        if (dropToOverlay()) {
            val others = p.overlays.count { it !== c }
            val row = overlayRowAt(curY, others)
            val top = rulerH + gap - vScroll + row * (rowH + gap)
            val r = RectF(left, top, left + dragW, top + rowH)
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            drawBlockAt(canvas, c, r, true)
            canvas.drawRoundRect(r, 6 * d, 6 * d, ghostStroke)
            canvas.restore()
            val label = if (row >= others) "New layer \u00B7 starts ${formatTime(max(0L, msAt(left)), true)}"
            else "Layer ${row + 1} \u00B7 starts ${formatTime(max(0L, msAt(left)), true)}"
            canvas.drawText(label, max(4 * d, left), r.top - 3 * d, dropText)
        } else {
            val (_, accMs) = mainInsert(p, c)
            val x = xOf(accMs)
            val top = mainRowTop()
            canvas.drawLine(x, top - 4 * d, x, top + mainH + 4 * d, insertPaint)
            val r = RectF(left, curY - mainH / 2, left + dragW, curY + mainH / 2)
            drawBlockAt(canvas, c, r, false)
            canvas.drawRoundRect(r, 6 * d, 6 * d, ghostStroke)
            canvas.drawText("Main track", max(4 * d, left), r.top - 3 * d, dropText)
        }
    }

    private fun applyDrop() {
        val p = project ?: return
        val c = dragClip ?: return
        val left = curX - dragOffsetX
        val wasOverlay = p.overlays.contains(c)
        if (dropToOverlay()) {
            val others = p.overlays.count { it !== c }
            val row = overlayRowAt(curY, others)
            p.main.remove(c)
            p.overlays.remove(c)
            if (!wasOverlay) { c.cx = 0.5f; c.cy = 0.5f; c.widthFrac = 0.5f }
            c.startMs = max(0L, msAt(left))
            p.overlays.add(row.coerceIn(0, p.overlays.size), c)
        } else {
            val (idx, _) = mainInsert(p, c)
            p.main.remove(c)
            p.overlays.remove(c)
            c.startMs = 0L
            p.main.add(idx.coerceIn(0, p.main.size), c)
        }
    }

    // ------------------------------------------------------------ touch

    private enum class Mode { NONE, SCROLL, TRIM_L, TRIM_R, DRAG, SCALE }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragClip: Clip? = null
    private var origStart = 0L
    private var origTrimS = 0L
    private var origTrimE = 0L
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var longPressFired = false

    private val longPress = Runnable {
        if (mode == Mode.NONE) {
            val hit = blocks().lastOrNull { it.rect.contains(downX, downY) }
            if (hit != null) {
                longPressFired = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                selectedId = hit.clip.id
                listener?.onSelect(hit.clip.id)
                dragOffsetX = downX - hit.rect.left
                dragW = hit.rect.width()
                curX = downX
                curY = downY
                beginDrag(hit.clip, Mode.DRAG)
                invalidate()
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            removeCallbacks(longPress)
            if (mode == Mode.TRIM_L || mode == Mode.TRIM_R || mode == Mode.DRAG) return false
            mode = Mode.SCALE
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pxPerMs = (pxPerMs * detector.scaleFactor).coerceIn(0.004f * d, 1.2f * d)
            invalidate()
            return true
        }
    })

    private fun beginDrag(c: Clip, m: Mode) {
        listener?.onEditBegin()
        dragClip = c
        origStart = c.startMs
        origTrimS = c.trimStartMs
        origTrimE = c.trimEndMs
        mode = m
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                mode = Mode.NONE
                longPressFired = false
                val sel = blocks().firstOrNull { it.clip.id == selectedId }
                if (sel != null && e.y >= sel.rect.top - 6 * d && e.y <= sel.rect.bottom + 6 * d) {
                    val touchW = handleW + 10 * d
                    if (e.x >= sel.rect.left - touchW && e.x <= sel.rect.left + 6 * d) {
                        beginDrag(sel.clip, Mode.TRIM_L)
                    } else if (e.x >= sel.rect.right - 6 * d && e.x <= sel.rect.right + touchW) {
                        beginDrag(sel.clip, Mode.TRIM_R)
                    }
                }
                if (mode == Mode.NONE) postDelayed(longPress, 350)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(longPress)
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                when (mode) {
                    Mode.NONE -> {
                        if (hypot(e.x - downX, e.y - downY) > slop) {
                            removeCallbacks(longPress)
                            mode = Mode.SCROLL
                        }
                    }
                    Mode.SCROLL -> {
                        if (e.pointerCount == 1) {
                            val total = project?.totalDurationMs() ?: 0L
                            timeMs = (timeMs - (dx / pxPerMs).toLong()).coerceIn(0L, total)
                            val maxV = max(0f, contentHeight() - height)
                            vScroll = (vScroll - dy).coerceIn(0f, maxV)
                            listener?.onScrub(timeMs)
                            invalidate()
                        }
                    }
                    Mode.TRIM_L, Mode.TRIM_R -> applyTrim(((e.x - downX) / pxPerMs).toLong())
                    Mode.DRAG -> {
                        curX = e.x
                        curY = e.y
                        // auto-scroll near the edges
                        val edge = 36 * d
                        val total = project?.totalDurationMs() ?: 0L
                        val step = (8 * d / pxPerMs).toLong()
                        if (e.x < edge) timeMs = (timeMs - step).coerceIn(0L, total)
                        else if (e.x > width - edge) timeMs = (timeMs + step).coerceIn(0L, total + 5000)
                        invalidate()
                    }
                    Mode.SCALE -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when (mode) {
                    Mode.NONE -> if (!longPressFired) tap(e.x, e.y)
                    Mode.TRIM_L, Mode.TRIM_R -> listener?.onEdited()
                    Mode.DRAG -> {
                        curX = e.x; curY = e.y
                        applyDrop()
                        mode = Mode.NONE
                        listener?.onEdited()
                    }
                    else -> {}
                }
                mode = Mode.NONE
                dragClip = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (mode == Mode.TRIM_L || mode == Mode.TRIM_R || mode == Mode.DRAG) {
                    mode = Mode.NONE
                    listener?.onEdited()
                }
                mode = Mode.NONE
                dragClip = null
                invalidate()
            }
        }
        lastX = e.x; lastY = e.y
        return true
    }

    private fun tap(x: Float, y: Float) {
        val hit = blocks().lastOrNull { it.rect.contains(x, y) }
        val id = hit?.clip?.id
        selectedId = if (id == selectedId) null else id
        listener?.onSelect(selectedId)
    }

    private fun applyTrim(dms: Long) {
        val c = dragClip ?: return
        val isOverlay = project?.isOverlay(c.id) == true
        val maxImage = 10 * 60_000L
        if (c.isImage) {
            val origDur = origTrimE - origTrimS
            if (mode == Mode.TRIM_L) {
                var newDur = (origDur - dms).coerceIn(minMs, maxImage)
                if (isOverlay) {
                    var ns = origStart + (origDur - newDur)
                    if (ns < 0L) { newDur += ns; ns = 0L }
                    c.startMs = ns
                }
                c.trimStartMs = 0L; c.trimEndMs = newDur
            } else {
                c.trimStartMs = 0L; c.trimEndMs = (origDur + dms).coerceIn(minMs, maxImage)
            }
        } else {
            if (mode == Mode.TRIM_L) {
                var ns = (origTrimS + dms).coerceIn(0L, origTrimE - minMs)
                if (isOverlay) {
                    var start = origStart + (ns - origTrimS)
                    if (start < 0L) { ns -= start; start = 0L }
                    c.startMs = start
                }
                c.trimStartMs = ns
            } else {
                c.trimEndMs = (origTrimE + dms).coerceIn(origTrimS + minMs, c.sourceDurationMs)
            }
        }
        invalidate()
    }

    companion object {
        fun formatTime(ms: Long, tenths: Boolean): String {
            val totalS = ms / 1000
            val m = totalS / 60
            val s = totalS % 60
            return if (tenths) "%d:%02d.%d".format(m, s, (ms % 1000) / 100) else "%d:%02d".format(m, s)
        }
    }
}
