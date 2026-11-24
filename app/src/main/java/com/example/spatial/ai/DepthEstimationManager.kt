package com.example.spatial.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.spatial.ai.DepthPreprocess.DepthModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.hypot

/**
 * Runs the bundled UINT8 RGB/NHWC depth models (`model_512.onnx` / `model_256.onnx`) with adaptive
 * device selection matching Photos 3D 1.1.6, and a heuristic fallback on inference failure.
 */
class DepthEstimationManager(private val context: Context) {

    @Volatile
    private var ortEnvironment: OrtEnvironment? = null
    @Volatile
    private var ortSession: OrtSession? = null
    @Volatile
    private var isModelLoaded = false
    @Volatile
    private var isClosed = false
    @Volatile
    var activeModelSpec: DepthModelSpec = DepthModelSpec.SMALL
        private set
    private val initMutex = Mutex()
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun hasSufficientMemoryForLargeModel(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        if (am.isLowRamDevice) return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // Prefer 512x512 model on devices with >= 3 GB total RAM (matches Photos 3D device tiering).
        return info.totalMem >= 3L * 1024L * 1024L * 1024L
    }

    private fun chooseModelSpec(): DepthModelSpec {
        val savedType = prefs.getString(KEY_MODEL_TYPE, null)
        val avgDuration = prefs.getLong(KEY_AVG_DURATION, 0L)
        val runCount = prefs.getLong(KEY_RUN_COUNT, 0L)
        val decision = DepthPreprocess.selectAdaptiveModel(
            savedType = savedType,
            avgDurationMs = avgDuration,
            runCount = runCount,
            sdkInt = Build.VERSION.SDK_INT,
            preferLarge = hasSufficientMemoryForLargeModel()
        )
        val editor = prefs.edit()
        if (decision.shouldResetStats) {
            editor.putLong(KEY_AVG_DURATION, 0L).putLong(KEY_RUN_COUNT, 0L)
        }
        if (savedType != decision.spec.code) {
            editor.putString(KEY_MODEL_TYPE, decision.spec.code)
        }
        editor.apply()
        return decision.spec
    }

