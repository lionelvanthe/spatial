package com.example.spatial

import com.example.spatial.data.MotionLimits
import com.example.spatial.data.SpatialSceneConfig
import org.junit.Assert.*
import org.junit.Test

class MotionLimitsTest {
    @Test fun strongBundledMetadataIsClamped() {
        // Same shape as the astronaut preset: zoom and zoom3D at their maximum.
        val c = SpatialSceneConfig(intensity = 9f, depthCenter = 4f, speed = 7f, zoom = 1f, zoom3D = 1f).sanitized()
        assertEquals(MotionLimits.MAX_INTENSITY, c.intensity, 0f)
        assertEquals(1f, c.depthCenter, 0f)
        assertEquals(1f, c.speed, 0f)
        assertEquals(MotionLimits.MAX_ZOOM, c.zoom, 0f)
        assertEquals(MotionLimits.MAX_ZOOM_3D, c.zoom3D, 0f)
    }

    @Test fun sliderAndRestoredValuesAreClamped() {
        val c = SpatialSceneConfig().copy(intensity = 1.25f, zoom = -5f, zoom3D = 1f).sanitized()
        assertEquals(MotionLimits.MAX_INTENSITY, c.intensity, 0f)
        assertEquals(MotionLimits.MIN_ZOOM, c.zoom, 0f)
        assertEquals(MotionLimits.MAX_ZOOM_3D, c.zoom3D, 0f)
    }

    @Test fun nonFiniteValuesFallBackToSafeDefaults() {
        val c = SpatialSceneConfig(intensity = Float.NaN, speed = Float.POSITIVE_INFINITY, zoom = Float.NaN).sanitized()
        assertEquals(MotionLimits.DEFAULT_INTENSITY, c.intensity, 0f)
        assertEquals(0.35f, c.speed, 0f)
        assertEquals(0f, c.zoom, 0f)
    }

    @Test fun valuesInsideTheRangeAreUntouched() {
        val c = SpatialSceneConfig(intensity = 0.1f, depthCenter = 0.6f, speed = 0.4f, zoom = -0.2f, zoom3D = 0.2f)
        assertEquals(c, c.sanitized())
    }

    @Test fun defaultIsLow() {
        val d = SpatialSceneConfig()
        assertTrue(d.intensity <= 0.5f)
        assertEquals(d, d.sanitized())
    }
}
