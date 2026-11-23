package com.example.spatial.ai

import kotlin.math.roundToInt

/** Aspect-safe helpers for square depth-model input and rectangular output. */
object DepthPreprocess {
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    fun letterbox(width: Int, height: Int, inputSize: Int): Box {
        require(width > 0 && height > 0 && inputSize > 0)
        val scale = minOf(inputSize.toFloat() / width, inputSize.toFloat() / height)
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        return Box((inputSize - w) / 2, (inputSize - h) / 2, w, h)
    }

    fun mapToOutput(box: Box, inputSize: Int, outputSize: Int): Box {
        val scale = outputSize.toFloat() / inputSize
        return Box((box.left * scale).roundToInt(), (box.top * scale).roundToInt(),
            (box.width * scale).roundToInt(), (box.height * scale).roundToInt())
    }

    fun cropNormalized(values: FloatArray, outputSize: Int, crop: Box): ByteArray {
        require(values.size == outputSize * outputSize)
        val out = FloatArray(crop.width * crop.height)
        var p = 0
        for (y in 0 until crop.height) for (x in 0 until crop.width) {
            out[p++] = values[(crop.top + y) * outputSize + crop.left + x]
        }
        val min = out.minOrNull() ?: 0f; val max = out.maxOrNull() ?: min
        val range = (max - min).takeIf { it > 1e-6f } ?: 1f
        return ByteArray(out.size) { ((out[it] - min) / range * 255f).roundToInt().coerceIn(0, 255).toByte() }
    }

