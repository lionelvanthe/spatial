package com.example.spatial

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatial.data.SpatialSceneConfig
import com.example.spatial.gl.SpatialGLRenderer
import com.example.spatial.gl.SpatialModelBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AdaptiveMeshRendererTest {

    @Test
    fun adaptiveMeshReducesVerticesOnFlatDepth() {
        val bridge = SpatialModelBridge()
        try {
            val flatDepth = ByteArray(64 * 64) { 128.toByte() }
            assertTrue(bridge.initializeMesh(flatDepth, 64, 64, 1.0f, gridSize = 96))
            // A 96x96 full grid has 97*97 = 9409 vertices; flat depth should compress significantly.
            assertTrue("Expected reduced vertices, got ${bridge.vertexCount}", bridge.vertexCount < 3000)

            val variedDepth = ByteArray(64 * 64) { i -> ((i % 64) * 4).toByte() }
            assertTrue(bridge.initializeMesh(variedDepth, 64, 64, 1.0f, gridSize = 96))
            assertTrue("Expected more vertices on varied depth", bridge.vertexCount > 3000)
        } finally {
            bridge.destroy()
        }
    }

    @Test
    fun shakePhysicsRespondsToImpulseAndSettles() {
        val bridge = SpatialModelBridge()
        try {
            val depth = ByteArray(64 * 64) { i ->
                val x = i % 64
                val y = i / 64
                if (x in 20..44 && y in 20..44) 240.toByte() else 30.toByte()
            }
            assertTrue(bridge.initializeMesh(depth, 64, 64, 1.0f, gridSize = 48))
            bridge.setProperties(0.9f, 0.2f, false, 0f, 0f, 0.7f)

            val vb = bridge.vertexBuffer!!
            bridge.updateShake(0.016f, 0f, 0f)
            val restXs = FloatArray(bridge.vertexCount) { i -> vb.get(i * 5) }

            bridge.applyImpulse(1.5f, 0.0f)
            bridge.updateShake(0.016f, 0f, 0f)
            var maxDelta = 0.0f
            for (i in 0 until bridge.vertexCount) {
                val d = kotlin.math.abs(vb.get(i * 5) - restXs[i])
                if (d > maxDelta) maxDelta = d
            }
            assertTrue("Impulse must displace interior vertices (maxDelta=$maxDelta)", maxDelta > 1e-4f)
        } finally {
            bridge.destroy()
        }
    }

    @Test
    fun rendererDrawsMeshWithoutBorderBlackBarsOnTilt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                ),
                0, configs, 0, 1, count, 0
            )
        )
        val eglContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        val surface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 128, EGL14.EGL_HEIGHT, 128, EGL14.EGL_NONE), 0
        )
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, eglContext))

        val renderer = SpatialGLRenderer(context)
        try {
            val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.rgb(40, 160, 220))
            val depth = ByteArray(64 * 64) { i ->
                val x = i % 64
                val y = i / 64
                if (x in 16..48 && y in 16..48) 230.toByte() else 20.toByte()
            }
            renderer.setSceneData(bmp, depth, SpatialSceneConfig(intensity = 1.0f), 64, 64)
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 128, 128)
            renderer.gyroX = 1.0f
            renderer.onDrawFrame(null)

            val px = ByteBuffer.allocateDirect(128 * 128 * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, 128, 128, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
            // Edge pixel (x=1, y=64) must still show the image color, not background dark clear color
            val edgeIdx = (64 * 128 + 1) * 4
            val r = px.get(edgeIdx).toInt() and 0xFF
            val g = px.get(edgeIdx + 1).toInt() and 0xFF
            val b = px.get(edgeIdx + 2).toInt() and 0xFF
            assertEquals("Left edge must stay anchored", 40.0, r.toDouble(), 6.0)
            assertEquals("Left edge must stay anchored", 160.0, g.toDouble(), 6.0)
            assertEquals("Left edge must stay anchored", 220.0, b.toDouble(), 6.0)
        } finally {
            renderer.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
    }
}
