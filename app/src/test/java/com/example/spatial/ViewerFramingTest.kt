package com.example.spatial

import com.example.spatial.gl.ViewerFraming
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerFramingTest {
    @Test fun switchingMotionTabsPreservesPinchZoom() {
        for (from in 0..2) for (to in 0..2) {
            assertEquals(true, ViewerFraming.preserveTouchZoom(from, to))
        }
        for (tab in 0..2) {
            assertEquals(false, ViewerFraming.preserveTouchZoom(tab, 3))
            assertEquals(false, ViewerFraming.preserveTouchZoom(3, tab))
        }
    }

    @Test fun allMotionTabsPreserveSceneZoom() {
        for (tab in 0..2) {
            assertEquals(0.7f, ViewerFraming.effectiveZoom(0.7f, tab), 0f)
            assertEquals(-0.35f, ViewerFraming.effectiveZoom(-0.7f, tab), 0f)
            assertEquals(0f, ViewerFraming.effectiveZoom(0f, tab), 0f)
            assertEquals(0.8f, ViewerFraming.zoom3D(0.7f, 0.8f, tab), 0f)
            assertEquals(0.8f, ViewerFraming.zoom3D(-0.7f, 0.8f, tab), 0f)
            assertEquals(0f, ViewerFraming.zoom3D(0.09f, 0.8f, tab), 0f)
        }
    }

    @Test fun zoomPanKeepsIndependentTouchFraming() {
        assertEquals(0f, ViewerFraming.effectiveZoom(0.7f, 3), 0f)
        assertEquals(0f, ViewerFraming.zoom3D(0.7f, 0.8f, 3), 0f)
    }

    @Test fun depthCenterKeepsTheSceneConvention() {
        assertEquals(0.2f, ViewerFraming.depthCenter(0.2f), 0f)
        assertEquals(0.8f, ViewerFraming.depthCenter(0.8f), 0f)
        assertEquals(0f, ViewerFraming.depthCenter(-1f), 0f)
        assertEquals(1f, ViewerFraming.depthCenter(2f), 0f)
    }
}
