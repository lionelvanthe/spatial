package com.example.spatial

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatial.data.MotionLimits
import com.example.spatial.data.SpatialSceneConfig
import com.example.spatial.gl.VideoExporter
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZoomExportTest {
    @Test fun encodedVideoPreservesZoomAndIgnoresLegacyDepthZoom() = runBlocking {
        val size = 128
        val image = Bitmap.createBitmap(IntArray(size * size) { i ->
            Color.rgb((i % size) * 255 / (size - 1), 80, 120)
        }, size, size, Bitmap.Config.ARGB_8888)
        val depth = ByteArray(16 * 16) { 255.toByte() }
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        try {
            for (zoom in floatArrayOf(MotionLimits.MIN_ZOOM, MotionLimits.MAX_ZOOM)) {
                val output = File(cache, "zoom_export_$zoom.mp4")
                val config = SpatialSceneConfig(zoom = zoom, zoom3D = MotionLimits.MAX_ZOOM_3D,
                    depthCenter = .95f, patternId = "vertical", speed = 1f)
                try {
                    VideoExporter().export(image, depth, 16, 16, config, output) {}
                    assertTrue(output.length() > 0)
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(output.absolutePath)
                        val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                        assertTrue("Export must contain the complete loop", duration in 2900L..3100L)
                        val frame = requireNotNull(metadata.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                        try {
                            val effectiveZoom = if (zoom < 0f) zoom * .5f else zoom
                            val scale = .88f * (1f + effectiveZoom * .6f)
                            val expectedRed = (((90.5f / size - .5f) / scale) + .5f) * 255f
                            assertEquals("Encoded zoom=$zoom", expectedRed, Color.red(frame.getPixel(90, 64)).toFloat(), 12f)
                        } finally { frame.recycle() }
                    } finally { metadata.release() }
                } finally { output.delete() }
            }
        } finally { image.recycle() }
    }
}
