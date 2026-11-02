package com.example.spatial

import com.example.spatial.gl.DepthProjection
import com.example.spatial.sensor.RelativeTilt
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SpatialProjectionTest {
    @Test fun zoomOffsetScalesWithDepthAndZoom3D() {
        assertEquals(0f, DepthProjection.zoomOffset(0f, 0.3f, 1f), 0f)
        assertEquals(0.3f, DepthProjection.zoomOffset(1f, 0.3f, 1f), 1e-6f)
        assertEquals(0.15f, DepthProjection.zoomOffset(0.5f, 0.3f, 1f), 1e-6f)
        // 3D zoom off: nothing is pushed, whatever the depth
        assertEquals(0f, DepthProjection.zoomOffset(1f, 0.3f, 0f), 0f)
    }

    @Test fun zoomOffsetInputsAreClamped() {
        assertEquals(0.3f, DepthProjection.zoomOffset(5f, 0.3f, 9f), 1e-6f)
        assertEquals(0f, DepthProjection.zoomOffset(-2f, 0.3f, 1f), 0f)
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
