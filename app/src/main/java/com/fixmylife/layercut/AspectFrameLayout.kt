package com.fixmylife.layercut

import android.content.Context
import android.widget.FrameLayout

/** Sizes itself to the largest box with the given aspect ratio that fits its parent. */
class AspectFrameLayout(ctx: Context) : FrameLayout(ctx) {
    var aspect: Float = 9f / 16f
        set(v) {
            if (field != v) { field = v; requestLayout() }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = (w / aspect).toInt()
        if (h > maxH) { h = maxH; w = (h * aspect).toInt() }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}
