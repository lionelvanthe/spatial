package com.example.spatial.gl

import kotlin.math.roundToInt

/**
 * Splits an image into a far, inpainted background and a near foreground.
 *
 * A single textured height mesh has to interpolate a triangle across an occlusion edge.  That
 * interpolation stretches pixels from the subject into the background as the view moves.  These
 * two textures let the renderer move each side of the edge independently instead.
 */
object LayerBuilder {
    data class LayerData(val layers: Array<IntArray>, val depths: FloatArray, val depthTexture: IntArray)

    fun buildLayers(
        pixels: IntArray,
        width: Int,
        height: Int,
        depth: ByteArray,
        depthWidth: Int,
        depthHeight: Int
    ): LayerData? {
        if (width <= 0 || height <= 0 || pixels.size != width * height ||
            depthWidth <= 0 || depthHeight <= 0 || depth.size < depthWidth * depthHeight
        ) return null

        var low = 255
        var high = 0
        val sampledDepth = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            // GL_LINEAR texel centres, matching the depth texture read by the shader.
            val sx = ((x + .5f) * depthWidth / width - .5f).coerceIn(0f, (depthWidth - 1).toFloat())
            val sy = ((y + .5f) * depthHeight / height - .5f).coerceIn(0f, (depthHeight - 1).toFloat())
            val x0 = sx.toInt(); val y0 = sy.toInt()
            val x1 = minOf(x0 + 1, depthWidth - 1); val y1 = minOf(y0 + 1, depthHeight - 1)
            fun d(xx: Int, yy: Int) = depth[yy * depthWidth + xx].toInt() and 255
            val fx = sx - x0; val fy = sy - y0
            val value = ((d(x0, y0) * (1 - fx) + d(x1, y0) * fx) * (1 - fy) +
                (d(x0, y1) * (1 - fx) + d(x1, y1) * fx) * fy).roundToInt()
            val i = y * width + x
            sampledDepth[i] = value
            low = minOf(low, value)
            high = maxOf(high, value)
        }
        if (high - low < MIN_DEPTH_RANGE) return null

        val threshold = otsuThreshold(sampledDepth)
        val nearMask = BooleanArray(pixels.size) { sampledDepth[it] > threshold }
        val nearCount = nearMask.count { it }
        // A nearly full mask is not a separable foreground object; keep normal continuous depth.
        if (nearCount < pixels.size / 200 || nearCount > pixels.size * 19 / 20) return null