    private fun recordInferenceDuration(durationMs: Long) {
        if (durationMs <= 0L) return
        val prevCount = prefs.getLong(KEY_RUN_COUNT, 0L).coerceAtLeast(0L)
        val prevAvg = prefs.getLong(KEY_AVG_DURATION, 0L).coerceAtLeast(0L)
        val newCount = prevCount + 1L
        val newAvg = ((prevAvg * prevCount) + durationMs) / newCount
        prefs.edit()
            .putLong(KEY_RUN_COUNT, newCount)
            .putLong(KEY_AVG_DURATION, newAvg)
            .apply()
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (isModelLoaded || isClosed) return@withLock
            val preferred = chooseModelSpec()
            val candidates = if (preferred == DepthModelSpec.LARGE) {
                listOf(DepthModelSpec.LARGE, DepthModelSpec.SMALL)
            } else {
                listOf(DepthModelSpec.SMALL)
            }
            for (spec in candidates) {
                if (tryLoadModel(spec)) {
                    activeModelSpec = spec
                    return@withLock
                }
            }
            isModelLoaded = false
        }
    }

    private fun tryLoadModel(spec: DepthModelSpec): Boolean {
        return try {
            val env = OrtEnvironment.getEnvironment()
            ortEnvironment = env
            val modelPath = copyAssetToInternalStorage(spec.assetPath, spec.cacheFileName)
            if (modelPath != null && File(modelPath).exists()) {
                OrtSession.SessionOptions().use { opts ->
                    // Enable XNNPACK EP for faster mobile CPU inference and proper runtime FP16
                    // feature detection (avoids MLAS FP16 SIGILL on ARMv8.0-A CPUs without FEAT_FP16).
                    try {
                        opts.addXnnpack(emptyMap())
                    } catch (e: Throwable) {
                        Log.w(TAG, "XNNPACK EP unavailable: ${e.message}")
                    }
                    ortSession = env.createSession(modelPath, opts)
                }
                isModelLoaded = ortSession != null
                if (isModelLoaded) {
                    Log.d(TAG, "ONNX ${spec.assetPath} loaded successfully (input=${spec.inputSize}, out=${spec.outputSize})")
                }
                isModelLoaded
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ONNX ${spec.assetPath} load failed: ${e.message}")
            ortSession = null
            isModelLoaded = false
            false
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

        // Ensure ONNX initialization finishes before falling back (e.g. when launched via ACTION_SEND).
        if (!isModelLoaded && !isClosed) {
            initialize()
        }

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
        val spec = activeModelSpec

        val input = session.inputInfo.entries.single()
        val info = input.value.info as? TensorInfo ?: error("Depth input must be a tensor")
        val inputSize = spec.inputSize
        val contentSize = spec.outputSize
        require(
            info.type == OnnxJavaType.UINT8 &&
                info.shape.contentEquals(longArrayOf(1, inputSize.toLong(), inputSize.toLong(), 3))
        ) {
            "Unsupported depth input: ${info.type} ${info.shape.contentToString()} (expected $inputSize)"
        }

        // Match Photos 3D 1.1.6 (`zp2.f` + `hl0.doInBackground`): scale image to contentSize x contentSize
        // (504 or 252), stream directly into a direct ByteBuffer with black right/bottom margin to avoid
        // multi-megabyte intermediate arrays, and preserve full 504x504 native ONNX output detail.
        val startMs = SystemClock.uptimeMillis()
        val scaled = Bitmap.createScaledBitmap(bitmap, contentSize, contentSize, true)
        val rowPixels = IntArray(contentSize)
        val rowBytes = ByteArray(inputSize * 3)
        val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        try {
            for (y in 0 until contentSize) {
                scaled.getPixels(rowPixels, 0, contentSize, 0, y, contentSize, 1)
                for (x in 0 until contentSize) {
                    val px = rowPixels[x]
                    val idx = x * 3
                    rowBytes[idx] = (px shr 16).toByte()
                    rowBytes[idx + 1] = (px shr 8).toByte()
                    rowBytes[idx + 2] = px.toByte()
                }
                // Right margin bytes [contentSize*3 .. inputSize*3) stay 0 (black)
                buffer.put(rowBytes)
            }
            // Bottom margin rows [contentSize .. inputSize) stay 0 (black)
            rowBytes.fill(0)
            for (y in contentSize until inputSize) {
                buffer.put(rowBytes)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        buffer.rewind()
        OnnxTensor.createTensor(env, buffer, info.shape, OnnxJavaType.UINT8).use { tensor ->
            session.run(Collections.singletonMap(input.key, tensor)).use { results ->
                val output = results.get(0) as? OnnxTensor ?: error("Depth output must be a tensor")
                val outputInfo = output.info
                val shape = outputInfo.shape
                require(
                    outputInfo.type == OnnxJavaType.UINT8 && shape.size == 3 && shape[0] == 1L &&
                        shape[1] > 0 && shape[2] > 0
                ) { "Unsupported depth output: $outputInfo" }
                val height = shape[1].toInt()
                val width = shape[2].toInt()
                val out = output.byteBuffer ?: error("Missing UINT8 depth buffer")
                require(out.remaining() == width * height)
                val values = ByteArray(out.remaining())
                out.get(values)
                recordInferenceDuration(SystemClock.uptimeMillis() - startMs)

                // Match Photos 3D 1.1.6 `hl0.doInBackground`: keep native 504x504 model_512 output crisp
                // without 1/4-resolution downsampling blur; only upsample model_256 (252x252 -> 504x504).
                return if (width == 504 && height == 504) {
                    values
                } else {
                    DepthPreprocess.resizeBilinear(values, width, height, 504, 504)
                }
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
        try {
            scaled.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }

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
        isClosed = true
        isModelLoaded = false
        try {
            // Only close the session; OrtEnvironment is a process-wide singleton.
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX session", e)
        }
    }

    companion object {
        private const val TAG = "DepthEstimationManager"
        private const val PREFS_NAME = "spatial_depth_model_prefs"
        private const val KEY_MODEL_TYPE = "da_model_type"
        private const val KEY_AVG_DURATION = "da_avg_duration"
        private const val KEY_RUN_COUNT = "da_count"
    }
}
