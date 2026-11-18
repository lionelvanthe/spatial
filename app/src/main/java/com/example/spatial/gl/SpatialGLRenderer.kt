package com.example.spatial.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.example.spatial.data.MotionLimits
import com.example.spatial.data.SpatialSceneConfig
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * OpenGL ES 2.0 Renderer powered by the native C++ Adaptive 3D Triangle Mesh
 * and Spring-Mass-Damper Lattice engine (SpatialModelBridge).
 */
class SpatialGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val modelBridge = SpatialModelBridge()

    private var programHandle = 0
    private var uViewProjectionMatrixHandle = -1
    private var uModelMatrixHandle = -1
    private var uSamplerHandle = -1
    private var aPositionHandle = -1
    private var aTextureSTHandle = -1

    private val textureIds = IntArray(1)
    private val vboIds = IntArray(2) // [0] = vertex VBO, [1] = index IBO
    private var hasTexture = false
    private var hasMeshBuffers = false
    private var allocatedVertexBytes = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    var currentBitmap: Bitmap? = null
        private set

    @Volatile
    var onSceneDrawn: ((Bitmap) -> Unit)? = null
    private var undrawnBitmap: Bitmap? = null

    private var currentDepthMap: ByteArray? = null
    private var currentDepthWidth = 504
    private var currentDepthHeight = 504
    private var activeBitmapWidth = 1
    private var activeBitmapHeight = 1

    @Volatile
    private var needsTextureReload = false
    @Volatile
    private var needsDepthReload = false

    @Volatile
    var sceneConfig = SpatialSceneConfig()
        set(value) {
            field = value.sanitized()
        }

    // 0 = Rotate, 1 = Shake, 2 = Animations, 3 = ZoomPan
    @Volatile
    var activeTabMode = 0
        set(value) {
            if (field != value) {
                field = value
                if (value == 1) {
                    modelBridge.resetPhysics()
                }
            }
        }

    // Legacy compatibility property
    var fitImage: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") value) {
            sceneConfig = sceneConfig.copy(imageFitMode = com.example.spatial.data.ImageFitMode.FIT)
        }

    // Sensor tilt inputs
    @Volatile
    var gyroX = 0.0f
    @Volatile
    var gyroY = 0.0f

    // Touch inputs
    @Volatile
    var touchDragX = 0.0f
    @Volatile
    var touchDragY = 0.0f
    @Volatile
    var touchZoom = 0.0f
    @Volatile
    var touchPanX = 0.0f
    @Volatile
    var touchPanY = 0.0f
    @Volatile
    var isTouching = false

    private var smoothTiltX = 0.0f
    private var smoothTiltY = 0.0f
    private var prevGyroX = 0.0f
    private var prevGyroY = 0.0f
    private var gyroNeutralX = 0.0f
    private var gyroNeutralY = 0.0f

    @Volatile
    private var pendingImpulseX = 0.0f
    @Volatile
    private var pendingImpulseY = 0.0f

    private var lastFrameTimeMs = 0L
    private var animStartTimeMs = 0L
    private var viewportWidth = 1
    private var viewportHeight = 1

    fun setSceneData(
        bitmap: Bitmap?,
        depthData: ByteArray?,
        config: SpatialSceneConfig,
        depthWidth: Int = 504,
        depthHeight: Int = 504
    ) {
        if (bitmap == null) return
        synchronized(this) {
            currentBitmap = bitmap
            undrawnBitmap = bitmap
            activeBitmapWidth = bitmap.width.coerceAtLeast(1)
            activeBitmapHeight = bitmap.height.coerceAtLeast(1)
            currentDepthMap = depthData
            currentDepthWidth = depthWidth.coerceAtLeast(1)
            currentDepthHeight = depthHeight.coerceAtLeast(1)
            sceneConfig = config
            needsTextureReload = true
            needsDepthReload = true
            touchDragX = 0.0f
            touchDragY = 0.0f
            touchZoom = 0.0f
            touchPanX = 0.0f
            touchPanY = 0.0f
            smoothTiltX = 0.0f
            smoothTiltY = 0.0f
            animStartTimeMs = SystemClock.uptimeMillis()
        }
    }

    fun onTouchDown(@Suppress("UNUSED_PARAMETER") x: Float, @Suppress("UNUSED_PARAMETER") y: Float) {
        isTouching = true
    }

    fun onTouchMove(dx: Float, dy: Float, @Suppress("UNUSED_PARAMETER") x: Float, @Suppress("UNUSED_PARAMETER") y: Float) {
        if (activeTabMode == 3) {
            touchPanX = (touchPanX + dx * 2.0f).coerceIn(-1.0f, 1.0f)
            touchPanY = (touchPanY - dy * 2.0f).coerceIn(-1.0f, 1.0f)
        } else {
            touchDragX = (touchDragX + dx * 2.2f).coerceIn(-1.0f, 1.0f)
            touchDragY = (touchDragY - dy * 2.2f).coerceIn(-1.0f, 1.0f)
        }
    }

    fun onTouchUp(velocityX: Float, velocityY: Float) {
        isTouching = false
        if (activeTabMode == 1) {
            val vx = velocityX.coerceIn(-8.0f, 8.0f)
            val vy = (-velocityY).coerceIn(-8.0f, 8.0f)
            pendingImpulseX += vx * 0.14f
            pendingImpulseY += vy * 0.14f
        }
    }

    fun resetTouchState(preserveZoom: Boolean = false) {
        touchDragX = 0.0f
        touchDragY = 0.0f
        if (!preserveZoom) {
            touchZoom = 0.0f
        }
        touchPanX = 0.0f
        touchPanY = 0.0f
        gyroNeutralX = gyroX
        gyroNeutralY = gyroY
        modelBridge.resetPhysics()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.06f, 0.06f, 0.09f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        val vertSrc = ShaderHelper.loadShaderFromAssets(context, "shaders/mesh_default.vert")
        val fragSrc = ShaderHelper.loadShaderFromAssets(context, "shaders/mesh_default.frag")
        programHandle = ShaderHelper.createProgram(vertSrc, fragSrc)
        if (programHandle == 0) {
            Log.e(TAG, "Failed to create mesh shader program")
            return
        }

        uViewProjectionMatrixHandle = GLES20.glGetUniformLocation(programHandle, "uViewProjectionMatrix")
        uModelMatrixHandle = GLES20.glGetUniformLocation(programHandle, "uModelMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(programHandle, "uSampler")
        aPositionHandle = GLES20.glGetAttribLocation(programHandle, "aPosition")
        aTextureSTHandle = GLES20.glGetAttribLocation(programHandle, "aTextureST")

        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glGenBuffers(2, vboIds, 0)
        hasMeshBuffers = vboIds[0] != 0 && vboIds[1] != 0

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.setIdentityM(viewMatrix, 0)

        lastFrameTimeMs = SystemClock.uptimeMillis()
        animStartTimeMs = lastFrameTimeMs

        synchronized(this) {
            if (currentBitmap != null) needsTextureReload = true
            if (currentDepthMap != null || currentBitmap != null) needsDepthReload = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        updateProjectionMatrix()
    }

    private fun updateProjectionMatrix() {
        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        if (viewAspect <= 1.0f) {
            Matrix.frustumM(projectionMatrix, 0, -viewAspect, viewAspect, -1f, 1f, 2.0f, 12.0f)
        } else {
            val invAspect = 1f / viewAspect
            Matrix.frustumM(projectionMatrix, 0, -1f, 1f, -invAspect, invAspect, 2.0f, 12.0f)
        }
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (programHandle == 0) return

        val nowMs = SystemClock.uptimeMillis()
        val dt = ((nowMs - lastFrameTimeMs) / 1000.0f).coerceIn(0.001f, 0.05f)
        lastFrameTimeMs = nowMs

        val drawnBitmapCandidate: Bitmap?
        val cfg: SpatialSceneConfig
        val tabMode = activeTabMode

        synchronized(this) {
            cfg = sceneConfig
            val bmp = currentBitmap
            if (needsTextureReload && bmp != null && !bmp.isRecycled) {
                uploadTexture(bmp)
                hasTexture = true
                needsTextureReload = false
                updateProjectionMatrix()
            }

            if (needsDepthReload) {
                val aspect = activeBitmapWidth.toFloat() / activeBitmapHeight.toFloat()
                val depthBytes = currentDepthMap ?: ByteArray(16) { 128.toByte() }
                val dw = if (currentDepthMap != null) currentDepthWidth else 4
                val dh = if (currentDepthMap != null) currentDepthHeight else 4
                if (modelBridge.initializeMesh(depthBytes, dw, dh, aspect, gridSize = 96)) {
                    uploadIndexBuffer()
                }
                needsDepthReload = false
            }

            drawnBitmapCandidate = if (hasTexture && undrawnBitmap != null) {
                undrawnBitmap.also { undrawnBitmap = null }
            } else null
        }

        if (!hasTexture || !modelBridge.isInitialized) return

        // Update sensor smoothing and auto-centering
        updateTiltState(dt, tabMode)

        modelBridge.setProperties(
            intensity = cfg.intensity,
            depthCenter = cfg.depthCenter,
            rInverted = cfg.rInverted,
            zoom = cfg.zoom,
            zoom3D = cfg.zoom3D,
            elasticity = cfg.elasticity
        )

        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val imageAspect = activeBitmapWidth.toFloat() / activeBitmapHeight.toFloat()
        val fitScale = if (viewAspect <= 1.0f) {
            if (imageAspect > viewAspect) (viewAspect * 3.5f) / imageAspect else 3.5f
        } else {
            if (imageAspect > viewAspect) 3.5f / imageAspect else 3.5f / viewAspect
        }
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, -7.0f)
        Matrix.scaleM(modelMatrix, 0, fitScale, fitScale, fitScale)
        when (tabMode) {
            0 -> modelBridge.updateRotate(smoothTiltX, smoothTiltY)
            1 -> {
                val impX = pendingImpulseX
                val impY = pendingImpulseY
                pendingImpulseX = 0.0f
                pendingImpulseY = 0.0f
                if (impX != 0.0f || impY != 0.0f) {
                    modelBridge.applyImpulse(impX, impY)
                }
                modelBridge.updateShake(dt, smoothTiltX, smoothTiltY)
            }
            2 -> {
                val pattern = if (cfg.patternId.equals("none", ignoreCase = true)) "horizontal" else cfg.patternId
                val cycleMs = LoopPlan.cycleMs(cfg.speed) * MotionPattern.periodCycles(pattern)
                val elapsed = (nowMs - animStartTimeMs).toFloat()
                val progress = ((elapsed % cycleMs) / cycleMs).coerceIn(0.0f, 1.0f)
                modelBridge.updateAnimated(progress, MotionPattern.patternIdToNativeInt(pattern))
            }
            3 -> {
                val z = (cfg.zoom + touchZoom).coerceIn(MotionLimits.MIN_ZOOM, 1.5f)
                modelBridge.updateZoomPan(z, touchPanX, touchPanY)
            }
        }

        drawMesh()

        if (drawnBitmapCandidate != null) {
            onSceneDrawn?.invoke(drawnBitmapCandidate)
        }
    }

    private fun updateTiltState(dt: Float, tabMode: Int) {
        val gx = gyroX
        val gy = gyroY
        val safeDt = dt.coerceAtLeast(0.001f)
        val gyroVx = (gx - prevGyroX) / safeDt
        val gyroVy = (gy - prevGyroY) / safeDt
        prevGyroX = gx
        prevGyroY = gy

        // Responsive auto-centering from e67e38f:
        // Slows neutral drift while actively tilting, swiftly recovers within ~0.55s - 0.60s when resting.
        val motionSpeed = hypot(gyroVx, gyroVy)
        val offsetDistance = hypot(gx - gyroNeutralX, gy - gyroNeutralY)
        val restingBoost = if (motionSpeed < 0.32f) {
            1.65f
        } else {
            (1.0f / (1.0f + motionSpeed * 1.5f)).coerceIn(0.4f, 1.0f)
        }
        val edgeBoost = 1.0f + offsetDistance * 1.4f
        val recenterAlpha = (1.95f * restingBoost * edgeBoost * dt).coerceIn(0.0f, 0.22f)
        gyroNeutralX += (gx - gyroNeutralX) * recenterAlpha
        gyroNeutralY += (gy - gyroNeutralY) * recenterAlpha

        if (tabMode == 1 && motionSpeed > 1.2f) {
            pendingImpulseX += gyroVx * 0.04f
            pendingImpulseY += gyroVy * 0.04f
        }

        val sensorTiltX = softCompress((gx - gyroNeutralX) * 1.35f)
        val sensorTiltY = softCompress((gy - gyroNeutralY) * 1.35f)
        val rawX = if (isTouching) touchDragX else (sensorTiltX + touchDragX).coerceIn(-1.0f, 1.0f)
        val rawY = if (isTouching) touchDragY else (sensorTiltY + touchDragY).coerceIn(-1.0f, 1.0f)

        if (!isTouching && (abs(touchDragX) > 0.0001f || abs(touchDragY) > 0.0001f)) {
            val decay = exp(-3.5f * dt)
            touchDragX *= decay
            touchDragY *= decay
        }

        val lerp = 1.0f - exp(-18.0f * dt)
        smoothTiltX += (rawX - smoothTiltX) * lerp
        smoothTiltY += (rawY - smoothTiltY) * lerp
    }

    private fun softCompress(value: Float): Float {
        val clamped = value.coerceIn(-3.0f, 3.0f)
        return (clamped / sqrt(1.0f + 0.35f * clamped * clamped)).coerceIn(-1.0f, 1.0f)
    }

    private fun uploadTexture(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun uploadIndexBuffer() {
        val ib = modelBridge.indexBuffer ?: return
        if (!hasMeshBuffers) return
        ib.position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            modelBridge.indexCount * 2,
            ib,
            GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        val neededVBytes = modelBridge.vertexCount * 5 * 4
        if (neededVBytes != allocatedVertexBytes) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, neededVBytes, null, GLES20.GL_DYNAMIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            allocatedVertexBytes = neededVBytes
        }
    }

    private fun drawMesh() {
        val vb = modelBridge.vertexBuffer ?: return
        if (!hasMeshBuffers) return

        GLES20.glUseProgram(programHandle)
        GLES20.glUniformMatrix4fv(uViewProjectionMatrixHandle, 1, false, viewProjectionMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelMatrixHandle, 1, false, modelMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glUniform1i(uSamplerHandle, 0)

        vb.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, modelBridge.vertexCount * 5 * 4, vb)

        val strideBytes = 5 * 4
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, strideBytes, 0)

        GLES20.glEnableVertexAttribArray(aTextureSTHandle)
        GLES20.glVertexAttribPointer(aTextureSTHandle, 2, GLES20.GL_FLOAT, false, strideBytes, 3 * 4)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, modelBridge.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureSTHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun release() {
        modelBridge.destroy()
        if (hasMeshBuffers) {
            GLES20.glDeleteBuffers(2, vboIds, 0)
            hasMeshBuffers = false
        }
        if (textureIds[0] != 0) {
            GLES20.glDeleteTextures(1, textureIds, 0)
            textureIds[0] = 0
        }
        if (programHandle != 0) {
            GLES20.glDeleteProgram(programHandle)
            programHandle = 0
        }
    }

    private companion object {
        const val TAG = "SpatialGLRenderer"
    }
}
