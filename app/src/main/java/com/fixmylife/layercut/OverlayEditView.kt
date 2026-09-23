package com.fixmylife.layercut

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Transparent layer over the preview: tap an overlay to select it, drag to move,
 * pinch or drag the corner handle to resize.
 */
class OverlayEditView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onSelect(id: String?)
        fun onEditBegin()
        fun onEdited()
        fun onLiveChange() {}
    }

    var project: Project? = null
    var listener: Listener? = null
    var selectedId: String? = null
        set(v) { field = v; invalidate() }
    var timeMs = 0L
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d
        pathEffect = DashPathEffect(floatArrayOf(8 * d, 5 * d), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF2BD4C0"); strokeWidth = 1.5f * d }

    private fun rectOf(c: Clip): RectF {
        val w = c.widthFrac * width
        val h = w / c.croppedAspect
        val cx = c.cx * width
        val cy = c.cy * height
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    private fun active(c: Clip) = timeMs >= c.startMs && timeMs < c.startMs + c.durationMs

    private var snapX = false
    private var snapY = false

    override fun onDraw(canvas: Canvas) {
        val p = project ?: return
        val c = p.overlays.firstOrNull { it.id == selectedId } ?: return
        val r = rectOf(c)
        if (snapX) canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), guidePaint)
        if (snapY) canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, guidePaint)
        boxPaint.alpha = if (active(c)) 255 else 110
        canvas.drawRect(r, boxPaint)
        canvas.drawCircle(r.right, r.bottom, 9 * d, handlePaint)
    }

    private enum class Mode { NONE, MOVE, RESIZE }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var origCx = 0f
    private var origCy = 0f
    private var origW = 0f
    private var origDist = 1f
    private var edited = false
    private var target: Clip? = null

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val c = target ?: return false
            c.widthFrac = (c.widthFrac * detector.scaleFactor).coerceIn(0.05f, 2f)
            edited = true
            listener?.onLiveChange()
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                edited = false
                mode = Mode.NONE
                val sel = p.overlays.firstOrNull { it.id == selectedId }
                if (sel != null) {
                    val r = rectOf(sel)
                    if (hypot(e.x - r.right, e.y - r.bottom) < 30 * d) {
                        startEdit(sel, Mode.RESIZE, e)
                    } else if (r.contains(e.x, e.y)) {
                        startEdit(sel, Mode.MOVE, e)
                    }
                }
                if (mode == Mode.NONE) {
                    val hit = p.overlays.firstOrNull { active(it) && rectOf(it).contains(e.x, e.y) }
                    if (hit != null) {
                        selectedId = hit.id
                        listener?.onSelect(hit.id)
                        startEdit(hit, Mode.MOVE, e)
                    } else {
                        return false
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val c = target ?: return true
                if (scaleDetector.isInProgress || e.pointerCount > 1) return true
                when (mode) {
                    Mode.MOVE -> {
                        var nx = (origCx + (e.x - downX) / width).coerceIn(0f, 1f)
                        var ny = (origCy + (e.y - downY) / height).coerceIn(0f, 1f)
                        snapX = abs(nx - 0.5f) < 0.02f
                        snapY = abs(ny - 0.5f) < 0.02f
                        if (snapX) nx = 0.5f
                        if (snapY) ny = 0.5f
                        if (abs(e.x - downX) + abs(e.y - downY) > 4 * d) edited = true
                        c.cx = nx; c.cy = ny
                    }
                    Mode.RESIZE -> {
                        val dist = hypot(e.x - c.cx * width, e.y - c.cy * height)
                        c.widthFrac = (origW * dist / origDist).coerceIn(0.05f, 2f)
                        edited = true
                    }
                    Mode.NONE -> {}
                }
                if (edited) listener?.onLiveChange()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                snapX = false; snapY = false
                if (edited) listener?.onEdited()
                mode = Mode.NONE
                target = null
                invalidate()
            }
        }
        return true
    }

    private fun startEdit(c: Clip, m: Mode, e: MotionEvent) {
        listener?.onEditBegin()
        target = c
        mode = m
        origCx = c.cx; origCy = c.cy; origW = c.widthFrac
        origDist = hypot(e.x - c.cx * width, e.y - c.cy * height).coerceAtLeast(1f)
    }
}
