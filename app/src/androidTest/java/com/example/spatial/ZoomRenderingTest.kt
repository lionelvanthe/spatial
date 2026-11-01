package com.example.spatial

import android.graphics.Color
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.spatial.data.ImageFitMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ZoomRenderingTest {
    @Test fun zoomInAndOutAffectAllFitModesContinuously() {
        ParallaxGpuFixture(200, 200).use { gpu ->
            gpu.upload(0, gpu.bitmap { x, _ -> Color.rgb(x * 255 / 199, 80, 120) })
            gpu.uniform("uStrength", 0f)
            gpu.uniform("uFill", 1f)
            GLES20.glUniform4f(gpu.location("uBgColor"), 0f, 1f, 0f, 1f)
            for (mode in ImageFitMode.entries) {
                gpu.uniform("uScaleType", mode.shaderValue)
                var previousRed = 256
                for (zoom in floatArrayOf(.8f, 1f, 1.2f)) {
                    gpu.uniform("uZoom", zoom)
                    val pixels = gpu.render()
                    val x = 145
                    val red = Color.red(pixels[100 * 200 + x])
                    val expected = (((x + .5f) / 200 - .5f) / zoom + .5f) * 255
                    assertEquals("$mode zoom=$zoom", expected, red.toFloat(), 2f)
                    assertTrue("Zoom must have no dead region in $mode", red < previousRed)
                    previousRed = red
                    if (zoom < 1f) {
                        if (mode == ImageFitMode.FIT || mode == ImageFitMode.FILL) {
                            assertEquals("Explicit zoom-out reveals the background", Color.GREEN, pixels[0])
                        } else {
                            assertNotEquals(Color.GREEN, pixels[0])
                        }
                    }
                }
            }
        }
    }

    @Test fun legacyDepthZoomDoesNotChangeProjection() {
        ParallaxGpuFixture(200, 200).use { gpu ->
            assertEquals(-1, gpu.location("uZoom3D"))
            gpu.upload(0, gpu.bitmap { x, _ -> Color.rgb(x * 255 / 199, 80, 120) })
            gpu.upload(3, gpu.bitmap { _, _ -> Color.WHITE })
            gpu.uniform("uFill", 1f)
            GLES20.glUniform4f(gpu.location("uBgColor"), 0f, 1f, 0f, 1f)
            for (neutral in floatArrayOf(.2f, .95f)) for (depth in floatArrayOf(0f, 1f)) {
                gpu.uniform("uNeutral", neutral)
                val d = (depth * 255).roundToInt()
                gpu.upload(1, gpu.bitmap { _, _ -> Color.rgb(d, d, d) })
                for (zoom3D in floatArrayOf(-.3f, 0f, .3f)) {
                    gpu.uniform("uZoom3D", zoom3D)
                    val x = 145
                    val pixels = gpu.render()
                    val scale = 1f
                    val expected = (((x + .5f) / 200 - .5f) / scale + .5f) * 255
                    assertEquals("depth=$depth zoom3D=$zoom3D", expected,
                        Color.red(pixels[100 * 200 + x]).toFloat(), 2f)
                }
            }
        }
    }
}
