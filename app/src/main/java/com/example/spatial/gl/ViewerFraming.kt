package com.example.spatial.gl

import com.example.spatial.data.MotionLimits

/** Scene framing is shared by the three motion modes; ZoomPan uses touch framing. */
internal object ViewerFraming {
    fun preserveTouchZoom(fromTab: Int, toTab: Int): Boolean =
        fromTab in 0..2 && toTab in 0..2

    fun effectiveZoom(zoom: Float, tabMode: Int): Float {
        if (tabMode == 3) return 0f
        val clamped = zoom.coerceIn(MotionLimits.MIN_ZOOM, MotionLimits.MAX_ZOOM)
        return if (clamped < 0f) clamped * 0.5f else clamped
    }

    fun zoomScale(zoom: Float, tabMode: Int, touchZoom: Float = 0f, animationZoom: Float = 0f): Float =
        (1f + effectiveZoom(zoom, tabMode) * 0.6f + touchZoom + animationZoom).coerceIn(0.5f, 2.5f)

    fun depthCenter(center: Float): Float = center.coerceIn(0f, 1f)
}
