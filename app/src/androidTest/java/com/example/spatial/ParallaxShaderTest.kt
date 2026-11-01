package com.example.spatial

import android.opengl.EGL14
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.spatial.gl.ParallaxShaders
import com.example.spatial.gl.ShaderHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Render the production shader, including its GLES 2 compilation, in an offscreen context. */
@RunWith(AndroidJUnit4::class)
class ParallaxShaderTest {
    @Test
    fun flatDepthAndDisabledParallaxPreservePhotoAtExtremeOffsets() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        ), 0, configs, 0, 1, count, 0))
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 32, EGL14.EGL_HEIGHT, 32, EGL14.EGL_NONE), 0)
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            val program = ShaderHelper.createProgram(ParallaxShaders.VERTEX, ParallaxShaders.FRAGMENT)
            assertTrue("Production shader must compile and link", program != 0)
            GLES20.glUseProgram(program)
            fun uniform(name: String, value: Float) =
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, name), value)
            val textures = IntArray(4)
            GLES20.glGenTextures(4, textures, 0)
            fun upload(unit: Int, depth: Int = 0, farDepth: Int = depth) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[unit])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val pixels = ByteBuffer.allocateDirect(32 * 32 * 4)
                repeat(32) { y -> repeat(32) { x ->
                    val rgba = when (unit) {
                        0 -> intArrayOf(x * 8, y * 8, 80, 255)
                        1 -> intArrayOf(depth, farDepth, depth, 255)
                        2 -> intArrayOf(x * 8, 255, 255, 255)
                        else -> intArrayOf(0, 0, 0, 0)
                    }
                    rgba.forEach { pixels.put(it.toByte()) }
                } }
                pixels.position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 32, 32, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            }
            arrayOf("image", "uDepthMap", "uInpaint", "uForegroundMask").forEachIndexed { i, name ->
                upload(i)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), i)
            }
            uniform("uFill", 1f)
            uniform("uZoom", 1f)
            uniform("uScaleType", 1f)
            uniform("uDepthHeight", 0.19f)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uColor"), 1f, 1f, 1f, 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uCamera"), 0f, 0f)
            val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            quad.put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            val position = GLES20.glGetAttribLocation(program, "position")
            val texture = GLES20.glGetAttribLocation(program, "textureST")
            quad.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
            quad.position(2)
            GLES20.glVertexAttribPointer(texture, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(texture)
            GLES20.glViewport(0, 0, 32, 32)
            fun render(): ByteBuffer {
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                val result = ByteBuffer.allocateDirect(32 * 32 * 4)
                GLES20.glReadPixels(0, 0, 32, 32, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, result)
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                return result
            }
            for (aspect in floatArrayOf(0.5f, 1f, 2f)) {
                upload(3)
                uniform("uAspectRatio", aspect)
                uniform("uImageAspect", aspect)
                uniform("uStrength", 0f)
                var result = render()
                for (i in 0 until 32 * 32) {
                    assertEquals("Disabled parallax must bypass inpainting", 80, result.get(i * 4 + 2).toInt() and 255)
                }
                // An opaque mask makes composition equal the original photo. With a constant
                // depth at the neutral plane the ray must leave every pixel motionless.
                GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
                val opaque = ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1))
                opaque.position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, opaque)
                uniform("uStrength", 1f)
                for (depth in intArrayOf(0, 128, 255)) {
                    upload(1, depth)
                    uniform("uNeutral", depth / 255f)
                    for (offset in floatArrayOf(-1f, 1f)) {
                        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOffset"), offset, offset)
                        result = render()
                        for (y in 0 until 32) for (x in 0 until 32) {
                            val i = (y * 32 + x) * 4
                            assertTrue("Flat depth must preserve x=$x y=$y", kotlin.math.abs((result.get(i).toInt() and 255) - x * 8) <= 1)
                            assertTrue(kotlin.math.abs((result.get(i + 1).toInt() and 255) - y * 8) <= 1)
                        }
                    }
                }
            }
            // A moving surface can leave the photo bounds. Show background there rather
            // than clamping texels or bending the image to keep the border stationary.
            uniform("uAspectRatio", 1f)
            uniform("uImageAspect", 1f)
            uniform("uNeutral", 0f)
            upload(1, 255)
            for (offset in floatArrayOf(-1f, 1f)) {
                GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOffset"), offset, 0f)
                val result = render()
                val x = if (offset > 0f) 2 else 29
                val red = result.get((16 * 32 + x) * 4).toInt() and 255
                assertEquals("Outside source must use the configured background", 0, red)
            }
            // With the foreground plane stationary, the far layer still moves independently.
            // A zero mask exposes the synthetic inpaint gradient, without source-photo colours.
            upload(3)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOffset"), 1f, 0f)
            upload(1, 255, 0)
            uniform("uNeutral", 1f)
            val separated = render()
            val center = (16 * 32 + 16) * 4
            assertTrue("Background must use its own projection", (separated.get(center).toInt() and 255) > 128)
            assertEquals(255, separated.get(center + 1).toInt() and 255)
            assertEquals(255, separated.get(center + 2).toInt() and 255)
            GLES20.glDeleteTextures(4, textures, 0)
            GLES20.glDeleteProgram(program)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
