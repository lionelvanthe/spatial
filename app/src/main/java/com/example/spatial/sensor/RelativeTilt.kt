package com.example.spatial.sensor

import kotlin.math.asin

/** Screen-normal rotation in the calibrated device frame, without Euler singularities. */
internal class RelativeTilt {
    private var reference: FloatArray? = null

    fun reset() { reference = null }

    fun update(matrix: FloatArray): Pair<Float, Float> {
        val initial = reference ?: matrix.copyOf().also { reference = it }
        // Horizontal tilt combines yaw rotation (facing left/right) and wrist roll (tilting left/right edge)
        val hYaw = initial[0] * matrix[2] + initial[3] * matrix[5] + initial[6] * matrix[8]
        val hRoll = -(initial[1] * matrix[0] + initial[4] * matrix[3] + initial[7] * matrix[6])
        val horizontal = (hYaw + hRoll).coerceIn(-1f, 1f)

        // Vertical tilt measures pitch rotation (top edge away/closer)
        val vertical = (initial[1] * matrix[2] + initial[4] * matrix[5] + initial[7] * matrix[8]).coerceIn(-1f, 1f)

        return (asin(horizontal) / 0.55f).coerceIn(-1f, 1f) to
            (asin(vertical) / 0.55f).coerceIn(-1f, 1f)
    }
}
