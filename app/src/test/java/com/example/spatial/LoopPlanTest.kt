package com.example.spatial

import com.example.spatial.gl.LoopPlan
import com.example.spatial.gl.MotionPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class LoopPlanTest {
    private fun pose(pattern: String, cycle: Float, intensity: Float = 1f): Pair<Float, Float> {
        val out = FloatArray(3)
        MotionPattern.sample(pattern, cycle, intensity, out)
        return out[0] to out[1]
    }

    @Test
    fun everyPatternReturnsToItsStartAfterOneLoop() {
        val patterns = listOf(
            "horizontal", "vertical", "circular_cw", "circular_ccw", "eight_horizontal",
            "eight_vertical", "diagonal_top_bottom", "diagonal_bottom_top", "spiral_cw",
            "half_circle_bottom", "half_circle_top", "horizontal_rapid", "vertical_rapid",
            "square_cw", "square_ccw", "triangular_cw"
        )
        for (p in patterns) {
            val fpl = LoopPlan.framesPerLoop(0.35f, p)
            val first = pose(p, LoopPlan.phase(0, fpl, p))
            // The frame after the last one is frame 0 of the next loop.
            val wrapped = pose(p, LoopPlan.phase(fpl, fpl, p))
            assertEquals(p, first.first, wrapped.first, 1e-4f)
            assertEquals(p, first.second, wrapped.second, 1e-4f)
        }
    }

    @Test
    fun squareCwFollowsClockwisePerimeter() {
        val twoPi = (2.0 * PI).toFloat()
        // Top-left
        val p0 = pose("square_cw", 0f)
        assertEquals(-1.0f, p0.first, 1e-4f)
        assertEquals(1.0f, p0.second, 1e-4f)

        // Top-right
        val p1 = pose("square_cw", twoPi * 0.25f)
        assertEquals(1.0f, p1.first, 1e-4f)
        assertEquals(1.0f, p1.second, 1e-4f)

        // Bottom-right
        val p2 = pose("square_cw", twoPi * 0.50f)
        assertEquals(1.0f, p2.first, 1e-4f)
        assertEquals(-1.0f, p2.second, 1e-4f)

        // Bottom-left
        val p3 = pose("square_cw", twoPi * 0.75f)
        assertEquals(-1.0f, p3.first, 1e-4f)
        assertEquals(-1.0f, p3.second, 1e-4f)

        // Back to top-left
        val p4 = pose("square_cw", twoPi)
        assertEquals(-1.0f, p4.first, 1e-4f)
        assertEquals(1.0f, p4.second, 1e-4f)

        // Scaled by intensity
        val scaled = pose("square_cw", 0f, 0.5f)
        assertEquals(-0.5f, scaled.first, 1e-4f)
        assertEquals(0.5f, scaled.second, 1e-4f)
    }

    @Test
    fun triangularCwFollowsEquilateralTriangleVertices() {
        val twoPi = (2.0 * PI).toFloat()
        val sqrt3Over2 = sqrt(3.0f) / 2.0f

        // Top vertex (0, 1)
        val p0 = pose("triangular_cw", 0f)
        assertEquals(0.0f, p0.first, 1e-4f)
        assertEquals(1.0f, p0.second, 1e-4f)

        // Bottom-right vertex (sqrt(3)/2, -0.5)
        val p1 = pose("triangular_cw", twoPi / 3.0f)
        assertEquals(sqrt3Over2, p1.first, 1e-4f)
        assertEquals(-0.5f, p1.second, 1e-4f)

        // Bottom-left vertex (-sqrt(3)/2, -0.5)
        val p2 = pose("triangular_cw", twoPi * 2.0f / 3.0f)
        assertEquals(-sqrt3Over2, p2.first, 1e-4f)
        assertEquals(-0.5f, p2.second, 1e-4f)

        // Back to top vertex
        val p3 = pose("triangular_cw", twoPi)
        assertEquals(0.0f, p3.first, 1e-4f)
        assertEquals(1.0f, p3.second, 1e-4f)

        // Scaled by intensity
        val scaled = pose("triangular_cw", 0f, 0.5f)
        assertEquals(0.0f, scaled.first, 1e-4f)
        assertEquals(0.5f, scaled.second, 1e-4f)
    }

    @Test
    fun squareCcwInvertsX() {
        val twoPi = (2.0 * PI).toFloat()
        for (i in 0..10) {
            val c = twoPi * (i / 10f)
            val cw = pose("square_cw", c)
            val ccw = pose("square_ccw", c)
            assertEquals("x at $c", -cw.first, ccw.first, 1e-4f)
            assertEquals("y at $c", cw.second, ccw.second, 1e-4f)
        }
    }

    @Test
    fun allMotionPatternsExcludesNone() {
        val noneItem = com.example.spatial.ui.ALL_MOTION_PATTERNS.firstOrNull { it.id.equals("none", ignoreCase = true) }
        assertNull("Motion 'none' should not be in ALL_MOTION_PATTERNS", noneItem)
    }

    @Test
    fun spiralNeedsTwoCyclesPerLoop() {
        assertEquals(2 * LoopPlan.framesPerLoop(0.5f, "circular_cw"), LoopPlan.framesPerLoop(0.5f, "spiral_cw"))
    }

    @Test
    fun clipLastsAtLeastThreeSeconds() {
        for (speed in listOf(0f, 0.35f, 1f)) {
            val fpl = LoopPlan.framesPerLoop(speed, "horizontal")
            assertTrue(fpl * LoopPlan.loopCount(fpl) >= 3 * LoopPlan.FPS)
        }
    }

    @Test
    fun videoSizeIsEncoderFriendly() {
        val (w, h) = LoopPlan.videoSize(1280, 853, 1080)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue(maxOf(w, h) <= 1088)
    }
}
