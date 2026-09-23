package com.fixmylife.layercut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Shows a frame and lets the user drag a crop rectangle. Values are normalized 0..1. */
class CropView(ctx: Context, private val bmp: Bitmap) : View(ctx) {

    var l = 0f
    var t = 0f
    var r = 1f
    var b = 1f

    /** Locked ratio (width / height in image pixels) or null for free. */
    private var ratio: Float? = null

    private val d = resources.displayMetrics.density
    private val img = RectF()
    private val dim = Paint().apply { color = Color.parseColor("#AA000000") }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66FFFFFF"); strokeWidth = 1 * d }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val minN = 0.05f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 16 * d
        val aw = w - 2 * pad
        val ah = h - 2 * pad
        val s = min(aw / bmp.width, ah / bmp.height)
        val iw = bmp.width * s
        val ih = bmp.height * s
        img.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
    }

    private fun cropRect() = RectF(
        img.left + l * img.width(), img.top + t * img.height(),
        img.left + r * img.width(), img.top + b * img.height()
    )

    fun setRatio(rt: Float?) {
        ratio = rt
        if (rt != null) {
            val iw = bmp.width.toFloat()
            val ih = bmp.height.toFloat()
            var nw = 1f
            var nh = nw * iw / (rt * ih)
            if (nh > 1f) { nh = 1f; nw = rt * ih / iw }
            l = (1 - nw) / 2; r = l + nw
            t = (1 - nh) / 2; b = t + nh
        }
        invalidate()
    }

    fun reset() {
        ratio = null
        l = 0f; t = 0f; r = 1f; b = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(bmp, null, img, null)
        val c = cropRect()
        canvas.drawRect(img.left, img.top, img.right, c.top, dim)
        canvas.drawRect(img.left, c.bottom, img.right, img.bottom, dim)
        canvas.drawRect(img.left, c.top, c.left, c.bottom, dim)
        canvas.drawRect(c.right, c.top, img.right, c.bottom, dim)
        for (i in 1..2) {
            val x = c.left + c.width() * i / 3
            val y = c.top + c.height() * i / 3
            canvas.drawLine(x, c.top, x, c.bottom, grid)
            canvas.drawLine(c.left, y, c.right, y, grid)
        }
        canvas.drawRect(c, border)
        for (p in listOf(c.left to c.top, c.right to c.top, c.left to c.bottom, c.right to c.bottom)) {
            canvas.drawCircle(p.first, p.second, 8 * d, handle)
        }
    }

    private var corner = -1
    private var moving = false
    private var downX = 0f
    private var downY = 0f
    private var oL = 0f
    private var oT = 0f
    private var oR = 0f
    private var oB = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y
                oL = l; oT = t; oR = r; oB = b
                val c = cropRect()
                val corners = listOf(c.left to c.top, c.right to c.top, c.left to c.bottom, c.right to c.bottom)
                corner = corners.indexOfFirst { hypot(e.x - it.first, e.y - it.second) < 32 * d }
                moving = corner < 0 && c.contains(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - downX) / img.width()
                val dy = (e.y - downY) / img.height()
                if (moving) {
                    val w = oR - oL
                    val h = oB - oT
                    l = (oL + dx).coerceIn(0f, 1f - w); r = l + w
                    t = (oT + dy).coerceIn(0f, 1f - h); b = t + h
                } else if (corner >= 0) {
                    dragCorner(dx, dy)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { corner = -1; moving = false }
        }
        return true
    }

    private fun dragCorner(dx: Float, dy: Float) {
        val left = corner == 0 || corner == 2
        val top = corner == 0 || corner == 1
        var nl = oL; var nt = oT; var nr = oR; var nb = oB
        if (left) nl = (oL + dx).coerceIn(0f, oR - minN) else nr = (oR + dx).coerceIn(oL + minN, 1f)
        if (top) nt = (oT + dy).coerceIn(0f, oB - minN) else nb = (oB + dy).coerceIn(oT + minN, 1f)
        val rt = ratio
        if (rt != null) {
            val iw = bmp.width.toFloat()
            val ih = bmp.height.toFloat()
            var nw = nr - nl
            var nh = nw * iw / (rt * ih)
            if (top) {
                if (nb - nh < 0f) { nh = nb; nw = nh * rt * ih / iw }
                nt = nb - nh
            } else {
                if (nt + nh > 1f) { nh = 1f - nt; nw = nh * rt * ih / iw }
                nb = nt + nh
            }
            if (left) nl = nr - nw else nr = nl + nw
        }
        l = nl; t = nt; r = nr; b = nb
    }
}
