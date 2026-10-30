package com.example.spatial.gl

/**
 * Splits an image into a far, inpainted background and a near foreground.
 *
 * A single textured height mesh has to interpolate a triangle across an occlusion edge.  That
 * interpolation stretches pixels from the subject into the background as the view moves.  These
 * two textures let the renderer move each side of the edge independently instead.
 */
object LayerBuilder {
    data class LayerData(val layers: Array<IntArray>, val depths: FloatArray)

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
            val sx = x * (depthWidth - 1) / (width - 1).coerceAtLeast(1)
            val sy = y * (depthHeight - 1) / (height - 1).coerceAtLeast(1)
            val value = depth[sy * depthWidth + sx].toInt() and 0xFF
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
        for (i in pixels.indices) {
            if (nearMask[i]) {
                foreground[i] = pixels[i]
            } else {
                foreground[i] = pixels[i] and 0x00FFFFFF
                backgroundValid[i] = true
            }
        }
        val background = pushPull(pixels.copyOf(), backgroundValid, width, height)
        return LayerData(arrayOf(background, foreground), floatArrayOf(0f, 1f))
    }

    /** Fills masked pixels from their closest available background neighbours. */
    fun pushPull(pixels: IntArray, valid: BooleanArray, width: Int, height: Int): IntArray {
        require(pixels.size == width * height && valid.size == pixels.size)
        val out = pixels.copyOf()
        val known = valid.copyOf()
        // Multi-source breadth-first fill: every masked pixel takes the colour of its closest
        // unmasked neighbour.  The former pass-based version repeatedly copied a full 2M-pixel
        // frame and made loading a photo stall for seconds.
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        for (i in known.indices) if (known[i]) queue[tail++] = i
        while (head < tail) {
            val source = queue[head++]
            val x = source % width
            fun fill(target: Int) {
                if (!known[target]) {
                    known[target] = true
                    out[target] = out[source]
                    queue[tail++] = target
                }
            }
            if (x > 0) fill(source - 1)
            if (x + 1 < width) fill(source + 1)
            if (source >= width) fill(source - width)
            if (source + width < out.size) fill(source + width)
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
