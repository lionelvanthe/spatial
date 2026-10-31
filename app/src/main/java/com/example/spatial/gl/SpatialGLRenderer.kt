package com.example.spatial.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.example.spatial.data.SpatialSceneConfig
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos

class SpatialGLRenderer(private val context: Context) : GLSurfaceView.Renderer {
    // A flat quad; all depth intersection is resolved per pixel in ParallaxShaders.
    private val rayMarchMesh = SpatialMesh(gridWidth = 1, gridHeight = 1)
    private val backgroundMesh = SpatialMesh(gridWidth = 100, gridHeight = 100)
    private val foregroundMesh = SpatialMesh(gridWidth = 100, gridHeight = 100)
    private var programHandle = 0
    private var uViewProjectionMatrixHandle = 0
    private var uModelMatrixHandle = 0
    private var uSamplerHandle = 0
    private var uDepthSamplerHandle = 0
    private var uInpaintSamplerHandle = 0
    private var uForegroundMaskHandle = 0
    private var uOffsetHandle = 0
    private var uDepthHeightHandle = 0
    private var uNeutralHandle = 0
    private var uAspectRatioHandle = 0
    private var uImageAspectHandle = 0
    private var uBgColorHandle = 0
    private var uScaleTypeHandle = 0
    private var uFillHandle = 0
    private var uZoomHandle = 0
    private var uPanHandle = 0
    private var uZoom3DHandle = 0
    private var uStrengthHandle = 0
    private var aPositionHandle = 0
    private var aTextureSTHandle = 0
    // Color image, depth field, inpainted background, and foreground alpha mask.
    private val textureHandle = IntArray(4)
    private var hasTexture = false
    // Matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    // Current scene & texture updates
    var currentBitmap: Bitmap? = null
        private set
    private var currentDepthMap: ByteArray? = null
    private var currentDepthWidth = 504
    private var currentDepthHeight = 504
    private var layerBitmaps: Array<Bitmap?> = arrayOf(null, null)
    private var hasSeparatedLayers = false
    @Volatile
    private var needsTextureReload = false
    @Volatile
    private var needsDepthReload = false
    private var activeBitmapWidth = 1
    private var activeBitmapHeight = 1
    // Scene configuration
    @Volatile
    var sceneConfig = SpatialSceneConfig()
        set(value) { field = value.sanitized() }
    // Active bottom tab mode:
    // 0 = Rotate (Rotational Tilt), 1 = Shake (Dynamic Jelly Physics), 2 = Animations, 3 = ZoomPan
    @Volatile
    var activeTabMode = 0
    // true = whole photo visible (editor/viewer); false = edge-to-edge crop (wallpaper)
    @Volatile
    var fitImage = false
    // Sensor inputs (from Gyroscope / Accelerometer)
    @Volatile
    var gyroX = 0.0f
    @Volatile
    var gyroY = 0.0f
    // Touch inputs (from drag & pinch gestures)
    @Volatile
    var touchDragX = 0.0f
    @Volatile
    var touchDragY = 0.0f
    @Volatile
    var touchZoom = 0.0f
    @Volatile
    var isTouching = false
    // Jelly / Jello Physics State (Tab 1: Dynamic spring-mass-damper lattice)
    @Volatile
    var jellyDisplacementX = 0.0f
    @Volatile
    var jellyDisplacementY = 0.0f
    @Volatile
    var jellyVelocityX = 0.0f
    @Volatile
    var jellyVelocityY = 0.0f
    @Volatile
    var jellyWavePhase = 0.0f
    // Smooth movement state (Direct exponential LERP smoothing without spring bounce)
    private var smoothTiltX = 0.0f
    private var smoothTiltY = 0.0f
    private var velocityX = 0.0f
    private var velocityY = 0.0f
    private var viewWidth = 1
    private var viewHeight = 1
    private var lastFrameTime = 0L
    private val patternSample = FloatArray(3)
    fun onTouchDown(x: Float, y: Float) {
        isTouching = true
    }
    fun onTouchMove(dx: Float, dy: Float, x: Float, y: Float) {
        if (activeTabMode == 1) {
            // Tab 1: Direct squishy drag into jelly spring with clamped amplitude
            jellyDisplacementX = (jellyDisplacementX + dx * 2.5f).coerceIn(-1.0f, 1.0f)
            jellyDisplacementY = (jellyDisplacementY + dy * 2.5f).coerceIn(-1.0f, 1.0f)
        } else {
            touchDragX = (touchDragX + dx * 2.5f).coerceIn(-1.0f, 1.0f)
            touchDragY = (touchDragY + dy * 2.5f).coerceIn(-1.0f, 1.0f)
        }
    }
    fun onTouchUp(releaseVx: Float, releaseVy: Float) {
        isTouching = false
        if (activeTabMode == 1) {
            // Impart release flick velocity into the mass-spring jelly oscillator
            jellyVelocityX = (releaseVx * 2.2f).coerceIn(-18.0f, 18.0f)
            jellyVelocityY = (releaseVy * 2.2f).coerceIn(-18.0f, 18.0f)
        }
    }
    /**
     * Resets touch zoom and drag offsets back to initial 1.0x centered identity state.
     */
    fun resetTouchState() {
        touchZoom = 0.0f
        touchDragX = 0.0f
        touchDragY = 0.0f
        smoothTiltX = 0.0f
        smoothTiltY = 0.0f
        velocityX = 0.0f
        velocityY = 0.0f
        jellyDisplacementX = 0.0f
        jellyDisplacementY = 0.0f
        jellyVelocityX = 0.0f
        jellyVelocityY = 0.0f
    }
    fun setSceneData(bitmap: Bitmap?, depthData: ByteArray?, config: SpatialSceneConfig, depthWidth: Int = 504, depthHeight: Int = 504) {
        sceneConfig = config.sanitized()
        currentBitmap = bitmap
        currentDepthMap = depthData?.takeIf { depthWidth > 0 && depthHeight > 0 && it.size == depthWidth * depthHeight }
        currentDepthWidth = depthWidth
        currentDepthHeight = depthHeight
        needsTextureReload = true
        needsDepthReload = true
    }

