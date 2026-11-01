package com.example.spatial

import android.opengl.GLES20
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.example.spatial.data.SpatialSceneItem
import com.example.spatial.gl.SpatialGLSurfaceView
import com.example.spatial.ui.SpatialViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerViewportLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun viewportStaysBetweenToolbarAndBottomBarAcrossTabsAndControls() {
        compose.onNodeWithContentDescription("Wallpapers").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Cliffside Village"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Cliffside Village").performClick()
        compose.waitForIdle()

        fun findSurface(view: View): SpatialGLSurfaceView? {
            if (view is SpatialGLSurfaceView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findSurface(view.getChildAt(index))?.let { return it }
            }
            return null
        }

        fun assertViewportBounds(): Int {
            compose.waitForIdle()
            val toolbar = compose.onNodeWithTag("viewer_toolbar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val bottomBar = compose.onNodeWithTag("viewer_bottom_bar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            lateinit var surface: SpatialGLSurfaceView
            val location = IntArray(2)
            var width = 0
            var height = 0
            var imageWidth = 0
            var imageHeight = 0
            compose.runOnIdle {
                surface = requireNotNull(findSurface(compose.activity.window.decorView))
                surface.getLocationInWindow(location)
                width = surface.width
                height = surface.height
                val image = requireNotNull(surface.renderer.currentBitmap)
                imageWidth = image.width
                imageHeight = image.height
            }
            assertTrue("Viewport must have usable dimensions", width > 0 && height > 0)
            assertTrue("Viewport stays below toolbar", location[1] >= toolbar.bottom - 1f)
            assertTrue("Viewport stays above bottom bar", location[1] + height <= bottomBar.top + 1f)
            assertEquals("Viewport is vertically centered", (toolbar.bottom + bottomBar.top) / 2f,
                location[1] + height / 2f, 1f)
            assertEquals("Viewport is horizontally centered", toolbar.center.x, location[0] + width / 2f, 1f)
            assertEquals("Viewport preserves the source image ratio", imageWidth.toFloat() / imageHeight,
                width.toFloat() / height, 2f / height)
            assertTrue("Viewport uses the largest fitting rectangle",
                kotlin.math.abs(width - toolbar.width) <= 1f ||
                    kotlin.math.abs(height - (bottomBar.top - toolbar.bottom)) <= 1f)
            val viewport = IntArray(4)
            val latch = CountDownLatch(1)
            surface.queueEvent {
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
                latch.countDown()
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertArrayEquals(intArrayOf(0, 0, width, height), viewport)
            return height
        }

        val initialHeight = assertViewportBounds()
        compose.onNodeWithContentDescription("Tune").performClick()
        assertEquals(initialHeight, assertViewportBounds())
        compose.onNodeWithContentDescription("Tune").performClick()
        for (tab in listOf("Rotate", "Animations", "ZoomPan", "Shake")) {
            compose.onNodeWithContentDescription(tab).performClick()
            assertEquals("Tab $tab must retain the same viewport height", initialHeight, assertViewportBounds())
        }
        // Exercise both width-limited and height-limited layouts using real scene changes.
        for ((width, height) in listOf(160 to 80, 96 to 96, 80 to 160)) {
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            compose.runOnIdle {
                ViewModelProvider(compose.activity)[SpatialViewModel::class.java].selectScene(
                    SpatialSceneItem(
                        id = "viewport_${width}x$height", title = "Viewport test", folderPath = "",
                        isCustom = true, fullImageBitmap = image
                    )
                )
            }
            compose.waitUntil(10_000) {
                var loaded = false
                compose.runOnIdle {
                    loaded = findSurface(compose.activity.window.decorView)?.renderer?.currentBitmap === image
                }
                loaded
            }
            assertViewportBounds()
        }
    }
}
