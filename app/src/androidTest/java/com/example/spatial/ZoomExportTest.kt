package com.example.spatial

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatial.data.MotionLimits
import com.example.spatial.data.SpatialSceneConfig
import com.example.spatial.gl.GifExporter
import com.example.spatial.gl.VideoExporter
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZoomExportTest {
    @Test fun encodedVideoPreservesZoomAndIgnoresLegacyDepthZoom() = runBlocking {
        val size = 256
        val image = Bitmap.createBitmap(IntArray(size * size) { i ->
            Color.rgb((i % size) * 255 / (size - 1), 80, 120)
        }, size, size, Bitmap.Config.ARGB_8888)
        val depth = ByteArray(16 * 16) { 255.toByte() }
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val sampledReds = mutableMapOf<Float, Int>()
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
                        // Sample at mid-loop (progress = 0.5) where cosine zoom breathing 0.5*(1 - cos(2*pi*p)) reaches peak 1.0.
                        val midTimeUs = duration * 1000L / 2L
                        val frame = requireNotNull(metadata.getFrameAtTime(midTimeUs, MediaMetadataRetriever.OPTION_CLOSEST))
                        try {
                            val sampleX = 224
                            sampledReds[zoom] = Color.red(frame.getPixel(sampleX, 128))
                        } finally { frame.recycle() }
                    } finally { metadata.release() }
                } finally { output.delete() }
            }
            // Zoom-in magnifies the center, so an off-center pixel at x=224 samples closer to center (lower red) than zoom-out.
            val redMinZoom = requireNotNull(sampledReds[MotionLimits.MIN_ZOOM])
            val redMaxZoom = requireNotNull(sampledReds[MotionLimits.MAX_ZOOM])
            assertTrue(
                "Zoom must visibly change exported framing (minZoom red=$redMinZoom, maxZoom red=$redMaxZoom)",
                redMinZoom - redMaxZoom >= 6
            )
        } finally { image.recycle() }
    }

    @Test fun gifExportProducesValidLoopingGifOnDevice() = runBlocking {
        val size = 128
        val image = Bitmap.createBitmap(IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            Color.rgb(x * 2, y * 2, 140)
        }, size, size, Bitmap.Config.ARGB_8888)
        val depth = ByteArray(16 * 16) { i -> ((i % 16) * 16).toByte() }
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val output = File(cache, "spatial_test_export.gif")
        try {
            val config = SpatialSceneConfig(
                intensity = 0.8f,
                speed = 1.0f,
                zoom = 0.1f,
                zoom3D = 0.5f,
                patternId = "horizontal"
            )
            GifExporter().export(image, depth, 16, 16, config, output) {}
            assertTrue("GIF file must be non-empty", output.exists() && output.length() > 1000)
            val bytes = output.readBytes()
            assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
            assertTrue(
                "GIF must contain NETSCAPE2.0 loop block",
                String(bytes, Charsets.ISO_8859_1).contains("NETSCAPE2.0")
            )
            assertEquals(0x3B.toByte(), bytes.last())
        } finally {
            output.delete()
            image.recycle()
        }
    }
}
