package com.example.spatial

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.spatial.data.SpatialSceneItem
import com.example.spatial.gl.SpatialGLSurfaceView
import com.example.spatial.ui.SpatialViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun findSurface(view: View): SpatialGLSurfaceView? {
        if (view is SpatialGLSurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun loadingShown() =
        compose.onAllNodes(hasContentDescription("Loading scene")).fetchSemanticsNodes().isNotEmpty()

    @Test fun loadingCoversTheOldSceneUntilTheNewOneIsDrawn() {
        compose.onNodeWithContentDescription("Wallpapers").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("Cliffside Village")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Cliffside Village").performClick()
        compose.waitUntil(10_000) { !loadingShown() }
        val viewModel = ViewModelProvider(compose.activity)[SpatialViewModel::class.java]
        lateinit var surface: SpatialGLSurfaceView
        compose.runOnIdle {
            surface = requireNotNull(findSurface(compose.activity.window.decorView))
            assertEquals("cliff_village", viewModel.currentScene.value?.id)
            assertSame(viewModel.currentScene.value?.fullImageBitmap, surface.renderer.currentBitmap)
        }

        // With rendering paused the new photo cannot be drawn, so the loader must stay up.
        val image = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            surface.onPause()
            viewModel.selectScene(
                SpatialSceneItem(id = "loading_test", title = "Loading test", folderPath = "",
                    isCustom = true, fullImageBitmap = image)
            )
        }
        compose.waitUntil(10_000) { viewModel.currentScene.value?.fullImageBitmap === image }
        compose.waitForIdle()
        assertTrue("Loader must cover the viewport before the new scene is drawn", loadingShown())

        compose.runOnIdle { surface.onResume() }
        compose.waitUntil(10_000) { !loadingShown() }
        compose.runOnIdle { assertSame(image, surface.renderer.currentBitmap) }
    }
}
