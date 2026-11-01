package com.example.spatial.gl

/** Scene framing is shared by the three motion modes; ZoomPan uses touch framing. */
internal object ViewerFraming {
    fun preserveTouchZoom(fromTab: Int, toTab: Int): Boolean =
        fromTab in 0..2 && toTab in 0..2

    fun effectiveZoom(zoom: Float, tabMode: Int): Float {
        if (tabMode == 3) return 0f
        val clamped = zoom.coerceIn(-1f, 1f)
        return if (clamped < 0f) clamped * 0.5f else clamped
    }

    fun zoom3D(zoom: Float, zoom3D: Float, tabMode: Int): Float =
        if (tabMode != 3 && kotlin.math.abs(zoom) >= 0.1f) zoom3D.coerceIn(0f, 1f) else 0f

    fun depthCenter(center: Float): Float = center.coerceIn(0f, 1f)
}
