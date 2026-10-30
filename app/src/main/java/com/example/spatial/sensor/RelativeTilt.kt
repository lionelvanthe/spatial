package com.example.spatial.sensor

import kotlin.math.asin

/** Screen-normal rotation in the calibrated device frame, without Euler singularities. */
internal class RelativeTilt {
    private var reference: FloatArray? = null

    fun reset() { reference = null }

    fun update(matrix: FloatArray): Pair<Float, Float> {
        val initial = reference ?: matrix.copyOf().also { reference = it }
        val horizontal = initial[0] * matrix[2] + initial[3] * matrix[5] + initial[6] * matrix[8]
        val vertical = initial[1] * matrix[2] + initial[4] * matrix[5] + initial[7] * matrix[8]
        return (asin(horizontal.coerceIn(-1f, 1f)) / 0.55f).coerceIn(-1f, 1f) to
            (asin(vertical.coerceIn(-1f, 1f)) / 0.55f).coerceIn(-1f, 1f)
    }
}
