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

    @Test fun percentileStretchExpandsCompressedDepthIgnoringOutliers() {
        val src = ByteArray(1000) { i ->
            when {
                i < 5 -> 0.toByte()          // isolated dark outlier
                i >= 995 -> 255.toByte()     // isolated bright outlier
                i < 500 -> 90.toByte()       // compressed background
                else -> 150.toByte()         // compressed foreground
            }
        }
        val out = DepthPreprocess.stretchContrastPercentile(src, lowPct = 0.02f, highPct = 0.98f)
        val bg = out[100].toInt() and 0xFF
        val fg = out[700].toInt() and 0xFF
        assertEquals(0, bg)
        assertEquals(255, fg)
    }

    @Test fun meshRefinementWeldsAdjacentBodyPartsWhileKeepingBackgroundSeparate() {
        val w = 64; val h = 64
        // Background (10) on left, "neck" (165) in middle, "head/chin" (225) on right
        val depth = ByteArray(w * h) { i ->
            val x = i % w
            when {
                x < 22 -> 10.toByte()
                x < 42 -> 165.toByte()
                else -> 225.toByte()
            }
        }
        val out = DepthPreprocess.refineDepthForMesh(depth, w, h)
        val row = IntArray(w) { out[32 * w + it].toInt() and 0xFF }
        assertTrue("Background must stay far (${row[4]})", row[4] < 25)
        assertTrue("Head must stay near (${row[58]})", row[58] > 235)
        // Step across head-neck seam (x=42) must be gentle so head does not detach from neck
        val headNeckMaxStep = (38..46).maxOf { kotlin.math.abs(row[it] - row[it - 1]) }
        assertTrue("Head-neck transition must be smooth (maxStep=$headNeckMaxStep)", headNeckMaxStep < 18)
        // Foreground-background separation must remain strong
        assertTrue("Subject-background separation must remain strong", row[32] - row[4] > 140)
    }

    @Test fun estimateDepthCenterTracksCentralSubject() {
        val w = 40; val h = 40
        // Bright foreground subject in center (depth ~210), dark background around (depth ~20)
        val depth = ByteArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x in 10..30 && y in 8..32) 210.toByte() else 20.toByte()
        }
        val center = DepthPreprocess.estimateDepthCenter(depth, w, h)
        assertTrue("Expected balanced center in [0.45, 0.72], got $center", center in 0.45f..0.72f)
    }

    @Test fun adaptiveModelSelectionFollowsPhotos3DTiers() {
        // First run on SDK >= 29 prefers LARGE (model_512.onnx); on SDK < 29 chooses SMALL (model_256.onnx)
        val firstModern = DepthPreprocess.selectAdaptiveModel(null, 0L, 0L, sdkInt = 29, preferLarge = true)
        assertEquals(DepthPreprocess.DepthModelSpec.LARGE, firstModern.spec)
        assertTrue(firstModern.shouldResetStats)

        val firstOld = DepthPreprocess.selectAdaptiveModel(null, 0L, 0L, sdkInt = 28, preferLarge = true)
        assertEquals(DepthPreprocess.DepthModelSpec.SMALL, firstOld.spec)

        // Downgrade LARGE -> SMALL when avg >= 5000ms after >= 2 runs
        val downgrade = DepthPreprocess.selectAdaptiveModel("l", avgDurationMs = 5200L, runCount = 2L, sdkInt = 34)
        assertEquals(DepthPreprocess.DepthModelSpec.SMALL, downgrade.spec)
        assertTrue(downgrade.shouldResetStats)

        // Keep LARGE when fast enough
        val keepLarge = DepthPreprocess.selectAdaptiveModel("l", avgDurationMs = 2100L, runCount = 3L, sdkInt = 34)
        assertEquals(DepthPreprocess.DepthModelSpec.LARGE, keepLarge.spec)
        assertFalse(keepLarge.shouldResetStats)

        // Upgrade SMALL -> LARGE when avg < 2500ms after >= 4 runs on SDK >= 28
        val upgrade = DepthPreprocess.selectAdaptiveModel("s", avgDurationMs = 1800L, runCount = 4L, sdkInt = 29)
        assertEquals(DepthPreprocess.DepthModelSpec.LARGE, upgrade.spec)
        assertTrue(upgrade.shouldResetStats)

        // Stay SMALL when fewer than 4 runs
        val staySmall = DepthPreprocess.selectAdaptiveModel("s", avgDurationMs = 1200L, runCount = 3L, sdkInt = 29)
        assertEquals(DepthPreprocess.DepthModelSpec.SMALL, staySmall.spec)
        assertFalse(staySmall.shouldResetStats)
    }

    @Test fun padTopLeftPixelsPreservesContentAndFillsBlackMargin() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val content = IntArray(4 * 4) { white }
        val padded = DepthPreprocess.padTopLeftPixels(content, 4, 4, 6, 6)
        assertEquals(36, padded.size)
        // Top-left 4x4 must remain white
        for (y in 0 until 4) for (x in 0 until 4) {
            assertEquals("($x,$y)", white, padded[y * 6 + x])
        }
        // Right 2px and bottom 2px margin must be opaque black
        for (y in 0 until 6) {
            assertEquals(black, padded[y * 6 + 4])
            assertEquals(black, padded[y * 6 + 5])
        }
        for (x in 0 until 6) {
            assertEquals(black, padded[4 * 6 + x])
            assertEquals(black, padded[5 * 6 + x])
        }
    }
}
