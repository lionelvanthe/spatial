package com.example.spatial.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.example.spatial.data.SpatialSceneConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure-Kotlin GIF89a streaming encoder with a 256-color palette (6x6x6 RGB cube + 40-step
 * grayscale ramp), 4x4 Bayer ordered dithering, NETSCAPE2.0 infinite looping, and standard
 * variable-width LZW compression. Free of Android dependencies so JVM unit tests can verify it.
 */
class AnimatedGifEncoder(
    private val width: Int,
    private val height: Int,
    private val output: OutputStream
) {
    private val paletteR = IntArray(256)
    private val paletteG = IntArray(256)
    private val paletteB = IntArray(256)
    private var headerWritten = false

    init {
        require(width in 1..65535 && height in 1..65535) { "Invalid GIF dimensions: ${width}x${height}" }
        // 0..215: 6x6x6 uniform RGB cube (levels 0, 51, 102, 153, 204, 255)
        var idx = 0
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    paletteR[idx] = r * 51
                    paletteG[idx] = g * 51
                    paletteB[idx] = b * 51
                    idx++
                }
            }
        }
        // 216..255: 40-step neutral grayscale ramp for smooth skin/sky/shadow gradients
        for (i in 0 until 40) {
            val v = ((i + 1) * 255f / 41f).roundToInt().coerceIn(0, 255)
            paletteR[idx] = v
            paletteG[idx] = v
            paletteB[idx] = v
            idx++
        }
    }

    private fun writeHeader() {
        if (headerWritten) return
        headerWritten = true
        // Header: GIF89a
        output.write(byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte()))
        // Logical Screen Descriptor: width, height, GCT flag (1) + color res (7) + sort (0) + size (7 => 256 colors)
        writeShortLE(width)
        writeShortLE(height)
        output.write(0xF7)
        output.write(0) // background color index
        output.write(0) // pixel aspect ratio
        // Global Color Table (256 * 3 bytes)
        val gct = ByteArray(256 * 3)
        for (i in 0 until 256) {
            gct[i * 3] = paletteR[i].toByte()
            gct[i * 3 + 1] = paletteG[i].toByte()
            gct[i * 3 + 2] = paletteB[i].toByte()
        }
        output.write(gct)
        // NETSCAPE2.0 Application Extension for infinite loop
        output.write(
            byteArrayOf(
                0x21, 0xFF.toByte(), 0x0B,
                'N'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(),
                'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte(),
                '2'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(),
                0x03, 0x01, 0x00, 0x00, 0x00
            )
        )
    }

    /**
     * Adds one top-to-bottom ARGB frame with [delayCs] centiseconds delay (e.g. 7 = ~14.3 fps).
     */
    fun addFrame(argbPixels: IntArray, delayCs: Int = 7) {
        require(argbPixels.size == width * height) { "Frame pixel count mismatch" }
        writeHeader()
        // Graphic Control Extension
        output.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00))
        writeShortLE(delayCs.coerceIn(2, 65535))
        output.write(byteArrayOf(0x00, 0x00))
        // Image Descriptor (full frame, no local color table)
        output.write(0x2C)
        writeShortLE(0)
        writeShortLE(0)
        writeShortLE(width)
        writeShortLE(height)
        output.write(0x00)

        // Quantize to 256-color palette with 4x4 Bayer ordered dithering
        val indices = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            val bayerRow = (y and 3) shl 2
            for (x in 0 until width) {
                val p = argbPixels[row + x]
                val dither = BAYER_4X4[bayerRow + (x and 3)]
                val r = ((p ushr 16) and 0xFF) + dither
                val g = ((p ushr 8) and 0xFF) + dither
                val b = (p and 0xFF) + dither
                indices[row + x] = mapRgbToPalette(r, g, b).toByte()
            }
        }
        writeLzwData(indices)
    }

    fun finish() {
        writeHeader()
        output.write(0x3B) // GIF Trailer
        output.flush()
    }

    private fun mapRgbToPalette(rIn: Int, gIn: Int, bIn: Int): Int {
        val r = rIn.coerceIn(0, 255)
        val g = gIn.coerceIn(0, 255)
        val b = bIn.coerceIn(0, 255)
        val ri = (r * 5 + 127) / 255
        val gi = (g * 5 + 127) / 255
        val bi = (b * 5 + 127) / 255
        val cubeIdx = ri * 36 + gi * 6 + bi

        val dr = r - paletteR[cubeIdx]
        val dg = g - paletteG[cubeIdx]
        val db = b - paletteB[cubeIdx]
        val cubeErr = dr * dr + dg * dg + db * db

        // Check grayscale ramp when pixel is near-neutral
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        if (maxC - minC <= 24) {
            val luma = (77 * r + 150 * g + 29 * b) ushr 8
            val grayStep = ((luma * 41f / 255f).roundToInt() - 1).coerceIn(0, 39)
            val grayIdx = 216 + grayStep
            val gv = paletteR[grayIdx]
            val gr = r - gv
            val gg = g - gv
            val gb = b - gv
            if (gr * gr + gg * gg + gb * gb < cubeErr) {
                return grayIdx
            }
        }
        return cubeIdx
    }

    private fun writeLzwData(indices: ByteArray) {
        val minCodeSize = 8
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        output.write(minCodeSize)

        val hashKeys = IntArray(HASH_SIZE)
        val hashVals = IntArray(HASH_SIZE)
        val subBlock = ByteArray(256)
        var subBlockLen = 0
        var bitBuf = 0
        var bitCount = 0

        fun flushSubBlock() {
            if (subBlockLen > 0) {
                output.write(subBlockLen)
                output.write(subBlock, 0, subBlockLen)
                subBlockLen = 0
            }
        }

        fun emitCode(code: Int, codeSize: Int) {
            bitBuf = bitBuf or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                subBlock[subBlockLen++] = (bitBuf and 0xFF).toByte()
                if (subBlockLen == 255) flushSubBlock()
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1
        var maxCode = (1 shl codeSize) - 1
        hashKeys.fill(-1)

        emitCode(clearCode, codeSize)
        if (indices.isNotEmpty()) {
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                var slot = ((k shl 4) xor prefix) % HASH_SIZE
                if (slot < 0) slot += HASH_SIZE
                var found = -1
                while (hashKeys[slot] != -1) {
                    if (hashKeys[slot] == key) {
                        found = hashVals[slot]
                        break
                    }
                    slot = (slot + 1) % HASH_SIZE
                }
                if (found != -1) {
                    prefix = found
                } else {
                    emitCode(prefix, codeSize)
                    if (nextCode < 4096) {
                        hashKeys[slot] = key
                        hashVals[slot] = nextCode++
                        if (nextCode > maxCode + 1 && codeSize < 12) {
                            codeSize++
                            maxCode = (1 shl codeSize) - 1
                        }
                    } else {
                        emitCode(clearCode, codeSize)
                        hashKeys.fill(-1)
                        codeSize = minCodeSize + 1
                        nextCode = eoiCode + 1
                        maxCode = (1 shl codeSize) - 1
                    }
                    prefix = k
                }
            }
            emitCode(prefix, codeSize)
        }
        emitCode(eoiCode, codeSize)
        if (bitCount > 0) {
            subBlock[subBlockLen++] = (bitBuf and 0xFF).toByte()
        }
        flushSubBlock()
        output.write(0) // Block terminator
    }

    private fun writeShortLE(value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private companion object {
        const val HASH_SIZE = 5003
        // Centered 4x4 Bayer dither offsets in [-16..+15] (scaled for 6-level 51-step cube)
        val BAYER_4X4 = intArrayOf(
            -16,   0, -12,   4,
              8,  -8,  12,  -4,
            -10,   6, -14,   2,
             14,  -2,  10,  -6
        )
    }
}

