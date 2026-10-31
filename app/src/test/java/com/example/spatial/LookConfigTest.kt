package com.example.spatial

import com.example.spatial.data.SpatialSceneConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class LookConfigTest {
    @Test fun defaultLookLeavesThePhotoUnchanged() {
        val c = SpatialSceneConfig().sanitized()
        assertEquals(0f, c.vignette, 0f)
        assertEquals(0f, c.blur, 0f)
        assertEquals(1f, c.saturation, 0f)
        assertEquals(1f, c.contrast, 0f)
        assertEquals(1f, c.brightness, 0f)
        assertEquals(0f, c.sepia, 0f)
        assertEquals(1f, c.isometric, 0f)
        assertEquals(0f, c.dolly, 0f)
    }

    @Test fun cameraValuesAreClamped() {
        val c = SpatialSceneConfig(isometric = -3f, dolly = 9f).sanitized()
        assertEquals(0f, c.isometric, 0f)
        assertEquals(1f, c.dolly, 0f)
    }

    @Test fun lookValuesInsideTheRangeAreUntouched() {
        val c = SpatialSceneConfig(vignette = 0.4f, blur = 0.3f, saturation = 1.5f, contrast = 0.8f, brightness = 1.2f, sepia = 0.6f)
        assertEquals(c, c.sanitized())
    }

    @Test fun lookValuesAreClamped() {
        val c = SpatialSceneConfig(vignette = 5f, saturation = -1f, brightness = Float.NaN).sanitized()
        assertEquals(1f, c.vignette, 0f)
        assertEquals(0f, c.saturation, 0f)
        assertEquals(1f, c.brightness, 0f)
    }
}
