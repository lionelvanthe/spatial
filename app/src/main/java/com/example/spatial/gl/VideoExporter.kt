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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    ) = withContext(Dispatchers.Default) {
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

            for (frame in 0 until totalFrames) {
                coroutineContext.ensureActive()
                renderer.draw(cfg, patternId, LoopPlan.phase(frame, framesPerLoop, patternId))
                EGLExt.eglPresentationTimeANDROID(display, eglSurface, frame * 1_000_000_000L / LoopPlan.FPS)
                EGL14.eglSwapBuffers(display, eglSurface)
                drain(untilEnd = false)
                onProgress((frame + 1f) / totalFrames)
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
        Unit
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
        private val program = ShaderHelper.createProgram(ParallaxShaders.VERTEX, ParallaxShaders.FRAGMENT)
            .also { check(it != 0) { "Parallax shader failed to build" } }
        private val textures = IntArray(4)
        private val imageAspect = image.width.toFloat() / image.height
        private val depthTexelX = 1f / depthWidth.coerceAtLeast(1)
        private val depthTexelY = 1f / depthHeight.coerceAtLeast(1)
        private val separated: Boolean
        private val sample = FloatArray(3)
        private val quad: ByteBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).also {
            it.asFloatBuffer().put(QUAD)
        }

        init {
            GLES20.glGenTextures(textures.size, textures, 0)
            val layers = buildLayers(image, depth, depthWidth, depthHeight)
            separated = layers != null
            val depthBitmap = depthBitmap(depth, depthWidth, depthHeight, layers?.depthTexture)
            val inpaint = layers?.let { Bitmap.createBitmap(it.layers[0], image.width, image.height, Bitmap.Config.ARGB_8888) } ?: image
            val mask = layers?.let { Bitmap.createBitmap(it.layers[1], image.width, image.height, Bitmap.Config.ARGB_8888) }
                ?: opaqueMask(image.width, image.height)
            upload(0, image)
            upload(1, depthBitmap)
            upload(2, inpaint)
            upload(3, mask)
            depthBitmap.recycle()
            if (inpaint !== image) inpaint.recycle()
            mask.recycle()
        }

        fun draw(config: SpatialSceneConfig, patternId: String, cycle: Float) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0.06f, 0.06f, 0.09f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform1f(loc("uLayered"), if (separated) 1f else 0f)

            val animIntensity = 0.65f * (0.4f + 0.7f * config.intensity)
            MotionPattern.sample(patternId, cycle, animIntensity, sample)
            val animationZoom = if (sample[2].isNaN()) 0f else sample[2]
            val zoomScale = ViewerFraming.zoomScale(config.zoom, 2, animationZoom = animationZoom)

            for (unit in 0 until 4) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[unit])
            }
            uniform1i("image", 0)
            uniform1i("uDepthMap", 1)
            GLES20.glUniform2f(loc("uDepthTexel"), depthTexelX, depthTexelY)
            uniform1i("uInpaint", 2)
            uniform1i("uForegroundMask", 3)
            GLES20.glUniform2f(loc("uOffset"), sample[0].coerceIn(-1f, 1f), sample[1].coerceIn(-1f, 1f))
            GLES20.glUniform1f(loc("uDepthHeight"), DepthProjection.depthHeight(config.intensity))
            GLES20.glUniform1f(loc("uNeutral"), ViewerFraming.depthCenter(config.depthCenter))
            GLES20.glUniform1f(loc("uAspectRatio"), width.toFloat() / height)
            GLES20.glUniform1f(loc("uImageAspect"), imageAspect)
            GLES20.glUniform4f(loc("uBgColor"), 0.06f, 0.06f, 0.09f, 1.0f)
            GLES20.glUniform1f(loc("uScaleType"), 0f)
            GLES20.glUniform1f(loc("uFill"), .88f)
            GLES20.glUniform1f(loc("uZoom"), zoomScale)
            GLES20.glUniform2f(loc("uPan"), 0f, 0f)
            GLES20.glUniform1f(loc("uStrength"), 1.0f)
            ParallaxShaders.applyEffects(program, config)

            val pos = GLES20.glGetAttribLocation(program, "position")
            val st = GLES20.glGetAttribLocation(program, "textureST")
            quad.position(0)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(pos)
            quad.position(8)
            GLES20.glVertexAttribPointer(st, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(st)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(st)
        }

        private fun loc(name: String) = GLES20.glGetUniformLocation(program, name)
        private fun uniform1i(name: String, value: Int) = GLES20.glUniform1i(loc(name), value)

        private fun upload(index: Int, bitmap: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }

        private fun buildLayers(image: Bitmap, depth: ByteArray?, dw: Int, dh: Int): LayerBuilder.LayerData? {
            if (depth == null || depth.size != dw * dh) return null
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            return LayerBuilder.buildLayers(pixels, image.width, image.height, depth, dw, dh)
        }

        private fun depthBitmap(depth: ByteArray?, w: Int, h: Int, packed: IntArray?): Bitmap {
            val sw = w.coerceAtLeast(1)
            val sh = h.coerceAtLeast(1)
            val pixels = packed ?: IntArray(sw * sh) {
                val v = depth?.getOrNull(it)?.toInt()?.and(0xFF) ?: 0
                0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
            return Bitmap.createBitmap(pixels, sw, sh, Bitmap.Config.ARGB_8888)
        }

        private fun opaqueMask(w: Int, h: Int) =
            Bitmap.createBitmap(IntArray(w * h) { 0xFFFFFFFF.toInt() }, w, h, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val MAX_SIDE = 1080
        const val MAX_IDLE_ROUNDS = 500
        // Exact center crop; the shader bounds the ray instead of relying on hidden overscan.
        const val FILL = 1.0f
        // x, y, u, v; v = 0 is the top edge, as in the live mesh.
        val QUAD = floatArrayOf(
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f
        )
    }
}