    private fun buildLayers(bitmap: Bitmap, depth: ByteArray?): Array<Bitmap?>? {
        if (depth == null || depth.size != currentDepthWidth * currentDepthHeight) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val layerData = LayerBuilder.buildLayers(pixels, bitmap.width, bitmap.height, depth, currentDepthWidth, currentDepthHeight)
            ?: return null
        return Array(2) { index ->
            Bitmap.createBitmap(layerData.layers[index], bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        }
    }

    private fun createDepthBitmap(depth: ByteArray?, width: Int, height: Int): Bitmap {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val pixels = IntArray(safeWidth * safeHeight)
        for (i in pixels.indices) {
            val value = depth?.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
            pixels[i] = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(pixels, safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    }

    private fun createOpaqueMask(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(
            IntArray(width * height) { 0xFFFFFFFF.toInt() },
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        hasTexture = false
        needsTextureReload = true
        needsDepthReload = true
        GLES20.glClearColor(0.06f, 0.06f, 0.09f, 1.0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Intersect the depth field per pixel; this preserves object contours instead of bending
        // the entire photo through a displaced triangle mesh.
        programHandle = ShaderHelper.createProgram(ParallaxShaders.VERTEX, ParallaxShaders.FRAGMENT)
        if (programHandle != 0) {
            uSamplerHandle = GLES20.glGetUniformLocation(programHandle, "image")
            uDepthSamplerHandle = GLES20.glGetUniformLocation(programHandle, "uDepthMap")
            uInpaintSamplerHandle = GLES20.glGetUniformLocation(programHandle, "uInpaint")
            uForegroundMaskHandle = GLES20.glGetUniformLocation(programHandle, "uForegroundMask")
            uOffsetHandle = GLES20.glGetUniformLocation(programHandle, "uOffset")
            uDepthHeightHandle = GLES20.glGetUniformLocation(programHandle, "uDepthHeight")
            uNeutralHandle = GLES20.glGetUniformLocation(programHandle, "uNeutral")
            uAspectRatioHandle = GLES20.glGetUniformLocation(programHandle, "uAspectRatio")
            uImageAspectHandle = GLES20.glGetUniformLocation(programHandle, "uImageAspect")
            uBgColorHandle = GLES20.glGetUniformLocation(programHandle, "uBgColor")
            uScaleTypeHandle = GLES20.glGetUniformLocation(programHandle, "uScaleType")
            uFillHandle = GLES20.glGetUniformLocation(programHandle, "uFill")
            uZoomHandle = GLES20.glGetUniformLocation(programHandle, "uZoom")
            uPanHandle = GLES20.glGetUniformLocation(programHandle, "uPan")
            uZoom3DHandle = GLES20.glGetUniformLocation(programHandle, "uZoom3D")
            uStrengthHandle = GLES20.glGetUniformLocation(programHandle, "uStrength")
            aPositionHandle = GLES20.glGetAttribLocation(programHandle, "position")
            aTextureSTHandle = GLES20.glGetAttribLocation(programHandle, "textureST")
        }
        // Initialize texture object
        GLES20.glGenTextures(textureHandle.size, textureHandle, 0)
        textureHandle.forEach { handle ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        lastFrameTime = SystemClock.uptimeMillis()
        Log.d("SpatialGLRenderer", "onSurfaceCreated finished: prog=$programHandle, tex=${textureHandle[0]}")
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        val aspect = if (height > 0) width.toFloat() / height.toFloat() else 1.0f
        Matrix.perspectiveM(projectionMatrix, 0, 45.0f, aspect, 0.1f, 100.0f)
    }
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        // Upload the color image and its depth field.  The shader performs the parallax lookup
        // per pixel, so no foreground/background texture is stretched by mesh interpolation.
        val bmp = currentBitmap
        if ((needsTextureReload || needsDepthReload) && bmp != null && !bmp.isRecycled) {
            needsTextureReload = false
            needsDepthReload = false
            activeBitmapWidth = bmp.width
            activeBitmapHeight = bmp.height
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[1])
            val depthBitmap = createDepthBitmap(currentDepthMap, currentDepthWidth, currentDepthHeight)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, depthBitmap, 0)
            depthBitmap.recycle()
            val layers = buildLayers(bmp, currentDepthMap)
            val inpaint = layers?.get(0) ?: bmp
            val foregroundMask = layers?.get(1) ?: createOpaqueMask(bmp.width, bmp.height)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[2])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, inpaint, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[3])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, foregroundMask, 0)
            if (inpaint !== bmp) inpaint.recycle()
            foregroundMask.recycle()
            hasTexture = true
            Log.d("SpatialGLRenderer", "Ray-march depth textures loaded: ${bmp.width}x${bmp.height}")
        }
        if (programHandle == 0 || !hasTexture) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameTime > 0) ((now - lastFrameTime) / 1000.0f).coerceIn(0.001f, 0.05f) else 0.016f
        lastFrameTime = now
        // Calculate animation pattern shift matching rk0.O(Z) and performAnimatedUpdate (0x1439c)
        val speedSlider = sceneConfig.speed.coerceIn(0.0f, 1.0f)
        val cycleDurationMs = (1000.0f + (1.0f - speedSlider) * 6000.0f).coerceAtLeast(1000.0f)
        val progress = (now % cycleDurationMs.toLong()).toFloat() / cycleDurationMs
        val cycle = progress * 2.0f * Math.PI.toFloat()
        val animIntensity = 0.65f * (0.4f + 0.7f * sceneConfig.intensity.coerceIn(0.0f, 1.25f))
        // Native zoom -> shiftZ formula from performRotationalUpdate (0x141a8..0x141f0) & performAnimatedUpdate (0x1439c):
        // effectiveZoom = (zoom < 0.0f) ? (zoom * 0.5f) : zoom
        val rawZoom = if (activeTabMode == 0 || activeTabMode == 2) sceneConfig.zoom.coerceIn(-1.0f, 1.0f) else 0.0f
        val effectiveZoom = if (rawZoom < 0.0f) rawZoom * 0.5f else rawZoom
        var animZ = if (activeTabMode == 2) {
            (1.0f - cos(cycle)) * effectiveZoom * 0.1f
        } else {
            0.0f
        }
        MotionPattern.sample(sceneConfig.patternId, cycle, animIntensity, patternSample)
        val animX = patternSample[0]
        val animY = patternSample[1]
        if (!patternSample[2].isNaN()) animZ = patternSample[2]
        // Invert factor
        val invertMult = if (sceneConfig.rInverted) -1.0f else 1.0f
        var targetTiltX: Float
        var targetTiltY: Float
        val isJelly = (activeTabMode == 1)
        if (isJelly) {
            // Tab 1: Authentic Jelly / Jello Physics Simulation
            if (!isTouching) {
                // Mass-spring-damper harmonic ODE: F = -k * pos - c * vel
                val springK = 65.0f
                val dampC = 2.8f
                val forceX = -jellyDisplacementX * springK - jellyVelocityX * dampC
                val forceY = -jellyDisplacementY * springK - jellyVelocityY * dampC
                jellyVelocityX += forceX * dt
                jellyVelocityY += forceY * dt
                jellyDisplacementX += jellyVelocityX * dt
                jellyDisplacementY += jellyVelocityY * dt
                if (kotlin.math.abs(jellyDisplacementX) < 0.0005f && kotlin.math.abs(jellyVelocityX) < 0.001f) {
                    jellyDisplacementX = 0.0f
                    jellyVelocityX = 0.0f
                }
                if (kotlin.math.abs(jellyDisplacementY) < 0.0005f && kotlin.math.abs(jellyVelocityY) < 0.001f) {
                    jellyDisplacementY = 0.0f
                    jellyVelocityY = 0.0f
                }
            }
            val speedMag = kotlin.math.hypot(jellyVelocityX, jellyVelocityY)
            jellyWavePhase += speedMag * dt * 12.0f
            targetTiltX = (jellyDisplacementX + gyroX * 1.0f * invertMult).coerceIn(-1.0f, 1.0f)
            targetTiltY = (jellyDisplacementY + gyroY * 1.0f * invertMult).coerceIn(-1.0f, 1.0f)
            smoothTiltX = targetTiltX
            smoothTiltY = targetTiltY
        } else {
            // Strictly reset any leftover jelly physics state so Jelly never leaks into other tabs
            jellyDisplacementX = 0.0f
            jellyDisplacementY = 0.0f
            jellyVelocityX = 0.0f
            jellyVelocityY = 0.0f
            if (!isTouching && activeTabMode != 3) {
                touchDragX *= 0.90f
                touchDragY *= 0.90f
                if (kotlin.math.abs(touchDragX) < 0.0005f) touchDragX = 0.0f
                if (kotlin.math.abs(touchDragY) < 0.0005f) touchDragY = 0.0f
            }
            if (activeTabMode == 0) {
                // Tab 0: Rotational Arc (Shake) - Gyro + smooth touch drag
                targetTiltX = (gyroX * 2.2f * invertMult) + touchDragX
                targetTiltY = (gyroY * 2.2f * invertMult) + touchDragY
            } else if (activeTabMode == 2) {
                // Tab 2: Animations mode
                targetTiltX = (gyroX * 2.2f * invertMult) + touchDragX + animX
                targetTiltY = (gyroY * 2.2f * invertMult) + touchDragY + animY
            } else {
                // Tab 3: ZoomPan mode
                targetTiltX = touchDragX
                targetTiltY = touchDragY
            }
            // Direct exponential LERP smoothing: strictly NO spring oscillation or inertia bounce
            val lerpFactor = (1.0f - kotlin.math.exp(-18.0f * dt)).coerceIn(0.05f, 1.0f)
            smoothTiltX += (targetTiltX - smoothTiltX) * lerpFactor
            smoothTiltY += (targetTiltY - smoothTiltY) * lerpFactor
            velocityX = 0.0f
            velocityY = 0.0f
        }
        // Aspect ratios
        val screenAspect = if (viewHeight > 0) viewWidth.toFloat() / viewHeight.toFloat() else 1.0f
        val imageAspect = if (activeBitmapHeight > 0) activeBitmapWidth.toFloat() / activeBitmapHeight.toFloat() else screenAspect
        val fovY = 45.0f
        val baseDistance = 2.4f
        // Base mesh geometry is strictly anchored to the reference camera frustum at Z=0.
        // It does not depend on dynamic cameraZ or zoom, preventing perspective cancellation.
        val baseVisibleHeight = 2.0f * baseDistance * kotlin.math.tan(Math.toRadians(fovY / 2.0)).toFloat()
        val baseVisibleWidth = baseVisibleHeight * screenAspect
        // Center-crop (Aspect Fill) with clean 25% safety margin to accommodate 3D perspective rotation
        val margin = 1.25f
        val meshWidth: Float
        val meshHeight: Float
        if (fitImage) {
            // Aspect Fit: show the whole photo with a small border, parallax stays inside the frame.
            if (screenAspect < imageAspect) {
                meshWidth = baseVisibleWidth * FIT_FILL
                meshHeight = meshWidth / imageAspect
            } else {
                meshHeight = baseVisibleHeight * FIT_FILL
                meshWidth = meshHeight * imageAspect
            }
        } else if (screenAspect < imageAspect) {
            // Screen is taller than image (phone portrait): match height, width extends horizontally
            meshHeight = baseVisibleHeight * margin
            meshWidth = meshHeight * imageAspect
        } else {
            // Screen is wider than image: match width, height extends vertically
            meshWidth = baseVisibleWidth * margin
            meshHeight = meshWidth / imageAspect
        }
        // Camera distance (cameraZ) handles the dynamic 2D/3D zoom scaling.
        // When sliderZoom == 0f and touchZoom == 0f, zoomScale is strictly 1.0f,
        // positioning the camera at baseDistance (2.4f) and returning the quad to 100% exact initial size.
        val sliderZoom = if (activeTabMode == 0 || activeTabMode == 2) effectiveZoom else 0.0f
        val zoomScale = (1.0f + sliderZoom * 0.6f + touchZoom).coerceIn(0.5f, 2.5f)
        val cameraZ = baseDistance / zoomScale
        // Update mesh vertices with exact distortion-free dimensions and native C++ parameter formulas
        // Per rk0.p([F) lines 3515-3526: Mode 3 (Tab 2 Animations) inverts depthCenter -> (1.0f - depthCenter)
        val effectiveDepthCenter = if (activeTabMode == 2) {
            (1.0f - sceneConfig.depthCenter).coerceIn(0.0f, 1.0f)
        } else {
            sceneConfig.depthCenter.coerceIn(0.0f, 1.0f)
        }
        val effectiveZoom3D = if ((activeTabMode == 0 || activeTabMode == 2) && kotlin.math.abs(sceneConfig.zoom) >= 0.1f) {
            sceneConfig.zoom3D.coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }
        // Flat clip-space rectangle for the ray-march shader.
        rayMarchMesh.setUniformDepth(0.0f)
        rayMarchMesh.updateMesh(
            meshWidth = 2.0f,
            meshHeight = 2.0f,
            intensity = 0.0f,
            depthCenter = 0.0f,
            shiftZ = 0.0f,
            zoom3D = 0.0f
        )
        GLES20.glUseProgram(programHandle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[1])
        GLES20.glUniform1i(uDepthSamplerHandle, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[2])
        GLES20.glUniform1i(uInpaintSamplerHandle, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[3])
        GLES20.glUniform1i(uForegroundMaskHandle, 3)
        GLES20.glUniform2f(uOffsetHandle, smoothTiltX.coerceIn(-1f, 1f), smoothTiltY.coerceIn(-1f, 1f))
        GLES20.glUniform1f(uDepthHeightHandle, (0.045f + 0.115f * sceneConfig.intensity).coerceIn(0.0f, 0.19f))
        GLES20.glUniform1f(uNeutralHandle, effectiveDepthCenter)
        GLES20.glUniform1f(uAspectRatioHandle, screenAspect)
        GLES20.glUniform1f(uImageAspectHandle, imageAspect)
        GLES20.glUniform4f(uBgColorHandle, 0.06f, 0.06f, 0.09f, 1.0f)
        GLES20.glUniform1f(uScaleTypeHandle, if (fitImage) 0.0f else 1.0f)
        GLES20.glUniform1f(uFillHandle, if (fitImage) FIT_FILL else 1.12f)
        GLES20.glUniform1f(uZoomHandle, zoomScale)
        GLES20.glUniform2f(uPanHandle, if (activeTabMode == 3) touchDragX * 0.25f else 0.0f, if (activeTabMode == 3) touchDragY * 0.25f else 0.0f)
        GLES20.glUniform1f(uZoom3DHandle, effectiveZoom3D)
        GLES20.glUniform1f(uStrengthHandle, if (activeTabMode == 3) 0.0f else 1.0f)
        ParallaxShaders.applyEffects(programHandle, sceneConfig)
        rayMarchMesh.draw(aPositionHandle, aTextureSTHandle)
    }
    companion object {
        // Max sideways eye travel (world units) at full tilt.
        private const val EYE_RANGE = 0.8f
        // Fraction of the screen the photo occupies in fit mode.
        private const val FIT_FILL = 0.88f
        private const val DEFAULT_VERT_SHADER = """
            uniform mat4 viewProjectionMatrix;
            uniform mat4 modelMatrix;
            attribute vec4 position;
            attribute vec2 textureST;
            varying vec2 textureCoords;
            void main() {
                gl_Position = viewProjectionMatrix * (modelMatrix * position);
                textureCoords = textureST;
            }
        """
        private const val DEFAULT_FRAG_SHADER = """
            precision highp float;
            uniform sampler2D sampler;
            varying vec2 textureCoords;
            void main() {
                gl_FragColor = texture2D(sampler, textureCoords);
            }
        """
    }
}
