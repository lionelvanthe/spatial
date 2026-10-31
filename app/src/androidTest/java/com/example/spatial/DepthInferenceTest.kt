package com.example.spatial

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatial.ai.DepthEstimationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OnnxJavaType
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DepthInferenceTest {
    @Test fun importedPortraitRunsTheBundledModelWithoutFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = DepthEstimationManager(context)
        val bitmap = context.assets.open("samples/woman_selfie/woman_selfie_img.dat").use { BitmapFactory.decodeStream(it) }
        try {
            manager.initialize()
            val sessionField = manager.javaClass.getDeclaredField("ortSession").apply { isAccessible = true }
            val session = sessionField.get(manager) as OrtSession
            val inputInfo = session.inputInfo.values.single().info as TensorInfo
            val outputInfo = session.outputInfo.values.single().info as TensorInfo
            assertEquals(OnnxJavaType.UINT8, inputInfo.type)
            assertArrayEquals(longArrayOf(1, 256, 256, 3), inputInfo.shape)
            assertEquals(OnnxJavaType.UINT8, outputInfo.type)
            // ORT shape inference resolves the graph's stale annotated 518x518 to its actual
            // output for a 256 input. Production must use runtime H/W, never sqrt or 518.
            assertEquals(3, outputInfo.shape.size)
            assertEquals(1L, outputInfo.shape[0])
            assertTrue(outputInfo.shape[1] > 0 && outputInfo.shape[2] > 0)
            // Invoke the inference stage directly: a heuristic fallback must never make this test pass.
            val method = manager.javaClass.getDeclaredMethod("runOnnxInference", android.graphics.Bitmap::class.java)
            method.isAccessible = true
            val start = android.os.SystemClock.elapsedRealtime()
            val depth = method.invoke(manager, bitmap) as ByteArray
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            assertEquals(504 * 504, depth.size)
            val values = depth.map { it.toInt() and 255 }
            assertTrue("Model output must have useful range", values.max() - values.min() > 100)
            assertArrayEquals("Public import path must use ONNX, not fallback", depth, manager.estimateDepth(bitmap) {})
            val dir = File(context.cacheDir, "parallax-validation").apply { mkdirs() }
            File(dir, "onnx_inference.txt").writeText("input=${inputInfo.type} ${inputInfo.shape.contentToString()}\nmodelOutput=${outputInfo.type} ${outputInfo.shape.contentToString()}\nsource=${bitmap.width}x${bitmap.height}\noutput=504x504\nrange=${values.min()}..${values.max()}\nelapsedMs=$elapsed\nfallback=false\n")
            val pixels = IntArray(depth.size) { val d = values[it]; android.graphics.Color.rgb(d, d, d) }
            val output = android.graphics.Bitmap.createBitmap(pixels, 504, 504, android.graphics.Bitmap.Config.ARGB_8888)
            File(dir, "imported_model_depth.png").outputStream().use { output.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            output.recycle()
        } finally {
            bitmap.recycle()
            manager.close()
        }
    }
}
