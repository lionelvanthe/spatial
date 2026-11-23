package com.example.spatial.gl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
            "square_cw", "square" -> {
                val t = ((cycle / TWO_PI) % 1.0f + 1.0f) % 1.0f
                when {
                    t < 0.25f -> {
                        x = -1.0f + 8.0f * t
                        y = 1.0f
                    }
                    t < 0.50f -> {
                        x = 1.0f
                        y = 1.0f - 8.0f * (t - 0.25f)
                    }
                    t < 0.75f -> {
                        x = 1.0f - 8.0f * (t - 0.50f)
                        y = -1.0f
                    }
                    else -> {
                        x = -1.0f
                        y = -1.0f + 8.0f * (t - 0.75f)
                    }
                }
                x *= intensity
                y *= intensity
            }
            "square_ccw" -> {
                val t = ((cycle / TWO_PI) % 1.0f + 1.0f) % 1.0f
                when {
                    t < 0.25f -> {
                        x = -1.0f + 8.0f * t
                        y = 1.0f
                    }
                    t < 0.50f -> {
                        x = 1.0f
                        y = 1.0f - 8.0f * (t - 0.25f)
                    }
                    t < 0.75f -> {
                        x = 1.0f - 8.0f * (t - 0.50f)
                        y = -1.0f
                    }
                    else -> {
                        x = -1.0f
                        y = -1.0f + 8.0f * (t - 0.75f)
                    }
                }
                x = -x * intensity
                y *= intensity
            }
            "triangular_cw", "triangle", "triangular" -> {
                val t = ((cycle / TWO_PI) % 1.0f + 1.0f) % 1.0f
                when {
                    t < 1.0f / 3.0f -> {
                        val u = (1.0f - cos(t * 3.0f * PI_F)) * 0.5f
                        x = u * SQRT3_OVER_2
                        y = 1.0f - 1.5f * u
                    }
                    t < 2.0f / 3.0f -> {
                        val u = (1.0f - cos((t - 1.0f / 3.0f) * 3.0f * PI_F)) * 0.5f
                        x = SQRT3_OVER_2 - u * SQRT3
                        y = -0.5f
                    }
                    else -> {
                        val u = (1.0f - cos((t - 2.0f / 3.0f) * 3.0f * PI_F)) * 0.5f
                        x = -SQRT3_OVER_2 + u * SQRT3_OVER_2
                        y = -0.5f + 1.5f * u
                    }
                }
                x *= intensity
                y *= intensity
            }
            "zoom" -> z = sin(cycle) * 0.25f * intensity
        }
        out[0] = x
        out[1] = y
        out[2] = z
    }

    /** Number of 2π cycles after which [patternId] returns to its starting pose. */
    fun periodCycles(patternId: String): Int =
        if (patternId.equals("spiral_cw", ignoreCase = true)) 2 else 1

    /** Maps patternId string to native C++ PatternAnimations::PatternId enum integer. */
    fun patternIdToNativeInt(patternId: String): Int = when (patternId.lowercase()) {
        "horizontal" -> 1
        "vertical" -> 2
        "diagonal_bottom_top", "diagonal_bt" -> 3
        "diagonal_top_bottom", "diagonal_tb" -> 4
        "half_circle_bottom", "half_circle_b" -> 5
        "half_circle_top", "half_circle_t" -> 6
        "circular_cw", "circle_cw", "circle" -> 7
        "circular_ccw", "circle_ccw" -> 8
        "triangular_cw", "triangle", "triangular" -> 9
        "square_cw", "square" -> 10
        "square_ccw" -> 11
        "eight_horizontal" -> 12
        "eight_vertical" -> 13
        "spiral_cw" -> 14
        "horizontal_rapid" -> 15
        "vertical_rapid" -> 16
        "zoom" -> 17
        else -> 0
    }

    private const val SPIRAL_PERIOD = (4.0 * PI).toFloat()
    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val PI_F = PI.toFloat()
    private val SQRT3 = sqrt(3.0f)
    private val SQRT3_OVER_2 = SQRT3 / 2.0f
}
