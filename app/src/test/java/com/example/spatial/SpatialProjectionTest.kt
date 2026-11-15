package com.example.spatial

import com.example.spatial.gl.MotionPattern
import com.example.spatial.sensor.RelativeTilt
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SpatialProjectionTest {
    @Test fun nativePatternMappingCoversAll16Patterns() {
        val expected = mapOf(
            "none" to 0,
            "horizontal" to 1,
            "vertical" to 2,
            "diagonal_bottom_top" to 3,
            "diagonal_top_bottom" to 4,
            "half_circle_bottom" to 5,
            "half_circle_top" to 6,
            "circular_cw" to 7,
            "circular_ccw" to 8,
            "triangular_cw" to 9,
            "square_cw" to 10,
            "square_ccw" to 11,
            "eight_horizontal" to 12,
            "eight_vertical" to 13,
            "spiral_cw" to 14,
            "horizontal_rapid" to 15,
            "vertical_rapid" to 16
        )
        for ((id, nativeId) in expected) {
            assertEquals("Pattern $id", nativeId, MotionPattern.patternIdToNativeInt(id))
        }
    }

    @Test fun relativeTiltWorksWhenPhoneIsUpright() {
        val tracker = RelativeTilt()
        // Reference rotation: 90 degrees around X, the Euler pitch singularity.
        val upright = floatArrayOf(1f,0f,0f, 0f,0f,-1f, 0f,1f,0f)
        assertEquals(0f, tracker.update(upright).first, 0.00001f)
        val a = 0.2f
        val tilted = floatArrayOf(cos(a),0f,sin(a), sin(a),0f,-cos(a), 0f,1f,0f)
        assertEquals(a / 0.55f, tracker.update(tilted).first, 0.00001f)
        tracker.reset()
        assertEquals(0f, tracker.update(tilted).first, 0.00001f)
    }

    @Test fun relativeTiltWorksWhenPhoneIsRolled() {
        val tracker = RelativeTilt()
        val upright = floatArrayOf(1f,0f,0f, 0f,0f,-1f, 0f,1f,0f)
        assertEquals(0f, tracker.update(upright).first, 0.00001f)
        val a = 0.2f
        // Roll rotation around device Z axis (screen normal)
        val rolled = floatArrayOf(cos(a), sin(a), 0f, 0f, 0f, -1f, -sin(a), cos(a), 0f)
        assertEquals(a / 0.55f, tracker.update(rolled).first, 0.00001f)
        tracker.reset()
        assertEquals(0f, tracker.update(rolled).first, 0.00001f)
    }

    @Test fun relativeTiltWorksWhenPhoneIsPitched() {
        val tracker = RelativeTilt()
        val upright = floatArrayOf(1f,0f,0f, 0f,0f,-1f, 0f,1f,0f)
        assertEquals(0f, tracker.update(upright).second, 0.00001f)
        val b = 0.15f
        // Pitch rotation around device X axis
        val pitched = floatArrayOf(1f, 0f, 0f, 0f, sin(b), -cos(b), 0f, cos(b), sin(b))
        assertEquals(b / 0.55f, tracker.update(pitched).second, 0.00001f)
        tracker.reset()
        assertEquals(0f, tracker.update(pitched).second, 0.00001f)
    }
}
