package com.example.spatial.gl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Pre-programmed camera paths of the Animations tab, shared by live rendering and video export. */
object MotionPattern {
    /**
     * Writes the tilt for [patternId] at [cycle] radians into [out]: `out[0]` = x, `out[1]` = y,
     * `out[2]` = z offset, which is NaN unless the pattern drives zoom.
     */
    fun sample(patternId: String, cycle: Float, intensity: Float, out: FloatArray) {
        var x = 0.0f
        var y = 0.0f
        var z = Float.NaN
        when (patternId.lowercase()) {
            "horizontal" -> x = sin(cycle) * intensity
            "vertical" -> y = sin(cycle) * intensity
            "circular_cw", "circle_cw", "circle" -> {
                x = cos(cycle) * intensity
                y = sin(cycle) * intensity
            }
            "circular_ccw", "circle_ccw" -> {
                x = -cos(cycle) * intensity
                y = sin(cycle) * intensity
            }
            "eight_horizontal" -> {
                x = sin(cycle) * intensity
                y = sin(cycle * 2.0f) * 0.45f * intensity
            }
            "eight_vertical" -> {
                x = sin(cycle * 2.0f) * 0.45f * intensity
                y = sin(cycle) * intensity
            }
            "diagonal_top_bottom" -> {
                val d = sin(cycle) * intensity * 0.75f
                x = d
                y = d
            }
            "diagonal_bottom_top" -> {
                val d = sin(cycle) * intensity * 0.75f
                x = d
                y = -d
            }
            "spiral_cw" -> {
                val radius = (0.35f + 0.65f * ((cycle % SPIRAL_PERIOD) / SPIRAL_PERIOD)) * intensity
                x = cos(cycle) * radius
                y = sin(cycle) * radius
            }
            "half_circle_bottom" -> {
                x = cos(cycle) * intensity
                y = abs(sin(cycle)) * intensity
            }
            "half_circle_top" -> {
                x = cos(cycle) * intensity
                y = -abs(sin(cycle)) * intensity
            }
            "horizontal_rapid" -> x = sin(cycle * 2.2f) * intensity
            "vertical_rapid" -> y = sin(cycle * 2.2f) * intensity
            "zoom" -> z = sin(cycle) * 0.25f * intensity
        }
        out[0] = x
        out[1] = y
        out[2] = z
    }

    /** Number of 2π cycles after which [patternId] returns to its starting pose. */
    fun periodCycles(patternId: String): Int =
        if (patternId.equals("spiral_cw", ignoreCase = true)) 2 else 1

    private const val SPIRAL_PERIOD = (4.0 * PI).toFloat()
}
