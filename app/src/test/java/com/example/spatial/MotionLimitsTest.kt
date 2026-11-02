package com.example.spatial

import com.example.spatial.data.MotionLimits
import com.example.spatial.data.SpatialSceneConfig
import com.example.spatial.gl.DepthProjection
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

    @Test fun wholeIntensityRangeChangesParallax() {
        // Native depthScale keeps 30% of the motion at zero and peaks at the validated maximum.
        assertEquals(DepthProjection.MAX_DEPTH_HEIGHT * .3f / DepthProjection.depthScale(1.25f),
            DepthProjection.depthHeight(0f), 1e-6f)
        assertEquals(DepthProjection.MAX_DEPTH_HEIGHT, DepthProjection.depthHeight(MotionLimits.MAX_INTENSITY), 1e-6f)
        var previous = -1f
        for (step in 0..10) {
            val h = DepthProjection.depthHeight(MotionLimits.MAX_INTENSITY * step / 10f)
            assertTrue("step $step", h > previous)
            previous = h
        }
        assertEquals(DepthProjection.MAX_DEPTH_HEIGHT, DepthProjection.depthHeight(9f), 1e-6f)
    }

    @Test fun bundledSceneIntensitiesAreKeptAndRenderDifferently() {
        // Values from astronaut_desc.dat and unicorn_desc.dat; fromJson ends with sanitized().
        val low = SpatialSceneConfig(intensity = 0.4167618155479431f).sanitized()
        val high = SpatialSceneConfig(intensity = 0.9775443077087402f).sanitized()
        assertEquals(0.41676f, low.intensity, 1e-5f)
        assertEquals(0.97754f, high.intensity, 1e-5f)
        assertTrue(DepthProjection.depthHeight(low.intensity) < DepthProjection.depthHeight(high.intensity))
        assertTrue(DepthProjection.depthHeight(high.intensity) < DepthProjection.MAX_DEPTH_HEIGHT)
    }

    @Test fun defaultIsLow() {
        val d = SpatialSceneConfig()
        // The default sits below half of the maximum parallax.
        assertTrue(DepthProjection.depthHeight(d.intensity) <= .5f * DepthProjection.MAX_DEPTH_HEIGHT)
        assertEquals(d, d.sanitized())
    }

    @Test fun elasticityValuesAreClamped() {
        val c = SpatialSceneConfig(elasticity = -2f).sanitized()
        assertEquals(MotionLimits.MIN_ELASTICITY, c.elasticity, 0f)
        val c2 = SpatialSceneConfig(elasticity = 5f).sanitized()
        assertEquals(MotionLimits.MAX_ELASTICITY, c2.elasticity, 0f)
        val c3 = SpatialSceneConfig(elasticity = Float.NaN).sanitized()
        assertEquals(MotionLimits.DEFAULT_ELASTICITY, c3.elasticity, 0f)
    }

    @Test fun elasticityControlsSpringKAndDampC() {
        assertEquals(65.0f, MotionLimits.springK(0.5f), 1e-5f)
        assertEquals(2.8f, MotionLimits.dampC(0.5f), 1e-5f)
        assertEquals(30.0f, MotionLimits.springK(0.0f), 1e-5f)
        assertEquals(9.0f, MotionLimits.dampC(0.0f), 1e-5f)
        assertEquals(100.0f, MotionLimits.springK(1.0f), 1e-5f)
        assertEquals(0.8f, MotionLimits.dampC(1.0f), 1e-5f)

        // springK monotonically increases with elasticity
        assertTrue(MotionLimits.springK(0.2f) < MotionLimits.springK(0.8f))
        // dampC monotonically decreases with elasticity (less damping = bouncier)
        assertTrue(MotionLimits.dampC(0.2f) > MotionLimits.dampC(0.8f))
    }
}
