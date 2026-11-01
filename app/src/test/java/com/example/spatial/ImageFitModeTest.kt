package com.example.spatial

import com.example.spatial.data.ImageFitMode
import com.example.spatial.data.SpatialSceneConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFitModeTest {
    @Test fun aliasesAndOldConfigurationUseSafeDefaults() {
        assertEquals(ImageFitMode.FIT, ImageFitMode.parse("CENTER_CROP"))
        assertEquals(ImageFitMode.FIT, ImageFitMode.parse("fit"))
        assertEquals(ImageFitMode.FIT, ImageFitMode.parse("blurred_background"))
        assertEquals(ImageFitMode.FIT, ImageFitMode.parse("FILL"))
        assertEquals(ImageFitMode.FIT, ImageFitMode.parse("unsupported"))
        assertEquals(ImageFitMode.FIT, SpatialSceneConfig().imageFitMode)
    }

    @Test fun sanitizationForcesFitAndPreservesBackgroundColor() {
        val c = SpatialSceneConfig(imageFitMode = ImageFitMode.BLURRED_BACKGROUND, backgroundColor = 0xFF123456.toInt())
        assertEquals(ImageFitMode.FIT, c.sanitized().imageFitMode)
        assertEquals(c.backgroundColor, c.sanitized().backgroundColor)
    }
}
