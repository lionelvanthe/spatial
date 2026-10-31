package com.example.spatial

import com.example.spatial.gl.LoopPlan
import com.example.spatial.gl.MotionPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPlanTest {
    private fun pose(pattern: String, cycle: Float): Pair<Float, Float> {
        val out = FloatArray(3)
        MotionPattern.sample(pattern, cycle, 1f, out)
        return out[0] to out[1]
    }

    @Test
    fun everyPatternReturnsToItsStartAfterOneLoop() {
        val patterns = listOf(
            "horizontal", "vertical", "circular_cw", "circular_ccw", "eight_horizontal",
            "eight_vertical", "diagonal_top_bottom", "diagonal_bottom_top", "spiral_cw",
            "half_circle_bottom", "half_circle_top", "horizontal_rapid", "vertical_rapid"
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