    fun fitSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height))
        return (width * scale).roundToInt() to (height * scale).roundToInt()
    }

    fun resizeBilinear(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        require(sw > 0 && sh > 0 && dw > 0 && dh > 0 && src.size == sw * sh)
        return ByteArray(dw * dh) { i ->
            val x = i % dw; val y = i / dw
            val sx = ((x + .5f) * sw / dw - .5f).coerceIn(0f, (sw - 1).toFloat())
            val sy = ((y + .5f) * sh / dh - .5f).coerceIn(0f, (sh - 1).toFloat())
            val x0 = sx.toInt(); val y0 = sy.toInt()
            val x1 = minOf(x0 + 1, sw - 1); val y1 = minOf(y0 + 1, sh - 1)
            fun d(xx: Int, yy: Int) = src[yy * sw + xx].toInt() and 255
            val fx = sx - x0; val fy = sy - y0
            ((d(x0, y0) * (1 - fx) + d(x1, y0) * fx) * (1 - fy) +
                (d(x0, y1) * (1 - fx) + d(x1, y1) * fx) * fy).roundToInt().coerceIn(0, 255).toByte()
        }
    }

    fun rgbNhwc(pixels: IntArray): ByteArray = ByteArray(pixels.size * 3).also { bytes ->
        pixels.forEachIndexed { i, pixel ->
            bytes[i * 3] = (pixel shr 16).toByte()
            bytes[i * 3 + 1] = (pixel shr 8).toByte()
            bytes[i * 3 + 2] = pixel.toByte()
        }
    }

    fun smoothForParallax(src: ByteArray, width: Int, height: Int): ByteArray {
        require(src.size == width * height)
        val min = src.minOf { it.toInt() and 0xff }; val max = src.maxOf { it.toInt() and 0xff }
        if (max == min) return ByteArray(src.size)
        val blurred = FloatArray(src.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0f; var count = 0
            for (dy in -3..3) for (dx in -3..3) {
                val xx = (x + dx).coerceIn(0, width - 1); val yy = (y + dy).coerceIn(0, height - 1)
                sum += src[yy * width + xx].toInt() and 0xff; count++
            }
            blurred[y * width + x] = sum / count
        }
        val low = blurred.minOrNull() ?: 0f; val high = blurred.maxOrNull() ?: 1f
        return ByteArray(src.size) { ((blurred[it] - low) * 255f / (high - low)).roundToInt().coerceIn(0, 255).toByte() }
    }

    /**
     * Stretches depth contrast using robust percentiles [lowPct, highPct] so isolated corner/border
     * outliers cannot compress the foreground-background depth separation.
     */
    fun stretchContrastPercentile(
        src: ByteArray,
        lowPct: Float = 0.02f,
        highPct: Float = 0.98f
    ): ByteArray {
        if (src.isEmpty()) return src
        val hist = IntArray(256)
        for (b in src) hist[b.toInt() and 0xFF]++

        val total = src.size
        val lowTarget = (total * lowPct.coerceIn(0f, 0.45f)).toInt()
        val highTarget = (total * highPct.coerceIn(0.55f, 1f)).toInt().coerceAtLeast(lowTarget + 1)

        var lowVal = 0
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= lowTarget) {
                lowVal = i
                break
            }
        }
        var highVal = 255
        acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= highTarget) {
                highVal = i
                break
            }
        }
        val range = highVal - lowVal
        if (range < 8) return src.copyOf()

        val invRange = 255f / range.toFloat()
        return ByteArray(total) { i ->
            val v = src[i].toInt() and 0xFF
            ((v - lowVal) * invRange).roundToInt().coerceIn(0, 255).toByte()
        }
    }

    /**
     * Prepares an upsampled ONNX depth map for continuous 3D triangle-mesh warping:
     * 1) Percentile contrast stretch restores full 0..255 dynamic range while ignoring outliers.
     * 2) Depth-domain bilateral smoothing (at 1/4 scale for wide spatial reach in ~15ms) welds
     *    adjacent subject parts (e.g. head/chin and neck/shoulders) into a cohesive 3D volume
     *    while preserving the large depth separation between the foreground subject and background.
     * 3) Gentle foreground gamma (0.85) reduces abrupt jumps inside the foreground without losing
     *    background parallax depth.
     * Note: AdaptiveMeshBuilder already applies mild max-window dilation at vertex sampling time,
     * so we avoid extra morphological dilation here to prevent floating-head detachment artifacts.
     */
    fun refineDepthForMesh(
        depth: ByteArray,
        width: Int,
        height: Int,
        sigmaRange: Float = 0.26f,
        bilateralRadius: Int = 5
    ): ByteArray {
        val n = width * height
        require(width > 0 && height > 0 && depth.size == n)
        val stretched = stretchContrastPercentile(depth, lowPct = 0.02f, highPct = 0.98f)

        // Run wide-radius depth-domain bilateral filter at quarter resolution (e.g. 126x126 for 504x504)
        val smallW = (width / 4).coerceAtLeast(16).coerceAtMost(width)
        val smallH = (height / 4).coerceAtLeast(16).coerceAtMost(height)
        val down = resizeBilinear(stretched, width, height, smallW, smallH)
        val filteredSmall = depthBilateralFilter(down, smallW, smallH, bilateralRadius, sigmaRange)

        // Upsample smoothly back to target resolution and apply mild anti-staircase smoothing + gamma
        val up = resizeBilinear(filteredSmall, smallW, smallH, width, height)
        val floats = FloatArray(n) { i -> (up[i].toInt() and 0xFF) / 255f }
        val scratch = FloatArray(n)
        val smooth = boxFilter(floats, width, height, 2, scratch)

        return ByteArray(n) { i ->
            val d = smooth[i].coerceIn(0f, 1f)
            val curved = Math.pow(d.toDouble(), 0.85).toFloat()
            (curved * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
    }

    /** Depth-domain bilateral filter: blends nearby depths smoothly while keeping large fg/bg cliffs. */
    private fun depthBilateralFilter(
        src: ByteArray,
        w: Int,
        h: Int,
        radius: Int,
        sigmaRange: Float
    ): ByteArray {
        val n = w * h
        val f = FloatArray(n) { i -> (src[i].toInt() and 0xFF) / 255f }
        val r = radius.coerceAtLeast(1)
        val sigmaSpatial = (r * 0.6f).coerceAtLeast(1f)
        val invTwoSigmaS2 = 1f / (2f * sigmaSpatial * sigmaSpatial)
        val invTwoSigmaR2 = 1f / (2f * sigmaRange * sigmaRange)

        val spatialW = FloatArray((2 * r + 1) * (2 * r + 1))
        for (dy in -r..r) {
            for (dx in -r..r) {
                spatialW[(dy + r) * (2 * r + 1) + (dx + r)] =
                    kotlin.math.exp(-(dx * dx + dy * dy) * invTwoSigmaS2)
            }
        }

        val out = ByteArray(n)
        val span = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val center = f[row + x]
                var sum = 0f
                var wSum = 0f
                for (dy in -r..r) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val sRow = yy * w
                    val kRow = (dy + r) * span
                    for (dx in -r..r) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val v = f[sRow + xx]
                        val diff = v - center
                        val weight = spatialW[kRow + dx + r] * kotlin.math.exp(-(diff * diff) * invTwoSigmaR2)
                        sum += v * weight
                        wSum += weight
                    }
                }
                val res = if (wSum > 1e-6f) (sum / wSum).coerceIn(0f, 1f) else center
                out[row + x] = (res * 255f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /**
     * Estimates an optimal focal/pivot plane [depthCenter] from the central subject region
     * using the median depth, keeping foreground and background shifts balanced so edges do not tear.
     */
    fun estimateDepthCenter(depth: ByteArray, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0 || depth.size < width * height) return 0.60f
        val x0 = (width * 0.25f).toInt().coerceIn(0, width - 1)
        val x1 = (width * 0.75f).toInt().coerceIn(x0 + 1, width)
        val y0 = (height * 0.20f).toInt().coerceIn(0, height - 1)
        val y1 = (height * 0.80f).toInt().coerceIn(y0 + 1, height)

        val hist = IntArray(256)
        var count = 0
        for (y in y0 until y1) {
            val row = y * width
            for (x in x0 until x1) {
                hist[depth[row + x].toInt() and 0xFF]++
                count++
            }
        }
        if (count == 0) return 0.60f
        // 50th percentile (median) balances foreground and background displacement evenly
        val target = (count * 0.50f).toInt()
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= target) {
                return (i / 255f).coerceIn(0.45f, 0.72f)
            }
        }
        return 0.60f
    }

    /**
     * Bundled ONNX depth models matching Photos 3D 1.1.6:
     * - SMALL: 256x256 input (252x252 content + 4px right/bottom black margin) -> 252x252 output.
     * - LARGE: 512x512 input (504x504 content + 8px right/bottom black margin) -> 504x504 output.
     */
    enum class DepthModelSpec(
        val code: String,
        val assetPath: String,
        val cacheFileName: String,
        val inputSize: Int,
        val outputSize: Int
    ) {
        SMALL("s", "d_models/model_256.onnx", "model_256_uint8_nhwc_v1.onnx", 256, 252),
        LARGE("l", "d_models/model_512.onnx", "model_512_uint8_nhwc_v1.onnx", 512, 504);

        val margin: Int get() = inputSize - outputSize
    }

    data class AdaptiveModelDecision(
        val spec: DepthModelSpec,
        val shouldResetStats: Boolean
    )

    /**
     * Adaptive model selection matching Photos 3D 1.1.6 (`k00.c()`):
     * - First run: LARGE on SDK >= 29 when [preferLarge] is true, otherwise SMALL.
     * - Downgrade LARGE -> SMALL after >= 2 runs if avg duration >= 5000 ms.
     * - Upgrade SMALL -> LARGE after >= 4 runs if avg duration < 2500 ms and SDK >= 28.
     */
    fun selectAdaptiveModel(
        savedType: String?,
        avgDurationMs: Long,
        runCount: Long,
        sdkInt: Int,
        preferLarge: Boolean = true
    ): AdaptiveModelDecision {
        if (savedType == null) {
            val useSmall = !preferLarge || sdkInt < 29
            return AdaptiveModelDecision(
                spec = if (useSmall) DepthModelSpec.SMALL else DepthModelSpec.LARGE,
                shouldResetStats = true
            )
        }
        if (avgDurationMs <= 0L || runCount <= 0L) {
            val useSmall = savedType == DepthModelSpec.SMALL.code
            return AdaptiveModelDecision(
                spec = if (useSmall) DepthModelSpec.SMALL else DepthModelSpec.LARGE,
                shouldResetStats = false
            )
        }
        return if (savedType == DepthModelSpec.LARGE.code) {
            if (runCount >= 2L && avgDurationMs >= 5000L) {
                AdaptiveModelDecision(DepthModelSpec.SMALL, shouldResetStats = true)
            } else {
                AdaptiveModelDecision(DepthModelSpec.LARGE, shouldResetStats = false)
            }
        } else {
            if (runCount >= 4L && avgDurationMs < 2500L && preferLarge && sdkInt >= 28) {
                AdaptiveModelDecision(DepthModelSpec.LARGE, shouldResetStats = true)
            } else {
                AdaptiveModelDecision(DepthModelSpec.SMALL, shouldResetStats = false)
            }
        }
    }

    /**
     * Places a [contentWidth]x[contentHeight] ARGB pixel array at top-left (0, 0) inside a black
     * [targetWidth]x[targetHeight] frame (matching `zp2.f` marginRight / marginBottom padding).
     */
    fun padTopLeftPixels(
        contentPixels: IntArray,
        contentWidth: Int,
        contentHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): IntArray {
        require(contentWidth in 1..targetWidth && contentHeight in 1..targetHeight)
        require(contentPixels.size == contentWidth * contentHeight)
        if (contentWidth == targetWidth && contentHeight == targetHeight) {
            return contentPixels.copyOf()
        }
        val out = IntArray(targetWidth * targetHeight) { 0xFF000000.toInt() }
        for (y in 0 until contentHeight) {
            System.arraycopy(contentPixels, y * contentWidth, out, y * targetWidth, contentWidth)
        }
        return out
    }

    /** O(N) separable 2-pass box filter with edge clamping. */
    private fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int, scratch: FloatArray): FloatArray {
        val out = FloatArray(w * h)
        val window = (2 * r + 1).toFloat()
        val invWindow = 1f / window

        // Horizontal pass: src -> scratch
        for (y in 0 until h) {
            val row = y * w
            var sum = src[row] * (r + 1)
            for (dx in 1..r) {
                sum += src[row + minOf(dx, w - 1)]
            }
            scratch[row] = sum * invWindow
            for (x in 1 until w) {
                val addIdx = minOf(x + r, w - 1)
                val subIdx = maxOf(x - r - 1, 0)
                sum += src[row + addIdx] - src[row + subIdx]
                scratch[row + x] = sum * invWindow
            }
        }

        // Vertical pass: scratch -> out
        for (x in 0 until w) {
            var sum = scratch[x] * (r + 1)
            for (dy in 1..r) {
                sum += scratch[minOf(dy, h - 1) * w + x]
            }
            out[x] = sum * invWindow
            for (y in 1 until h) {
                val addRow = minOf(y + r, h - 1) * w
                val subRow = maxOf(y - r - 1, 0) * w
                sum += scratch[addRow + x] - scratch[subRow + x]
                out[y * w + x] = sum * invWindow
            }
        }
        return out
    }
}
