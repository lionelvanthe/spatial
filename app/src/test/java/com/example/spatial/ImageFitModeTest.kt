package com.example.spatial

import com.example.spatial.data.ImageFitMode
import com.example.spatial.data.SpatialSceneConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFitModeTest {
    @Test fun aliasesAndOldConfigurationUseSafeDefaults() {
        assertEquals(ImageFitMode.FILL, ImageFitMode.parse("CENTER_CROP"))
        assertEquals(ImageFitMode.FIT, ImageFitMode.parse("fit"))
        assertEquals(ImageFitMode.BLURRED_BACKGROUND, ImageFitMode.parse("blurred_background"))
        assertEquals(ImageFitMode.FILL, ImageFitMode.parse("unsupported"))
        assertEquals(ImageFitMode.FILL, SpatialSceneConfig().imageFitMode)
    }

    @Test fun sanitizationPreservesPresentationSettings() {
        val c = SpatialSceneConfig(imageFitMode = ImageFitMode.BLURRED_BACKGROUND, backgroundColor = 0xFF123456.toInt())
        assertEquals(c.imageFitMode, c.sanitized().imageFitMode)
        assertEquals(c.backgroundColor, c.sanitized().backgroundColor)
    }
}
