package com.example.spatial

import android.opengl.GLES20
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatial.data.AssetDataLoader
import com.example.spatial.gl.LayerBuilder
import android.graphics.Bitmap
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParallaxRenderingTest {
    @Test fun zeroStrengthReturnsOriginalRgbEvenWithEffectsAndInvalidLayers() {
        ParallaxGpuFixture().use { gpu ->
            val expected = gpu.original("original_rgb")
            gpu.offset(1f, -1f)
            gpu.uniform("uZoom3D", 1f)
            gpu.uniform("uBlur", 1f)
            gpu.uniform("uVignette", 1f)
            GLES20.glUniform4f(gpu.location("uColor"), 0f, 0.5f, 0.2f, 1f)
            for (strength in floatArrayOf(0f, 0.000001f)) {
                gpu.uniform("uStrength", strength)
                assertArrayEquals("Zero strength must preserve every RGBA pixel", expected,
                    gpu.render("zero_${strength}"))
            }
        }
    }

    @Test fun strongDepthBoundaryKeepsSubjectWidthAndMovesContinuously() {
        ParallaxGpuFixture().use { gpu ->
            val background = Color.rgb(20, 60, 120)
            gpu.upload(0, gpu.bitmap { x, _ -> if (x in 96..159) Color.rgb(240, 10, 10) else background })
            gpu.upload(1, gpu.bitmap { x, _ -> val d = if (x in 96..159) 230 else 25; Color.rgb(d, 25, 230) })
            gpu.upload(2, gpu.bitmap { _, _ -> background })
            gpu.upload(3, gpu.bitmap { x, _ -> if (x in 96..159) Color.WHITE else Color.TRANSPARENT })
            GLES20.glUniform2f(gpu.location("uDepthTexel"), 1f / 256, 1f / 256)
            gpu.uniform("uDepthHeight", .12f)
            val errors = mutableListOf<String>()
            for (direction in floatArrayOf(-1f, 1f)) {
                var previousCenter = 127.5f
                for (strength in floatArrayOf(.25f, .5f, 1f)) {
                    gpu.offset(direction, 0f)
                    gpu.uniform("uStrength", strength)
                    val pixels = gpu.render("boundary_${direction}_${strength}")
                    val red = (0 until 256).filter { x ->
                        val c = pixels[128 * 256 + x]
                        Color.red(c) > 180 && Color.green(c) < 60
                    }
                    val expectedCenter = 127.5f + direction * .12f * strength * (230f / 255 - .5f) * 256
                    val center = if (red.isEmpty()) 0f else (red.first() + red.last()) * .5f
                    if (red.size !in 61..67) errors.add("direction=$direction strength=$strength width=${red.size}")
                    if (kotlin.math.abs(center - expectedCenter) > 2f) errors.add("center=$center expected=$expectedCenter")
                    if ((center - previousCenter) * direction <= 0) errors.add("non-monotonic motion")
                    previousCenter = center
                }
            }
            assertTrue(errors.joinToString(), errors.isEmpty())
        }
    }

    @Test fun captureAstronautOriginalFlatAndDepthAtBothTiltDirections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scene = AssetDataLoader.loadPresetSceneById(context, "astronaut")!!
        val image = scene.fullImageBitmap!!
        val depth = scene.depthData!!
        val source = IntArray(image.width * image.height)
        image.getPixels(source, 0, image.width, 0, 0, image.width, image.height)
        val prepareStart = android.os.SystemClock.elapsedRealtime()
        val layers = LayerBuilder.buildLayers(source, image.width, image.height, depth, 504, 504)!!
        val prepareMs = android.os.SystemClock.elapsedRealtime() - prepareStart
        ParallaxGpuFixture(540, 960).use { gpu ->
            gpu.upload(0, image.copy(Bitmap.Config.ARGB_8888, false))
            gpu.upload(2, Bitmap.createBitmap(layers.layers[0], image.width, image.height, Bitmap.Config.ARGB_8888))
            gpu.upload(3, Bitmap.createBitmap(layers.layers[1], image.width, image.height, Bitmap.Config.ARGB_8888))
            gpu.uniform("uImageAspect", image.width.toFloat() / image.height)
            gpu.uniform("uScaleType", 0f)
            gpu.uniform("uFill", .88f)
            gpu.uniform("uZoom", 1.21f)
            gpu.uniform("uNeutral", .87852156f)
            gpu.uniform("uStrength", 0f)
            gpu.render("astronaut_original")
            fun uploadDepth(flat: Boolean) {
                gpu.uniform("uLayered", if (flat) 0f else 1f)
                val pixels = if (flat) IntArray(depth.size) { Color.rgb(224, 224, 224) } else layers.depthTexture
                gpu.upload(1, Bitmap.createBitmap(pixels, 504, 504, Bitmap.Config.ARGB_8888))
                GLES20.glUniform2f(gpu.location("uDepthTexel"), 1f / 504, 1f / 504)
            }
            for (flat in listOf(true, false)) {
                uploadDepth(flat)
                for (direction in floatArrayOf(-1f, 0f, 1f)) for (strength in floatArrayOf(.25f, .5f, 1f)) {
                    gpu.offset(direction, 0f)
                    gpu.uniform("uStrength", strength)
                    gpu.render("astronaut_${if (flat) "flat" else "depth"}_${direction}_${strength}")
                }
            }
            gpu.uniform("uZoom3D", .35f)
            for (direction in floatArrayOf(-1f, 1f)) {
                gpu.offset(direction, 0f)
                gpu.render("astronaut_zoom3d_${direction}")
            }
            gpu.uniform("uZoom3D", 0f)
            gpu.offset(1f, 0f)
            val baselineSource = InstrumentationRegistry.getInstrumentation().context.assets
                .open("parallax_baseline.frag").bufferedReader().use { it.readText() }
            val baselineProgram = gpu.createProgram(baselineSource)
            GLES20.glUseProgram(baselineProgram)
            fun baselineFloat(name: String, value: Float) = GLES20.glUniform1f(GLES20.glGetUniformLocation(baselineProgram, name), value)
            arrayOf("image", "uDepthMap", "uInpaint", "uForegroundMask").forEachIndexed { i, name ->
                GLES20.glUniform1i(GLES20.glGetUniformLocation(baselineProgram, name), i)
            }
            baselineFloat("uAspectRatio", 540f / 960)
            baselineFloat("uImageAspect", image.width.toFloat() / image.height)
            baselineFloat("uFill", .88f); baselineFloat("uZoom", 1.21f)
            baselineFloat("uNeutral", .87852156f); baselineFloat("uDepthHeight", .06225f)
            baselineFloat("uStrength", 1f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(baselineProgram, "uOffset"), 1f, 0f)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(baselineProgram, "uColor"), 1f, 1f, 1f, 0f)
            repeat(3) { gpu.render(selectedProgram = baselineProgram); gpu.render() }
            val before = LongArray(20); val after = LongArray(20)
            repeat(20) { i ->
                var start = System.nanoTime()
                gpu.render(selectedProgram = baselineProgram)
                before[i] = System.nanoTime() - start
                start = System.nanoTime()
                gpu.render()
                after[i] = System.nanoTime() - start
            }
            val dir = File(context.cacheDir, "parallax-validation").apply { mkdirs() }
            File(dir, "render_timing.txt").writeText("size=540x960\nmethod=alternating 20 frames after 3 warmups; draw+readback+RGBA conversion, emulator wall time\nbaselineMedianMs=${before.sorted()[10] / 1e6}\nfixedMedianMs=${after.sorted()[10] / 1e6}\nprepare1024x1700Ms=$prepareMs\n")
        }
    }

    @Test fun revealedRegionReadsReconstructedBackgroundAtIndependentCoordinates() {
        ParallaxGpuFixture().use { gpu ->
            gpu.upload(0, gpu.bitmap { x, _ -> if (x in 96..159) Color.RED else Color.BLUE })
            gpu.upload(1, gpu.bitmap { x, _ -> Color.rgb(if (x in 96..159) 230 else 25, 25, 230) })
            gpu.upload(2, gpu.bitmap { x, _ -> if (x in 96..159) Color.GREEN else Color.BLUE })
            gpu.upload(3, gpu.bitmap { x, _ -> if (x in 96..159) Color.WHITE else Color.TRANSPARENT })
            GLES20.glUniform2f(gpu.location("uDepthTexel"), 1f / 256, 1f / 256)
            gpu.uniform("uDepthHeight", .12f)
            gpu.offset(1f, 0f)
            val pixels = gpu.render("independent_reconstructed_background")
            val exposed = pixels[128 * 256 + 100]
            assertTrue("Revealed hole must read the reconstructed green background", Color.green(exposed) > 240)
            assertTrue(Color.red(exposed) < 5 && Color.blue(exposed) < 5)
            assertTrue("Foreground must still move", Color.red(pixels[128 * 256 + 165]) > 240)
        }
    }

    @Test fun flatDepthPreservesRgbAndTinyTiltChangesDoNotFlashTheSilhouette() {
        ParallaxGpuFixture().use { gpu ->
            gpu.upload(3, gpu.bitmap { _, _ -> Color.WHITE })
            gpu.uniform("uNeutral", 128f / 255)
            val original = gpu.original("flat_reference")
            for (direction in floatArrayOf(-1f, 1f)) {
                gpu.offset(direction, direction)
                val pixels = gpu.render("flat_neutral_${direction}")
                for (i in pixels.indices) {
                    assertTrue(kotlin.math.abs(Color.red(pixels[i]) - Color.red(original[i])) <= 1)
                    assertTrue(kotlin.math.abs(Color.green(pixels[i]) - Color.green(original[i])) <= 1)
                }
            }
            gpu.upload(0, gpu.bitmap { x, _ -> if (x in 96..159) Color.RED else Color.BLUE })
            gpu.upload(1, gpu.bitmap { _, _ -> Color.rgb(128, 25, 230) })
            gpu.upload(2, gpu.bitmap { _, _ -> Color.BLUE })
            gpu.upload(3, gpu.bitmap { x, _ -> if (x in 96..159) Color.WHITE else Color.TRANSPARENT })
            GLES20.glUniform2f(gpu.location("uDepthTexel"), 1f / 256, 1f / 256)
            gpu.uniform("uNeutral", .5f)
            gpu.offset(.499f, 0f)
            val before = gpu.render()
            gpu.offset(.501f, 0f)
            val after = gpu.render()
            val flashing = before.indices.count { kotlin.math.abs(Color.red(before[it]) - Color.red(after[it])) > 32 }
            assertTrue("Subpixel tilt must not flash large image regions: $flashing", flashing < before.size / 100)
        }
    }
}