        val foreground = IntArray(pixels.size)
        val backgroundValid = BooleanArray(pixels.size)
        // Depth contours are lower resolution than RGB. Exclude at least one depth texel's
        // footprint from RGB fill seeds, rather than a fixed one-photo-pixel ring.
        val guard = maxOf(1, kotlin.math.ceil(maxOf(width.toFloat() / depthWidth, height.toFloat() / depthHeight)).toInt())
        val horizontalGuard = BooleanArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            for (dx in -guard..guard) {
                if (nearMask[y * width + (x + dx).coerceIn(0, width - 1)]) {
                    horizontalGuard[y * width + x] = true
                    break
                }
            }
        }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            var nearNeighbours = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (nearMask[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]) nearNeighbours++
            }
            if (nearMask[i]) {
                // Subpixel inward feather; no foreground RGB is extended onto far pixels.
                val alpha = 224 + nearNeighbours * 31 / 9
                foreground[i] = (pixels[i] and 0x00FFFFFF) or (alpha shl 24)
            } else {
                foreground[i] = pixels[i] and 0x00FFFFFF
            }
            // Exclude a one-pixel ring so antialiased subject colours cannot seed the fill.
            backgroundValid[i] = true
            for (dy in -guard..guard) {
                if (horizontalGuard[(y + dy).coerceIn(0, height - 1) * width + x]) {
                    backgroundValid[i] = false
                    break
                }
            }
        }
        if (backgroundValid.none { it }) return null
        val background = pushPull(pixels.copyOf(), backgroundValid, width, height)
        val nearDepth = BooleanArray(depthWidth * depthHeight) { (depth[it].toInt() and 255) > threshold }
        val interior = BooleanArray(nearDepth.size)
        for (y in 0 until depthHeight) for (x in 0 until depthWidth) {
            var solid = true
            for (dy in -2..2) for (dx in -2..2) {
                if (!nearDepth[(y + dy).coerceIn(0, depthHeight - 1) * depthWidth + (x + dx).coerceIn(0, depthWidth - 1)]) solid = false
            }
            interior[y * depthWidth + x] = solid
        }
        val depthPixels = IntArray(nearDepth.size) {
            val d = depth[it].toInt() and 255
            0xFF000000.toInt() or (d shl 16) or (d shl 8) or d
        }
        // Extrapolate each surface into its hidden region. The foreground ray no longer
        // intersects a fictitious wall between near and far; its mask supplies visibility.
        val farField = pushPull(depthPixels, BooleanArray(nearDepth.size) { !nearDepth[it] }, depthWidth, depthHeight)
        // The bilinear silhouette ramp is not a real surface. Extrapolate from near interiors
        // across that ramp, while retaining continuous depth variations inside each object.
        val nearField = pushPull(depthPixels, if (interior.any { it }) interior else nearDepth, depthWidth, depthHeight)
        val packed = IntArray(nearDepth.size) {
            0xFF000000.toInt() or ((depth[it].toInt() and 255) shl 16) or
                (farField[it] and 0x0000FF00) or (nearField[it] and 255)
        }
        return LayerData(arrayOf(background, foreground), floatArrayOf(0f, 1f), packed)
    }

    /** Multiscale push/pull reconstruction; known source pixels are preserved exactly. */
    fun pushPull(pixels: IntArray, valid: BooleanArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height && valid.size == pixels.size)
        if (valid.all { it } || valid.none { it }) return pixels.copyOf()
        val cw = (width + 1) / 2; val ch = (height + 1) / 2
        val coarse = IntArray(cw * ch)
        val coarseValid = BooleanArray(coarse.size)
        for (y in 0 until ch) for (x in 0 until cw) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in 0..1) for (dx in 0..1) {
                val xx = x * 2 + dx; val yy = y * 2 + dy
                if (xx >= width || yy >= height || !valid[yy * width + xx]) continue
                val p = pixels[yy * width + xx]
                r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++
            }
            if (n > 0) {
                coarseValid[y * cw + x] = true
                coarse[y * cw + x] = 0xFF000000.toInt() or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        val filled = pushPull(coarse, coarseValid, cw, ch)
        val out = pixels.copyOf()
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (valid[i]) continue
            val sx = ((x - .5f) * .5f).coerceIn(0f, (cw - 1).toFloat())
            val sy = ((y - .5f) * .5f).coerceIn(0f, (ch - 1).toFloat())
            val x0 = sx.toInt(); val y0 = sy.toInt()
            val x1 = minOf(x0 + 1, cw - 1); val y1 = minOf(y0 + 1, ch - 1)
            val fx = sx - x0; val fy = sy - y0
            fun channel(shift: Int): Int {
                fun v(xx: Int, yy: Int) = (filled[yy * cw + xx] shr shift) and 255
                return (((v(x0, y0) * (1 - fx) + v(x1, y0) * fx) * (1 - fy)) +
                    ((v(x0, y1) * (1 - fx) + v(x1, y1) * fx) * fy)).roundToInt().coerceIn(0, 255)
            }
            out[i] = 0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return out
    }

    private fun otsuThreshold(values: IntArray): Int {
        val histogram = IntArray(256)
        for (value in values) histogram[value]++
        val total = values.size.toFloat()
        var sum = 0f
        for (i in histogram.indices) sum += i * histogram[i]
        var leftCount = 0f
        var leftSum = 0f
        var bestVariance = -1f
        var threshold = 127
        for (i in histogram.indices) {
            leftCount += histogram[i]
            if (leftCount == 0f) continue
            val rightCount = total - leftCount
            if (rightCount == 0f) break
            leftSum += i * histogram[i]
            val delta = leftSum / leftCount - (sum - leftSum) / rightCount
            val variance = leftCount * rightCount * delta * delta
            if (variance > bestVariance) {
                bestVariance = variance
                threshold = i
            }
        }
        return threshold
    }

    private const val MIN_DEPTH_RANGE = 24
}
