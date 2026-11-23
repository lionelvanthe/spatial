package com.example.spatial.gl

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import com.example.spatial.data.SpatialSceneConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Frame schedule of an exported clip: whole pattern periods, so the video loops without a jump. */
object LoopPlan {
    const val FPS = 30
    private const val MIN_SECONDS = 3f

    fun cycleMs(speed: Float): Float = 1000.0f + (1.0f - speed.coerceIn(0f, 1f)) * 6000.0f

    fun framesPerLoop(speed: Float, patternId: String): Int =
        (cycleMs(speed) / 1000f * FPS * MotionPattern.periodCycles(patternId)).roundToInt().coerceAtLeast(1)

    fun loopCount(framesPerLoop: Int): Int =
        ceil(MIN_SECONDS * FPS / framesPerLoop).toInt().coerceAtLeast(1)

    /** Pattern phase, in radians, of [frame]. */
    fun phase(frame: Int, framesPerLoop: Int, patternId: String): Float =
        (2.0 * PI * MotionPattern.periodCycles(patternId) * (frame % framesPerLoop) / framesPerLoop).toFloat()

    /** Largest side [maxSide] at most, both sides rounded to multiples of 16 (encoder friendly). */
    fun videoSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val scale = minOf(1f, maxSide.toFloat() / max(width, height))
        fun snap(v: Int) = ((v * scale / 16f).roundToInt() * 16).coerceAtLeast(16)
        return snap(width) to snap(height)
    }
}

/**
 * Renders the Animations tab off screen with its own EGL context and encodes it to an H.264 MP4.
 * The look matches the live viewer in tab 2 (same shaders, same uniform mapping).
 */
class VideoExporter {
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
            Thread(runnable, "SpatialVideoExportEGL")
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
        // A scene without a pattern would export a still; fall back to a gentle circle.
        val patternId = if (cfg.patternId.equals("none", ignoreCase = true)) "circular_cw" else cfg.patternId
        val (width, height) = LoopPlan.videoSize(image.width, image.height, MAX_SIDE)
        val framesPerLoop = LoopPlan.framesPerLoop(cfg.speed, patternId)
        val totalFrames = framesPerLoop * LoopPlan.loopCount(framesPerLoop)

        output.parentFile?.mkdirs()
        output.delete()

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var muxerStarted = false
        var track = -1
        var done = false
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * LoopPlan.FPS * 0.12f).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, LoopPlan.FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            inputSurface = surface
            codec.start()
            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            setUpEgl(surface)
            val renderer = FrameRenderer(image, depth, depthWidth, depthHeight, width, height)
            val info = MediaCodec.BufferInfo()

            // Moves finished samples from the encoder to the muxer; with [untilEnd] it waits for EOS.
            fun drain(untilEnd: Boolean) {
                var idleRounds = 0
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!untilEnd || ++idleRounds > MAX_IDLE_ROUNDS) return
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = mux.addTrack(codec.outputFormat)
                            mux.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            idleRounds = 0
                            val buffer = codec.getOutputBuffer(index)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxerStarted && buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                mux.writeSampleData(track, buffer, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            try {
                for (frame in 0 until totalFrames) {
                    coroutineContext.ensureActive()
                    val progress = (frame % framesPerLoop).toFloat() / framesPerLoop.toFloat()
                    renderer.draw(cfg, patternId, progress)
                    EGLExt.eglPresentationTimeANDROID(display, eglSurface, frame * 1_000_000_000L / LoopPlan.FPS)
                    EGL14.eglSwapBuffers(display, eglSurface)
                    drain(untilEnd = false)
                    onProgress((frame + 1f) / totalFrames)
                }
            } finally {
                renderer.release()
            }
            codec.signalEndOfInputStream()
            drain(untilEnd = true)
            check(muxerStarted) { "Encoder produced no output" }
            done = true
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            tearDownEgl()
            inputSurface?.release()
            if (!done) output.delete()
        }
    }

    private fun setUpEgl(target: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL init failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No recordable EGL config"
        }
        eglContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "EGL makeCurrent failed" }
    }

    private fun tearDownEgl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    /** GL side: the four textures and the parallax program of the live renderer, one frame at a time. */
    private class FrameRenderer(
        image: Bitmap,
        depth: ByteArray?,
        depthWidth: Int,
        depthHeight: Int,
        private val width: Int,
        private val height: Int
    ) {
        private val modelBridge = SpatialModelBridge()
        private val program = ShaderHelper.createProgram(MESH_VERT, MESH_FRAG)
            .also { check(it != 0) { "Mesh shader failed to build" } }
        private val textureIds = IntArray(1)
        private val vboIds = IntArray(2)
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val viewProjectionMatrix = FloatArray(16)
        private val modelMatrix = FloatArray(16)

        private val uViewProjectionMatrix = GLES20.glGetUniformLocation(program, "uViewProjectionMatrix")
        private val uModelMatrix = GLES20.glGetUniformLocation(program, "uModelMatrix")
        private val uSampler = GLES20.glGetUniformLocation(program, "uSampler")
        private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        private val aTextureST = GLES20.glGetAttribLocation(program, "aTextureST")

        init {
            val imageAspect = image.width.toFloat() / image.height.coerceAtLeast(1)
            val depthBytes = depth ?: ByteArray(16) { 128.toByte() }
            val dw = if (depth != null) depthWidth.coerceAtLeast(1) else 4
            val dh = if (depth != null) depthHeight.coerceAtLeast(1) else 4
            check(modelBridge.initializeMesh(depthBytes, dw, dh, imageAspect, gridSize = 96)) {
                "Failed to initialize adaptive mesh for video export"
            }

            GLES20.glGenTextures(1, textureIds, 0)
            upload(image)

            GLES20.glGenBuffers(2, vboIds, 0)
            modelBridge.indexBuffer?.let { ib ->
                ib.position(0)
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, modelBridge.indexCount * 2, ib, GLES20.GL_STATIC_DRAW)
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, modelBridge.vertexCount * 5 * 4, null, GLES20.GL_DYNAMIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)

            // Use the exact same perspective frustum + fitScale transform as SpatialGLRenderer
            // so perspective Z-zoom and depth occlusion match the live preview.
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

        private fun upload(bitmap: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val MAX_SIDE = 1080
        const val MAX_IDLE_ROUNDS = 500
        const val MESH_VERT = """
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
        const val MESH_FRAG = """
            precision highp float;
            uniform sampler2D uSampler;
            varying vec2 vTextureCoords;
            void main() {
                gl_FragColor = texture2D(uSampler, vTextureCoords);
            }
        """
    }
}
