package com.example.spatial.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.hypot

/**
 * Runs the bundled UINT8 RGB/NHWC depth model, with a heuristic fallback on inference failure.
 */
class DepthEstimationManager(private val context: Context) {

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isModelLoaded = false

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            // A distinct cache key prevents older FLOAT/NCHW models surviving an app update.
            val modelPath = copyAssetToInternalStorage("d_models/model_256.onnx", "model_256_uint8_nhwc_v1.onnx")
            if (modelPath != null && File(modelPath).exists()) {
                OrtSession.SessionOptions().use { opts ->
                    ortSession = ortEnvironment?.createSession(modelPath, opts)
                }
                isModelLoaded = ortSession != null
                Log.d(TAG, "ONNX model_256.onnx loaded successfully")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ONNX model load failed, fallback will be used: ${e.message}")
            isModelLoaded = false
        }
    }

    private fun copyAssetToInternalStorage(assetPath: String, outputName: String): String? {
        val outFile = File(context.filesDir, outputName)
        if (outFile.exists() && outFile.length() > 1000) {
            return outFile.absolutePath
        }
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying asset $assetPath", e)
            null
        }
    }

    /**
     * Estimates 504x504 depth map (bytes 0..255) for the given input image.
     */
    suspend fun estimateDepth(bitmap: Bitmap, onProgress: (Float) -> Unit): ByteArray = withContext(Dispatchers.Default) {
        onProgress(0.1f)

        // Try ONNX first if loaded
        if (isModelLoaded && ortSession != null && ortEnvironment != null) {
            try {
                onProgress(0.25f)
                val depth = runOnnxInference(bitmap)
                if (depth != null) {
                    onProgress(1.0f)
                    return@withContext depth
                }
            } catch (e: Exception) {
                Log.e(TAG, "ONNX inference error, switching to fast fallback", e)
            }
        }

        // Fast high-quality gradient / focal depth map fallback
        onProgress(0.5f)
        val fallbackDepth = generateFallbackDepthMap(bitmap)
        onProgress(1.0f)
        fallbackDepth
    }

    private fun runOnnxInference(bitmap: Bitmap): ByteArray? {
        val session = ortSession ?: return null
        val env = ortEnvironment ?: return null

        val input = session.inputInfo.entries.single()
        val info = input.value.info as? TensorInfo ?: error("Depth input must be a tensor")
        require(info.type == OnnxJavaType.UINT8 && info.shape.contentEquals(longArrayOf(1, 256, 256, 3))) {
            "Unsupported depth input: ${info.type} ${info.shape.contentToString()}"
        }
        // The bundled graph performs NHWC->NCHW, /255 and ImageNet normalization itself.
        // Channel order is RGB, interleaved bytes, with no external normalization or inversion.
        val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val pixels = IntArray(256 * 256)
        try {
            scaled.getPixels(pixels, 0, 256, 0, 0, 256, 256)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        val bytes = DepthPreprocess.rgbNhwc(pixels)
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).rewind()
        OnnxTensor.createTensor(env, buffer, info.shape, OnnxJavaType.UINT8).use { tensor ->
            session.run(Collections.singletonMap(input.key, tensor)).use { results ->
                val output = results.get(0) as? OnnxTensor ?: error("Depth output must be a tensor")
                val outputInfo = output.info
                val shape = outputInfo.shape
                require(outputInfo.type == OnnxJavaType.UINT8 && shape.size == 3 && shape[0] == 1L &&
                    shape[1] > 0 && shape[2] > 0) { "Unsupported depth output: $outputInfo" }
                val height = shape[1].toInt(); val width = shape[2].toInt()
                val out = output.byteBuffer ?: error("Missing UINT8 depth buffer")
                require(out.remaining() == width * height)
                val values = ByteArray(out.remaining())
                out.get(values)
                // Output is already normalized to 0..255 by the graph; retain its polarity.
                return DepthPreprocess.resizeBilinear(values, width, height, 504, 504)
            }
        }
    }

    /**
     * Standalone depth map generator using saliency, focal plane, and radial depth gradients.
     * Produces clean, artifact-free 504x504 depth maps instantly.
     */
    private fun generateFallbackDepthMap(bitmap: Bitmap): ByteArray {
        val targetSize = 504
        val scaled = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        val pixels = IntArray(targetSize * targetSize)
        scaled.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        val depthBytes = ByteArray(targetSize * targetSize)
        val centerX = targetSize / 2.0f
        val centerY = targetSize * 0.45f
        val maxRadius = targetSize * 0.5f

        for (y in 0 until targetSize) {
            val normY = y.toFloat() / targetSize.toFloat()
            // Natural perspective: ground plane gets closer towards bottom, sky/horizon further at top
            val groundDepth = (1.0f - normY) * 160.0f

            for (x in 0 until targetSize) {
                val pixel = pixels[y * targetSize + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val luminance = (0.299f * r + 0.587f * g + 0.114f * b)

                // Distance from center focal subject
                val dist = hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat()
                val focalFactor = (1.0f - (dist / maxRadius)).coerceIn(0.0f, 1.0f)

                // Foreground subject has higher depth
                val depthVal = (groundDepth * 0.4f + focalFactor * 90.0f + (luminance / 255.0f) * 45.0f)
                    .toInt()
                    .coerceIn(10, 250)

                depthBytes[y * targetSize + x] = depthVal.toByte()
            }
        }
        return depthBytes
    }

    fun close() {
        try {
            ortSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX session", e)
        }
    }

    companion object {
        private const val TAG = "DepthEstimationManager"
    }
}