/**
 * Renders the Animations tab offscreen on an EGL Pbuffer and encodes a seamlessly looping GIF89a.
 */
class GifExporter {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    suspend fun export(
        image: Bitmap,
        depth: ByteArray?,
        depthWidth: Int,
        depthHeight: Int,
        config: SpatialSceneConfig,
        output: File,
        onProgress: (Float) -> Unit
    ) {
        val eglExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SpatialGifExportEGL")
        }
        val eglDispatcher = eglExecutor.asCoroutineDispatcher()
        try {
            withContext(eglDispatcher) {
                exportOnCurrentThread(image, depth, depthWidth, depthHeight, config, output, onProgress)
            }
        } finally {
            eglDispatcher.close()
            eglExecutor.shutdown()
        }
    }

    private suspend fun exportOnCurrentThread(
        image: Bitmap,
        depth: ByteArray?,
        depthWidth: Int,
        depthHeight: Int,
        config: SpatialSceneConfig,
        output: File,
        onProgress: (Float) -> Unit
    ) {
        val cfg = config.sanitized()
        val patternId = if (cfg.patternId.equals("none", ignoreCase = true)) "circular_cw" else cfg.patternId
        val (width, height) = gifSize(image.width, image.height, MAX_SIDE)
        val cycleSec = LoopPlan.cycleMs(cfg.speed) / 1000f * MotionPattern.periodCycles(patternId)
        val totalFrames = (cycleSec * GIF_FPS).roundToInt().coerceIn(15, MAX_FRAMES)
        val delayCs = (cycleSec * 100f / totalFrames).roundToInt().coerceIn(4, 15)

        output.parentFile?.mkdirs()
        output.delete()

        var done = false
        try {
            setUpPbufferEgl(width, height)
            val renderer = OffscreenMeshRenderer(image, depth, depthWidth, depthHeight, width, height)
            val rgbaBuf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            val rgbaBytes = ByteArray(width * height * 4)
            val argbPixels = IntArray(width * height)

            try {
                BufferedOutputStream(FileOutputStream(output), 64 * 1024).use { bos ->
                    val encoder = AnimatedGifEncoder(width, height, bos)
                    for (frame in 0 until totalFrames) {
                        coroutineContext.ensureActive()
                        val progress = frame.toFloat() / totalFrames.toFloat()
                        renderer.draw(cfg, patternId, progress)
                        GLES20.glFinish()

                        rgbaBuf.rewind()
                        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBuf)
                        rgbaBuf.rewind()
                        rgbaBuf.get(rgbaBytes)

                        // Flip vertically from OpenGL bottom-left origin to GIF top-left origin
                        for (y in 0 until height) {
                            val srcRow = (height - 1 - y) * width * 4
                            val dstRow = y * width
                            for (x in 0 until width) {
                                val p = srcRow + x * 4
                                val r = rgbaBytes[p].toInt() and 0xFF
                                val g = rgbaBytes[p + 1].toInt() and 0xFF
                                val b = rgbaBytes[p + 2].toInt() and 0xFF
                                argbPixels[dstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                        encoder.addFrame(argbPixels, delayCs)
                        onProgress((frame + 1f) / totalFrames)
                    }
                    encoder.finish()
                }
            } finally {
                renderer.release()
            }
            done = true
        } finally {
            tearDownEgl()
            if (!done) output.delete()
        }
    }

    private fun setUpPbufferEgl(width: Int, height: Int) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL init failed" }
        val attribs = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "No EGL Pbuffer config"
        }
        val config = configs[0]!!
        eglContext = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context failed" }
        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL pbuffer surface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun tearDownEgl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private class OffscreenMeshRenderer(
        image: Bitmap,
        depth: ByteArray?,
        depthWidth: Int,
        depthHeight: Int,
        private val width: Int,
        private val height: Int
    ) {
        private val modelBridge = SpatialModelBridge()
        private val program = ShaderHelper.createProgram(MESH_VERT, MESH_FRAG)
        private val textureIds = IntArray(1)
        private val vboIds = IntArray(2)
        private val uViewProjectionMatrix: Int
        private val uModelMatrix: Int
        private val uSampler: Int
        private val aPosition: Int
        private val aTextureST: Int
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val viewProjectionMatrix = FloatArray(16)
        private val modelMatrix = FloatArray(16)

        init {
            check(program != 0) { "Shader compilation failed" }
            uViewProjectionMatrix = GLES20.glGetUniformLocation(program, "uViewProjectionMatrix")
            uModelMatrix = GLES20.glGetUniformLocation(program, "uModelMatrix")
            uSampler = GLES20.glGetUniformLocation(program, "uSampler")
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureST = GLES20.glGetAttribLocation(program, "aTextureST")

            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glGenTextures(1, textureIds, 0)
            GLES20.glGenBuffers(2, vboIds, 0)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image, 0)

            val imageAspect = image.width.toFloat() / image.height.coerceAtLeast(1)
            val depthBytes = depth ?: ByteArray(16) { 128.toByte() }
            val dw = if (depth != null) depthWidth else 4
            val dh = if (depth != null) depthHeight else 4
            check(modelBridge.initializeMesh(depthBytes, dw, dh, imageAspect, gridSize = 96)) {
                "Failed to initialize native mesh for GIF export"
            }

            val ib = modelBridge.indexBuffer
            if (ib != null) {
                ib.position(0)
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, modelBridge.indexCount * 2, ib, GLES20.GL_STATIC_DRAW)
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, modelBridge.vertexCount * 5 * 4, null, GLES20.GL_DYNAMIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

            android.opengl.Matrix.setIdentityM(viewMatrix, 0)
            val viewAspect = width.toFloat() / height.coerceAtLeast(1)
            if (viewAspect <= 1.0f) {
                android.opengl.Matrix.frustumM(projectionMatrix, 0, -viewAspect, viewAspect, -1f, 1f, 2.0f, 12.0f)
            } else {
                val invAspect = 1f / viewAspect
                android.opengl.Matrix.frustumM(projectionMatrix, 0, -1f, 1f, -invAspect, invAspect, 2.0f, 12.0f)
            }
            android.opengl.Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            val baseFitScale = if (viewAspect <= 1.0f) {
                if (imageAspect > viewAspect) (viewAspect * 3.5f) / imageAspect else 3.5f
            } else {
                if (imageAspect > viewAspect) 3.5f / imageAspect else 3.5f / viewAspect
            }
            val fitScale = baseFitScale * 1.075f
            android.opengl.Matrix.setIdentityM(modelMatrix, 0)
            android.opengl.Matrix.translateM(modelMatrix, 0, 0f, 0f, -7.0f)
            android.opengl.Matrix.scaleM(modelMatrix, 0, fitScale, fitScale, fitScale)
        }

        fun draw(config: SpatialSceneConfig, patternId: String, progress: Float) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0.06f, 0.06f, 0.09f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            modelBridge.setProperties(
                config.intensity,
                config.depthCenter,
                config.rInverted,
                config.zoom,
                config.zoom3D,
                config.elasticity
            )
            modelBridge.updateAnimated(progress, MotionPattern.patternIdToNativeInt(patternId))

            val vb = modelBridge.vertexBuffer ?: return
            vb.position(0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uViewProjectionMatrix, 1, false, viewProjectionMatrix, 0)
            GLES20.glUniformMatrix4fv(uModelMatrix, 1, false, modelMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
            GLES20.glUniform1i(uSampler, 0)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, modelBridge.vertexCount * 5 * 4, vb)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 20, 0)
            GLES20.glEnableVertexAttribArray(aTextureST)
            GLES20.glVertexAttribPointer(aTextureST, 2, GLES20.GL_FLOAT, false, 20, 12)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, modelBridge.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTextureST)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        }

        fun release() {
            modelBridge.destroy()
            GLES20.glDeleteBuffers(2, vboIds, 0)
            GLES20.glDeleteTextures(1, textureIds, 0)
            GLES20.glDeleteProgram(program)
        }
    }

    companion object {
        const val MAX_SIDE = 480
        const val GIF_FPS = 15
        const val MAX_FRAMES = 90

        fun gifSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
            val safeW = width.coerceAtLeast(1)
            val safeH = height.coerceAtLeast(1)
            val scale = minOf(1f, maxSide.toFloat() / max(safeW, safeH))
            fun snap(v: Int) = ((v * scale / 4f).roundToInt() * 4).coerceAtLeast(16)
            return snap(safeW) to snap(safeH)
        }

        private const val MESH_VERT = """
            uniform mat4 uViewProjectionMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec2 aTextureST;
            varying vec2 vTextureCoords;
            void main() {
                gl_Position = uViewProjectionMatrix * (uModelMatrix * vec4(aPosition, 1.0));
                vTextureCoords = aTextureST;
            }
        """
        private const val MESH_FRAG = """
            precision highp float;
            uniform sampler2D uSampler;
            varying vec2 vTextureCoords;
            void main() {
                gl_FragColor = texture2D(uSampler, vTextureCoords);
            }
        """
    }
}
