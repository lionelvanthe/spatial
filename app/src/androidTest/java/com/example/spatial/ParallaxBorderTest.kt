package com.example.spatial

import android.graphics.Color
import android.opengl.GLES20
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatial.data.AssetDataLoader
import com.example.spatial.gl.SpatialGLRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParallaxBorderTest {
    @Test fun captureCliffsideAtBothTiltLimits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scene = requireNotNull(AssetDataLoader.loadPresetSceneById(context, "cliff_village"))
        ParallaxGpuFixture(360, 640).use { gpu ->
            val renderer = SpatialGLRenderer(context)
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 360, 640)
            renderer.setSceneData(scene.fullImageBitmap, scene.depthData,
                scene.config.copy(zoom = 0f, rInverted = false), scene.depthWidth, scene.depthHeight)
            // This mode applies the full sensor input immediately, without waiting for smoothing.
            renderer.activeTabMode = 1
            for (direction in floatArrayOf(-1f, 0f, 1f)) {
                renderer.resetTouchState()
                renderer.gyroX = direction
                renderer.onDrawFrame(null)
                gpu.capture("cliff_edge_$direction")
            }
        }
    }

    @Test fun flatSurfaceMovesRigidlyThroughBothImageEdges() {
        val size = 256
        ParallaxGpuFixture(size, size).use { gpu ->
            val background = Color.rgb(10, 20, 30)
            gpu.upload(0, gpu.bitmap { x, _ -> Color.rgb(x, 80, 120) })
            gpu.upload(2, gpu.bitmap { x, _ -> Color.rgb(x, 80, 120) })
            gpu.upload(3, gpu.bitmap { _, _ -> Color.WHITE })
            gpu.uniform("uFill", 1f)
            gpu.uniform("uDepthHeight", .12f)
            gpu.uniform("uNeutral", .5f)
            GLES20.glUniform4f(gpu.location("uBgColor"), 10f / 255, 20f / 255, 30f / 255, 1f)
            for (depth in intArrayOf(25, 230)) {
                gpu.upload(1, gpu.bitmap { _, _ -> Color.rgb(depth, depth, depth) })
                for (direction in floatArrayOf(-1f, 1f)) {
                    gpu.offset(direction, 0f)
                    val pixels = gpu.render("rigid_edge_${depth}_$direction")
                    // Parallax bleed ensures pixels all the way to both borders contain photo data,
                    // without exposing the background color or black border strips.
                    assertFalse("Left border must not expose background", pixels[128 * size + 0] == background)
                    assertFalse("Right border must not expose background", pixels[128 * size + (size - 1)] == background)
                    var prevRed = -1
                    for (x in 0 until size) {
                        val actual = pixels[128 * size + x]
                        assertNotEquals("Pixel at x=$x must contain photo data, not background", background, actual)
                        val red = Color.red(actual)
                        assertTrue("Monotonic gradient across surface at x=$x (got $red, prev $prevRed)", red >= prevRed)
                        prevRed = red
                    }
                }
            }
        }
    }
}
