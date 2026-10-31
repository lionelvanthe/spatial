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
}
