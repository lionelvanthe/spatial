package com.example.spatial

import com.example.spatial.ai.DepthPreprocess
import com.example.spatial.ai.DepthPreprocess.Box
import com.example.spatial.data.DepthMap
import org.junit.Assert.*
import org.junit.Test

class DepthPreprocessTest {
    @Test fun inputBytesAreInterleavedRgbWithoutNormalization() {
        val packed = DepthPreprocess.rgbNhwc(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF123456.toInt()))
        assertArrayEquals(byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, 0x12, 0x34, 0x56), packed)
    }

    @Test fun bilinearResizeInterpolatesUnsignedDepthInBothAxes() {
        val out = DepthPreprocess.resizeBilinear(byteArrayOf(0, 100, 100, 200.toByte()), 2, 2, 3, 3)
        assertEquals(100, out[4].toInt() and 255)
        assertEquals(50, out[1].toInt() and 255)
        assertEquals(0, out[0].toInt() and 255)
        assertEquals(200, out[8].toInt() and 255)
    }
    @Test fun portraitIsLetterboxedNotStretched() {
        val b = DepthPreprocess.letterbox(1000, 2000, 256)
        assertEquals(128, b.width)
        assertEquals(256, b.height)
        assertEquals(64, b.left)
        assertEquals(0, b.top)
        assertEquals(1000f / 2000f, b.width.toFloat() / b.height, 0.01f)
    }

    @Test fun landscapeAndSquareAndPanorama() {
        val land = DepthPreprocess.letterbox(1600, 900, 256)
        assertEquals(256, land.width)
        assertEquals(144, land.height)
        assertEquals(56, land.top)
        val sq = DepthPreprocess.letterbox(500, 500, 256)
        assertEquals(Box(0, 0, 256, 256), sq)
        val pano = DepthPreprocess.letterbox(4000, 500, 256)
        assertEquals(256, pano.width)
        assertEquals(32, pano.height)
    }

    @Test fun cropMapsIntoModelOutputSpace() {
        val box = DepthPreprocess.letterbox(1000, 2000, 256)
        val same = DepthPreprocess.mapToOutput(box, 256, 256)
        assertEquals(box, same)
        val half = DepthPreprocess.mapToOutput(box, 256, 128)
        assertEquals(64, half.width)
        assertEquals(128, half.height)
        assertEquals(32, half.left)
    }

    @Test fun normalisationIgnoresPaddingBars() {
        // 4x4 output, photo occupies the middle two columns; the bars hold extreme values.
        val values = FloatArray(16)
        for (y in 0 until 4) {
            values[y * 4 + 0] = 1000f
            values[y * 4 + 3] = -1000f
            values[y * 4 + 1] = 2f
            values[y * 4 + 2] = 4f
        }
        val out = DepthPreprocess.cropNormalized(values, 4, Box(1, 0, 2, 4))
        assertEquals(8, out.size)
        assertEquals(0, out[0].toInt() and 0xFF)
        assertEquals(255, out[1].toInt() and 0xFF)
    }

    @Test fun resultKeepsRectangularShape() {
        val (w, h) = DepthPreprocess.fitSize(1000, 2000, 504)
        assertEquals(252, w)
        assertEquals(504, h)
        val resized = DepthPreprocess.resizeBilinear(ByteArray(10 * 20) { 100 }, 10, 20, 25, 50)
        assertEquals(25 * 50, resized.size)
        assertTrue(resized.all { (it.toInt() and 0xFF) == 100 })
    }

    @Test fun depthMapValidatesItsSize() {
        assertNotNull(DepthMap.orNull(ByteArray(12), 3, 4))
        assertNull(DepthMap.orNull(ByteArray(12), 4, 4))
        assertNull(DepthMap.orNull(null, 3, 4))
        assertNull(DepthMap.orNull(ByteArray(12), 0, 12))
        try {
            DepthMap(3, 3, ByteArray(10))
            fail("expected size mismatch to be rejected")
        } catch (e: IllegalArgumentException) { }
    }

    @Test fun smoothingTurnsACliffIntoASlopeAndUsesFullRange() {
        val w = 100; val h = 50
        val src = ByteArray(w * h) { if (it % w < 50) 20 else 220.toByte() }
        val out = DepthPreprocess.smoothForParallax(src, w, h)
        assertEquals(w * h, out.size)
        val row = IntArray(w) { out[25 * w + it].toInt() and 0xFF }
        // far left is the lowest, far right the highest: the whole 0..255 range is used
        assertEquals(0, row.min())
        assertEquals(255, row.max())
        // never goes backwards across the cliff, and the step is spread over many pixels
        for (x in 1 until w) assertTrue("x=$x", row[x] >= row[x - 1])
        val rampWidth = row.count { it in 30..225 }
        assertTrue("ramp is only $rampWidth px wide", rampWidth >= 6)
    }

    @Test fun flatDepthStaysFlat() {
        val out = DepthPreprocess.smoothForParallax(ByteArray(40 * 30) { 90 }, 40, 30)
        assertTrue(out.all { it.toInt() == 0 })
    }
}
