package com.example.spatial

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.spatial.data.MotionLimits
import com.example.spatial.ui.SpatialViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZoomControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun zoomStillUsesItsFullRangeAndDepthZoomIsRemoved() {
        compose.onNodeWithContentDescription("Wallpapers").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Cliffside Village"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Cliffside Village").performClick()
        compose.onNodeWithContentDescription("Tune").performClick()
        compose.onNodeWithText("Fill", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Blurred background", useUnmergedTree = true).assertDoesNotExist()
        val viewModel = ViewModelProvider(compose.activity)[SpatialViewModel::class.java]

        fun drag(tag: String, toRight: Boolean) {
            compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
                val left = Offset(1f, center.y)
                val right = Offset(width - 1f, center.y)
                // Start in the middle, outside Android's edge navigation gesture zones.
                swipe(center, if (toRight) right else left, 500)
            }
            compose.waitForIdle()
        }

        drag("viewer_zoom", false)
        compose.runOnIdle { assertEquals(MotionLimits.MIN_ZOOM, viewModel.currentConfig.value.zoom, .001f) }
        drag("viewer_zoom", true)
        compose.runOnIdle { assertEquals(MotionLimits.MAX_ZOOM, viewModel.currentConfig.value.zoom, .001f) }
        compose.onNodeWithTag("viewer_zoom_3d", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Zoom 3D:", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Animations").performClick()
        compose.onNodeWithTag("viewer_zoom_3d", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Zoom 3D:", useUnmergedTree = true).assertDoesNotExist()
    }
}
