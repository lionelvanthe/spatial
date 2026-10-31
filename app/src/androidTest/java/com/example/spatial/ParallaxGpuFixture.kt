package com.example.spatial

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatial.gl.ParallaxShaders
import com.example.spatial.gl.ShaderHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Small GLES2 offscreen harness; captures the real production program, not a CPU approximation. */
internal class ParallaxGpuFixture(width: Int = 256, height: Int = 256) : AutoCloseable {
    var width = width
        private set
    var height = height
        private set
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private var surface: android.opengl.EGLSurface
    private val programs = mutableListOf<Int>()
    private val textures = IntArray(4)
    private val quad = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f,
            -1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f))
    }
    val program: Int

    init {
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        ), 0, configs, 0, 1, count, 0))
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        program = createProgram(ParallaxShaders.FRAGMENT)
        GLES20.glUseProgram(program)
        GLES20.glGenTextures(4, textures, 0)
        arrayOf("image", "uDepthMap", "uInpaint", "uForegroundMask").forEachIndexed { unit, name ->
            GLES20.glUniform1i(location(name), unit)
        }
        uniform("uAspectRatio", width.toFloat() / height)
        uniform("uImageAspect", width.toFloat() / height)
        uniform("uScaleType", 1f)
        uniform("uFill", 1f)
        uniform("uZoom", 1f)
        uniform("uNeutral", 0.5f)
        uniform("uDepthHeight", 0.06225f)
        uniform("uStrength", 1f)
        GLES20.glUniform4f(location("uColor"), 1f, 1f, 1f, 0f)
        GLES20.glUniform2f(location("uCamera"), 0f, 0f)
        val source = bitmap { x, y -> Color.rgb(x * 255 / width, y * 255 / height, 80) }
        upload(0, source)
        upload(1, bitmap { _, _ -> Color.rgb(128, 128, 128) })
        upload(2, bitmap { _, _ -> Color.MAGENTA })
        upload(3, bitmap { _, _ -> Color.TRANSPARENT })
    }

    fun bitmap(pixel: (Int, Int) -> Int): Bitmap = Bitmap.createBitmap(
        IntArray(width * height) { pixel(it % width, it / width) }, width, height, Bitmap.Config.ARGB_8888)

    fun createProgram(fragment: String): Int = ShaderHelper.createProgram(ParallaxShaders.VERTEX, fragment).also {
        assertTrue("Shader must compile and link on GLES2", it != 0)
        programs.add(it)
    }

    fun location(name: String): Int = GLES20.glGetUniformLocation(program, name)
    fun uniform(name: String, value: Float) = GLES20.glUniform1f(location(name), value)
    fun offset(x: Float, y: Float) = GLES20.glUniform2f(location("uOffset"), x, y)

    fun upload(unit: Int, bitmap: Bitmap) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[unit])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    fun render(name: String? = null, selectedProgram: Int = program): IntArray {
        GLES20.glUseProgram(selectedProgram)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DITHER)
        val position = GLES20.glGetAttribLocation(selectedProgram, "position")
        val uv = GLES20.glGetAttribLocation(selectedProgram, "textureST")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        return capture(name)
    }

    fun resizeSurface(newWidth: Int, newHeight: Int) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        val id = IntArray(1)
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, newWidth, EGL14.EGL_HEIGHT, newHeight, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        width = newWidth
        height = newHeight
    }

    /** Read only: intentionally leaves the renderer's viewport/quad/draw untouched. */
    fun capture(name: String? = null): IntArray {
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        val pixels = IntArray(width * height) { i ->
            val j = ((height - 1 - i / width) * width + i % width) * 4
            Color.argb(bytes.get(j + 3).toInt() and 255, bytes.get(j).toInt() and 255,
                bytes.get(j + 1).toInt() and 255, bytes.get(j + 2).toInt() and 255)
        }
        GLES20.glUseProgram(program)
        if (name != null) {
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "parallax-validation").apply { mkdirs() }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return pixels
    }

    fun original(name: String): IntArray {
        val originalProgram = createProgram("""
            precision highp float;
            uniform sampler2D image;
            varying vec2 v_TexCoord;
            void main() { gl_FragColor = texture2D(image, v_TexCoord); }
        """)
        GLES20.glUseProgram(originalProgram)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(originalProgram, "image"), 0)
        return render(name, originalProgram)
    }

    override fun close() {
        GLES20.glDeleteTextures(4, textures, 0)
        programs.forEach { GLES20.glDeleteProgram(it) }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}
