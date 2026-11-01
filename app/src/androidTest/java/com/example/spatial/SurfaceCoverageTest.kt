package com.example.spatial

import android.graphics.Color
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatial.data.AssetDataLoader
import com.example.spatial.gl.ParallaxShaders
import com.example.spatial.gl.SpatialGLRenderer
import com.example.spatial.gl.SpatialMesh
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.test.core.app.ActivityScenario
import android.view.View
import android.view.ViewGroup
import com.example.spatial.gl.SpatialGLSurfaceView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SurfaceCoverageTest {
    @Test fun actualMainScreenSurfaceUsesFillAndViewportMatchesMeasuredView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var surface: SpatialGLSurfaceView? = null
            fun find(view: View): SpatialGLSurfaceView? {
                if (view is SpatialGLSurfaceView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (surface == null && System.nanoTime() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { surface = find(it.window.decorView) }
                if (surface == null) Thread.sleep(50)
            }
            val view = requireNotNull(surface) { "MainScreen must contain the real GLSurfaceView" }
            assertTrue("Preview must always use Fit", view.renderer.fitImage)
            var measuredWidth = 0; var measuredHeight = 0
            scenario.onActivity { measuredWidth = view.width; measuredHeight = view.height }
            assertTrue(measuredWidth > 0 && measuredHeight > 0)
            val latch = CountDownLatch(1)
            val viewport = IntArray(4)
            view.queueEvent {
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
                latch.countDown()
            }
            assertTrue("GL context must be ready", latch.await(10, TimeUnit.SECONDS))
            assertArrayEquals(intArrayOf(0, 0, measuredWidth, measuredHeight), viewport)
            val dir = File(instrumentation.targetContext.cacheDir, "parallax-validation").apply { mkdirs() }
            File(dir, "actual_surface_dimensions.txt").writeText("view=${measuredWidth}x$measuredHeight\nviewport=${viewport.contentToString()}\nfitImage=${view.renderer.fitImage}\n")
        }
    }

    @Test fun distinguishShaderLetterboxFromUncoveredSurfaceAcrossRealEglResizes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val scene = AssetDataLoader.loadPresetSceneById(app, "astronaut")!!
        val report = StringBuilder()
        ParallaxGpuFixture(403, 637).use { gpu ->
            val renderer = SpatialGLRenderer(app)
            renderer.onSurfaceCreated(null, null)
            renderer.fitImage = true
            renderer.setSceneData(scene.fullImageBitmap, scene.depthData, scene.config.copy(zoom = 0f, zoom3D = 0f), scene.depthWidth, scene.depthHeight)
            val mesh = renderer.javaClass.getDeclaredField("rayMarchMesh").apply { isAccessible = true }.get(renderer) as SpatialMesh
            for ((width, height) in listOf(403 to 637, 403 to 594, 637 to 403, 403 to 637)) {
                renderer.fitImage = true
                if (gpu.width != width || gpu.height != height) gpu.resizeSurface(width, height)
                renderer.onSurfaceChanged(null, width, height)
                val viewport = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
                assertArrayEquals(intArrayOf(0, 0, width, height), viewport)
                assertFalse(GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST))
                val fbo = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fbo, 0)
                assertEquals(0, fbo[0])
                GLES20.glClearColor(1f, 0f, 1f, 1f)
                renderer.gyroX = .25f
                renderer.onDrawFrame(null)
                val current = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, current, 0)
                val production = current[0]
                val diagnostic = gpu.createProgram(ParallaxShaders.FRAGMENT.replace(
                    "if (uStrength <= 0.0001)", "if (uStrength < -1.0)"))

                fun draw(program: Int, strength: Float, name: String): IntArray {
                    GLES20.glUseProgram(program)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uStrength"), strength)
                    GLES20.glClearColor(1f, 0f, 1f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    mesh.draw(GLES20.glGetAttribLocation(program, "position"), GLES20.glGetAttribLocation(program, "textureST"))
                    val pixels = gpu.capture("strip_${width}x${height}_$name")
                    assertFalse("No pixel may expose diagnostic clear magenta: $name", pixels.any { it == Color.MAGENTA })
                    return pixels
                }
                fun borders(pixels: IntArray): Pair<Int, Int> {
                    fun dark(x: Int): Boolean = (0 until height).all { y ->
                        val c = pixels[y * width + x]
                        abs(Color.red(c) - 15) <= 1 && abs(Color.green(c) - 15) <= 1 && abs(Color.blue(c) - 23) <= 1
                    }
                    val left = (0 until width).takeWhile { dark(it) }.size
                    val right = (width - 1 downTo 0).takeWhile { dark(it) }.size
                    return left to right
                }

                // A: direct original RGB, no UV framing. Uses the real production mesh.
                val raw = gpu.createProgram("""
                    precision highp float;
                    uniform sampler2D image;
                    varying vec2 v_TexCoord;
                    void main() { gl_FragColor = texture2D(image, v_TexCoord); }
                """)
                GLES20.glUseProgram(raw)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(raw, "image"), 0)
                val a = draw(raw, 0f, "A_raw_rgb")
                assertEquals(0 to 0, borders(a))
                // B: production strength-zero bypass, with production fit/zoom uniforms intact.
                val b = draw(production, 0f, "B_zero_bypass")
                // Copy all production uniforms into a test-only program that skips the bypass.
                copyUniforms(production, diagnostic)
                val c = draw(diagnostic, 0f, "C_zero_compositor")
                val d = draw(production, 1f, "D_parallax")
                val expected = maxOf(0f, (width - height * (1024f / 1700) * .88f) / 2).roundToInt()
                assertTrue("Framing must be centered", abs(borders(b).first - borders(b).second) <= 1)
                for ((name, pixels) in listOf("B" to b, "C" to c, "D" to d)) {
                    val edges = borders(pixels)
                    assertTrue("Expected fit border $expected, got $edges for $name", abs(edges.first - expected) <= 1)
                    assertEquals(borders(b), edges)
                    report.append("${width}x$height $name left=${edges.first} right=${edges.second} clearExposed=0 viewport=${viewport.contentToString()} fbo=0 scissor=false\n")
                }
                // A matching fit/crop reference must be pixel-identical to original-photo bypass.
                val reference = gpu.createProgram(ParallaxShaders.FRAGMENT.substringBefore("            vec2 finalUV = uv;") + "gl_FragColor = texture2D(image, uv); }")
                copyUniforms(production, reference)
                val originalFit = draw(reference, 0f, "A_fit_reference")
                assertArrayEquals("Bypass must preserve RGB with identical framing", b, originalFit)

                // The old asset shader has the same letterbox: this is a framing policy, not
                // a viewport or independent-layer compositor regression.
                val legacySource = InstrumentationRegistry.getInstrumentation().context.assets
                    .open("parallax_baseline.frag").bufferedReader().use { it.readText() }
                val legacy = gpu.createProgram(legacySource)
                copyUniforms(production, legacy)
                GLES20.glUseProgram(legacy)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(legacy, "uZoom"), 1f)
                assertEquals(borders(b), borders(draw(legacy, 0f, "legacy_zero_fit")))

                // Old callers cannot switch the app back to Fill.
                renderer.fitImage = false
                assertTrue(renderer.fitImage)
                renderer.onDrawFrame(null)
                val fitAgain = draw(production, 0f, "after_legacy_fill_request")
                assertArrayEquals("Legacy Fill requests must preserve Fit framing", b, fitAgain)
                renderer.activeTabMode = 2
                renderer.onDrawFrame(null)
                val anim = gpu.capture("strip_${width}x${height}_after_Animations")
                assertEquals(borders(d), borders(anim))
                assertFalse(anim.any { it == Color.MAGENTA })
                renderer.activeTabMode = 0
                report.append("${width}x$height AFTER legacy Fill request remains Fit; Animations borders match Fit; clearExposed=0\n")
            }
        }
        val dir = File(app.cacheDir, "parallax-validation").apply { mkdirs() }
        File(dir, "surface_coverage.txt").writeText(report.toString())
    }

    private fun copyUniforms(source: Int, destination: Int) {
        val count = IntArray(1)
        GLES20.glGetProgramiv(source, GLES20.GL_ACTIVE_UNIFORMS, count, 0)
        GLES20.glUseProgram(destination)
        for (i in 0 until count[0]) {
            val size = IntArray(1); val type = IntArray(1)
            val name = GLES20.glGetActiveUniform(source, i, size, 0, type, 0)
            val from = GLES20.glGetUniformLocation(source, name)
            val to = GLES20.glGetUniformLocation(destination, name)
            if (to < 0) continue
            val values = FloatArray(4)
            if (type[0] == GLES20.GL_SAMPLER_2D) {
                val unit = IntArray(1)
                GLES20.glGetUniformiv(source, from, unit, 0)
                GLES20.glUniform1i(to, unit[0])
            } else {
                GLES20.glGetUniformfv(source, from, values, 0)
                when (type[0]) {
                    GLES20.GL_FLOAT -> GLES20.glUniform1f(to, values[0])
                    GLES20.GL_FLOAT_VEC2 -> GLES20.glUniform2f(to, values[0], values[1])
                    GLES20.GL_FLOAT_VEC4 -> GLES20.glUniform4f(to, values[0], values[1], values[2], values[3])
                }
            }
        }
    }
}
