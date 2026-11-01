package com.example.spatial

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatial.data.ImageFitMode
import com.example.spatial.data.SpatialSceneConfig
import com.example.spatial.data.AssetDataLoader
import com.example.spatial.gl.LayerBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ImageFitRenderingTest {
    @Test fun fillAtBaseZoomAndExtremePanNeverRevealUncoveredPixels() {
        ParallaxGpuFixture(120, 160).use { gpu ->
            val color = Color.rgb(120, 80, 160)
            gpu.upload(0, gpu.bitmap { _, _ -> color })
            gpu.upload(3, gpu.bitmap { _, _ -> Color.WHITE })
            gpu.uniform("uImageAspect", 2f)
            gpu.uniform("uNeutral", 128f / 255)
            gpu.uniform("uZoom", 1f)
            GLES20.glUniform4f(gpu.location("uBgColor"), 0f, 1f, 0f, 1f)
            for (direction in floatArrayOf(-1f, 1f)) {
                GLES20.glUniform2f(gpu.location("uPan"), direction * 2, direction * 2)
                gpu.offset(direction, direction)
                assertTrue(gpu.render().all { it == color })
            }
        }
    }

    @Test fun portraitLandscapeSquareAndMatchingImagesKeepRgbDepthAlignedAcrossModes() {
        for ((vw, vh) in listOf(120 to 160, 160 to 120, 128 to 128)) {
            ParallaxGpuFixture(vw, vh).use { gpu ->
                gpu.uniform("uFill", 1f)
                gpu.uniform("uZoom", 1f)
                gpu.uniform("uDepthHeight", .04f)
                GLES20.glUniform4f(gpu.location("uBgColor"), 0f, 1f, 0f, 1f)
                for ((iw, ih) in listOf(80 to 160, 160 to 80, 96 to 96, vw to vh)) {
                    fun image(pixel: (Int, Int) -> Int) = Bitmap.createBitmap(IntArray(iw * ih) { pixel(it % iw, it / iw) }, iw, ih, Bitmap.Config.ARGB_8888)
                    fun red(x: Int) = (40 + 180 * (x + .5f) / iw).roundToInt()
                    gpu.upload(0, image { x, y -> Color.rgb(red(x), (30 + 170 * (y + .5f) / ih).roundToInt(), if ((x / 4 + y / 4) % 2 == 0) 50 else 230) })
                    gpu.upload(1, image { x, _ -> val d = red(x); Color.rgb(d, d, d) })
                    gpu.upload(3, image { _, _ -> Color.WHITE })
                    gpu.uniform("uImageAspect", iw.toFloat() / ih)
                    GLES20.glUniform2f(gpu.location("uDepthTexel"), 1f / iw, 1f / ih)
                    for (mode in ImageFitMode.entries) {
                        gpu.uniform("uScaleType", mode.shaderValue)
                        for (strength in floatArrayOf(0f, 1f)) for (direction in floatArrayOf(-1f, 1f)) {
                            gpu.uniform("uStrength", strength)
                            gpu.offset(direction, 0f)
                            val pixels = gpu.render(if (vw == 120 && strength == 0f && direction == 1f) "fitting_${iw}x${ih}_${mode.name}" else null)
                            val ratio = (vw.toFloat() / vh) / (iw.toFloat() / ih)
                            val crop = mode == ImageFitMode.FILL
                            val spanX = if (crop) minOf(ratio, 1f) else maxOf(ratio, 1f)
                            val spanY = if (crop) minOf(1 / ratio, 1f) else maxOf(1 / ratio, 1f)
                            if (mode != ImageFitMode.FIT) assertFalse("Fill/backdrop must cover every pixel", pixels.any { it == Color.GREEN })
                            for (y in listOf(vh / 4, vh / 2, vh * 3 / 4)) for (x in listOf(vw / 4, vw / 2, vw * 3 / 4)) {
                                val u = ((x + .5f) / vw - .5f) * spanX + .5f
                                val v = ((y + .5f) / vh - .5f) * spanY + .5f
                                if (u !in .08f.. .92f || v !in .08f.. .92f) continue
                                val p = direction * .04f * strength
                                val travel = abs(p) * .5f
                                val limit = if (travel == 0f) 100f else minOf(u, 1 - u) / travel
                                val bounded = p * (1 - exp(-limit * limit))
                                // RGB.r encodes exactly the sampled depth. Solve the inverse
                                // continuous surface analytically, independently of shader iteration.
                                val expectedRed = (40 + 180 * (u + bounded * .5f)) / (1 + 180 * bounded / 255)
                                val pixel = pixels[y * vw + x]
                                assertTrue("RGB/depth alignment $mode ${iw}x$ih strength=$strength", abs(Color.red(pixel) - expectedRed) <= 3)
                                assertTrue("Aspect-preserving Y coordinate", abs(Color.green(pixel) - (30 + 170 * v)) <= 2)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun blurredBackdropReducesDetailAndLeavesTheCompleteFitPhotoUntouched() {
        ParallaxGpuFixture(160, 160).use { gpu ->
            val width = 320; val height = 80
            val pixels = IntArray(width * height) { i -> if ((i % width / 4 + i / width / 4) % 2 == 0) Color.WHITE else Color.BLACK }
            gpu.upload(0, Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
            gpu.uniform("uImageAspect", 4f)
            gpu.uniform("uStrength", 0f)
            gpu.uniform("uScaleType", 0f)
            val fit = gpu.render("fitting_checker_fit")
            gpu.uniform("uScaleType", 1f)
            val crop = gpu.render("fitting_checker_crop")
            gpu.uniform("uScaleType", 2f)
            val blurred = gpu.render("fitting_checker_blurred")
            for (y in 60 until 100) for (x in 0 until 160) assertEquals(fit[y * 160 + x], blurred[y * 160 + x])
            fun variance(image: IntArray): Double {
                val values = (0 until 40 * 160).map { Color.red(image[it]).toDouble() }
                val mean = values.average()
                return values.sumOf { (it - mean) * (it - mean) } / values.size
            }
            assertTrue("Backdrop blur must measurably suppress high frequency detail", variance(blurred) < variance(crop) * .65)
        }
    }

    @Test fun oldModesBecomeFitAndBackgroundColorSurvivesPersistence() {
        for (mode in ImageFitMode.entries) {
            val config = SpatialSceneConfig(imageFitMode = mode, backgroundColor = Color.rgb(12, 80, 135))
            assertEquals(config.copy(imageFitMode = ImageFitMode.FIT), SpatialSceneConfig.fromJson(config.toJson()))
            assertEquals(ImageFitMode.FIT, config.sanitized().imageFitMode)
            assertEquals(ImageFitMode.FIT, SpatialSceneConfig.fromJson("{\"imageFitMode\":\"${mode.name}\"}").imageFitMode)
        }
        assertEquals(ImageFitMode.FIT, ImageFitMode.parse("CENTER_CROP"))
        assertEquals(ImageFitMode.FIT, ImageFitMode.parse("unknown"))
        assertEquals(ImageFitMode.FIT, SpatialSceneConfig.fromJson("{}").imageFitMode)
        ParallaxGpuFixture().use { gpu ->
            val color = Color.rgb(12, 80, 135)
            com.example.spatial.gl.ParallaxShaders.applyEffects(gpu.program,
                SpatialSceneConfig(imageFitMode = ImageFitMode.FIT, backgroundColor = color))
            gpu.uniform("uStrength", 0f)
            gpu.uniform("uImageAspect", 2f)
            assertEquals("Configured FIT background must reach the actual shader", color, gpu.render()[0])
        }
    }

    @Test fun captureAstronautAllPresentationModes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val scene = AssetDataLoader.loadPresetSceneById(app, "astronaut")!!
        val image = scene.fullImageBitmap!!
        val source = IntArray(image.width * image.height)
        image.getPixels(source, 0, image.width, 0, 0, image.width, image.height)
        val layers = LayerBuilder.buildLayers(source, image.width, image.height, scene.depthData!!, 504, 504)!!
        ParallaxGpuFixture(403, 594).use { gpu ->
            gpu.upload(0, image.copy(Bitmap.Config.ARGB_8888, false))
            gpu.upload(1, Bitmap.createBitmap(layers.depthTexture, 504, 504, Bitmap.Config.ARGB_8888))
            gpu.upload(2, Bitmap.createBitmap(layers.layers[0], image.width, image.height, Bitmap.Config.ARGB_8888))
            gpu.upload(3, Bitmap.createBitmap(layers.layers[1], image.width, image.height, Bitmap.Config.ARGB_8888))
            gpu.uniform("uLayered", 1f)
            gpu.uniform("uImageAspect", image.width.toFloat() / image.height)
            GLES20.glUniform2f(gpu.location("uDepthTexel"), 1f / 504, 1f / 504)
            GLES20.glUniform4f(gpu.location("uBgColor"), 15f / 255, 15f / 255, 23f / 255, 1f)
            for (mode in ImageFitMode.entries) for (strength in floatArrayOf(0f, 1f)) for (direction in floatArrayOf(-1f, 1f)) {
                gpu.uniform("uScaleType", mode.shaderValue)
                gpu.uniform("uFill", if (mode == ImageFitMode.FILL) 1f else .88f)
                gpu.uniform("uZoom", 1.21f)
                gpu.uniform("uStrength", strength)
                gpu.offset(direction, 0f)
                gpu.render("fitting_astronaut_${mode.name}_${strength}_${direction}")
            }
        }
    }
}
