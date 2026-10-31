package com.example.spatial

import com.example.spatial.gl.LayerBuilder
import org.junit.Assert.*
import org.junit.Test

class LayerBuilderTest {
    private val w = 96
    private val h = 96
    private val dw = 96
    private val dh = 96

    // Red disc on a blue background; the disc is near (bright depth), the background far.
    private fun scene(): Pair<IntArray, ByteArray> {
        val px = IntArray(w * h)
        val depth = ByteArray(dw * dh)
        for (y in 0 until h) for (x in 0 until w) {
            val inDisc = (x - 48) * (x - 48) + (y - 48) * (y - 48) < 20 * 20
            px[y * w + x] = if (inDisc) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
            depth[y * dw + x] = if (inDisc) 230.toByte() else 25.toByte()
        }
        return px to depth
    }

    @Test fun separatesNearObjectFromBackground() {
        val (px, depth) = scene()
        val data = LayerBuilder.buildLayers(px, w, h, depth, dw, dh)
        assertNotNull(data)
        data!!
        assertEquals(2, data.layers.size)
        assertTrue(data.depths[1] > data.depths[0])
        // Near layer: opaque red in the middle, transparent far from the disc.
        val near = data.layers[1]
        assertEquals(255, near[48 * w + 48] ushr 24)
        assertEquals(0, near[5 * w + 5] ushr 24)
    }

    @Test fun backgroundLayerHasNoNearObjectLeftBehind() {
        val (px, depth) = scene()
        val data = LayerBuilder.buildLayers(px, w, h, depth, dw, dh)!!
        val bg = data.layers[0]
        // Everything is opaque and the disc centre is filled with background blue, not red.
        assertEquals(255, bg[48 * w + 48] ushr 24)
        val c = bg[48 * w + 48]
        assertTrue("red=${(c shr 16) and 0xFF}", ((c shr 16) and 0xFF) < 60)
        assertTrue("blue=${c and 0xFF}", (c and 0xFF) > 190)
    }

    @Test fun flatDepthProducesNoLayers() {
        val px = IntArray(w * h) { 0xFF808080.toInt() }
        val depth = ByteArray(dw * dh) { 100 }
        assertNull(LayerBuilder.buildLayers(px, w, h, depth, dw, dh))
    }

    @Test fun pushPullKeepsValidPixelsAndFillsHoles() {
        val px = IntArray(16 * 16) { 0xFF00FF00.toInt() }
        val valid = BooleanArray(16 * 16) { (it % 16) < 8 || (it / 16) < 8 }
        for (i in px.indices) if (!valid[i]) px[i] = 0xFFFF0000.toInt()
        val out = LayerBuilder.pushPull(px, valid, 16, 16)
        assertEquals(0xFF00FF00.toInt(), out[0])
        assertEquals(0xFF00FF00.toInt(), out[15 * 16 + 15])
    }

    @Test fun stackedLayersReproduceThePhotoAtRest() {
        val (px, depth) = scene()
        val data = LayerBuilder.buildLayers(px, w, h, depth, dw, dh)!!
        var off = 0
        for (i in px.indices) {
            var r = 0f; var g = 0f; var b = 0f
            for (layer in data.layers) {
                val a = (layer[i] ushr 24) / 255f
                r = ((layer[i] shr 16) and 0xFF) * a + r * (1 - a)
                g = ((layer[i] shr 8) and 0xFF) * a + g * (1 - a)
                b = (layer[i] and 0xFF) * a + b * (1 - a)
            }
            val d = maxOf(
                kotlin.math.abs(r.toInt() - ((px[i] shr 16) and 0xFF)),
                kotlin.math.abs(g.toInt() - ((px[i] shr 8) and 0xFF)),
                kotlin.math.abs(b.toInt() - (px[i] and 0xFF))
            )
            if (d > 24) off++
        }
        // Only the thin soft edge of the disc may differ: well under 3% of the picture.
        assertTrue("$off of ${px.size} pixels differ", off < px.size * 0.03)
    }

    @Test fun backgroundBehindNearObjectCarriesNoObjectColour() {
        val (px, depth) = scene()
        val bg = LayerBuilder.buildLayers(px, w, h, depth, dw, dh)!!.layers[0]
        // Ring just inside the disc edge, where the soft edge lives: must be background blue, not red.
        var redish = 0
        for (y in 0 until h) for (x in 0 until w) {
            val d = Math.hypot((x - 48.0), (y - 48.0))
            if (d in 17.0..20.0) {
                if (((bg[y * w + x] shr 16) and 0xFF) > 100) redish++
            }
        }
        assertEquals(0, redish)
    }

    @Test fun extendedDepthFieldsDoNotContainTheOppositeSurface() {
        val (px, depth) = scene()
        val data = LayerBuilder.buildLayers(px, w, h, depth, dw, dh)!!
        for (i in depth.indices) {
            assertEquals(depth[i].toInt() and 255, (data.depthTexture[i] shr 16) and 255)
            assertEquals(25, (data.depthTexture[i] shr 8) and 255)
            assertEquals(230, data.depthTexture[i] and 255)
        }
    }

    @Test fun reconstructionBlendsSeedsAndPreservesKnownPixels() {
        val width = 33; val height = 9
        val valid = BooleanArray(width * height) { it % width < 4 || it % width >= 29 }
        val pixels = IntArray(valid.size) { if (it % width < 4) 0xFF0000FF.toInt() else if (it % width >= 29) 0xFF00FF00.toInt() else 0xFFFF0000.toInt() }
        val result = LayerBuilder.pushPull(pixels, valid, width, height)
        for (i in valid.indices) if (valid[i]) assertEquals(pixels[i], result[i])
        val center = result[4 * width + 16]
        assertEquals(0, (center shr 16) and 255)
        assertTrue(((center shr 8) and 255) in 1..254)
        assertTrue((center and 255) in 1..254)
    }

    @Test fun nearFieldExtendsInteriorAcrossContourRampButRetainsSurfaceVariation() {
        val (px, depth) = scene()
        for (y in 0 until h) for (x in 0 until w) {
            if ((depth[y * w + x].toInt() and 255) == 230) {
                depth[y * w + x] = (180 + x - 28).toByte()
            }
        }
        depth[48 * w + 29] = 125
        val packed = LayerBuilder.buildLayers(px, w, h, depth, dw, dh)!!.depthTexture
        assertTrue("Contour ramp must not be retained as a near surface", (packed[48 * w + 29] and 255) > 170)
        for (x in 40..56) {
            assertEquals("Interior depth must remain continuous", depth[48 * w + x].toInt() and 255, packed[48 * w + x] and 255)
        }
    }
}
