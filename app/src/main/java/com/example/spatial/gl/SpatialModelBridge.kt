package com.example.spatial.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * JNI bridge to the native C++ adaptive 3D triangle mesh & spring-mass physics engine.
 */
class SpatialModelBridge {
    private var handle: Long = 0L

    var vertexCount: Int = 0
        private set
    var indexCount: Int = 0
        private set

    var vertexBuffer: FloatBuffer? = null
        private set
    var indexBuffer: ShortBuffer? = null
        private set

    val isInitialized: Boolean
        get() = handle != 0L && vertexCount > 0 && indexCount > 0

    fun ensureCreated() {
        if (handle == 0L) {
            handle = nativeCreateModel()
        }
    }

    fun initializeMesh(
        depthBytes: ByteArray,
        depthWidth: Int,
        depthHeight: Int,
        imageAspect: Float,
        gridSize: Int = 96
    ): Boolean {
        ensureCreated()
        val counts = nativeInitializeMesh(handle, depthBytes, depthWidth, depthHeight, imageAspect, gridSize)
            ?: return false
        if (counts.size < 2 || counts[0] <= 0 || counts[1] <= 0) return false

        vertexCount = counts[0]
        indexCount = counts[1]

        val vBytes = vertexCount * 5 * 4
        if (vertexBuffer == null || vertexBuffer!!.capacity() * 4 < vBytes) {
            vertexBuffer = ByteBuffer.allocateDirect(vBytes)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }

        val iBytes = indexCount * 2
        if (indexBuffer == null || indexBuffer!!.capacity() * 2 < iBytes) {
            indexBuffer = ByteBuffer.allocateDirect(iBytes)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
        }

        indexBuffer!!.position(0)
        nativeCopyIndices(handle, indexBuffer!!)
        return true
    }

    fun setProperties(
        intensity: Float,
        depthCenter: Float,
        rInverted: Boolean,
        zoom: Float,
        zoom3D: Float,
        elasticity: Float
    ) {
        if (handle == 0L) return
        nativeSetProperties(handle, intensity, depthCenter, rInverted, zoom, zoom3D, elasticity)
    }

    fun updateRotate(tiltX: Float, tiltY: Float): Boolean {
        val vb = vertexBuffer ?: return false
        if (handle == 0L) return false
        vb.position(0)
        return nativeUpdateRotate(handle, vb, tiltX, tiltY)
    }

    fun updateShake(dt: Float, dragX: Float, dragY: Float, isTouching: Boolean = false) {
        val vb = vertexBuffer ?: return
        if (handle == 0L) return
        vb.position(0)
        nativeUpdateShake(handle, vb, dt, dragX, dragY, isTouching)
    }

    fun updateAnimated(progress: Float, patternId: Int): Boolean {
        val vb = vertexBuffer ?: return false
        if (handle == 0L) return false
        vb.position(0)
        return nativeUpdateAnimated(handle, vb, progress, patternId)
    }

    fun updateZoomPan(zoom: Float, panX: Float, panY: Float): Boolean {
        val vb = vertexBuffer ?: return false
        if (handle == 0L) return false
        vb.position(0)
        return nativeUpdateZoomPan(handle, vb, zoom, panX, panY)
    }

    fun applyImpulse(impulseX: Float, impulseY: Float) {
        if (handle == 0L) return
        nativeApplyImpulse(handle, impulseX, impulseY)
    }

    fun resetPhysics() {
        if (handle == 0L) return
        nativeResetPhysics(handle)
    }

    fun destroy() {
        if (handle != 0L) {
            nativeDestroyModel(handle)
            handle = 0L
        }
        vertexCount = 0
        indexCount = 0
        vertexBuffer = null
        indexBuffer = null
    }

    private external fun nativeCreateModel(): Long
    private external fun nativeDestroyModel(handle: Long)
    private external fun nativeInitializeMesh(
        handle: Long,
        depthBytes: ByteArray,
        depthWidth: Int,
        depthHeight: Int,
        imageAspect: Float,
        gridSize: Int
    ): IntArray?

    private external fun nativeCopyIndices(handle: Long, indexBuffer: ShortBuffer)
    private external fun nativeSetProperties(
        handle: Long,
        intensity: Float,
        depthCenter: Float,
        rInverted: Boolean,
        zoom: Float,
        zoom3D: Float,
        elasticity: Float
    )

    private external fun nativeUpdateRotate(handle: Long, vertexBuffer: FloatBuffer, tiltX: Float, tiltY: Float): Boolean
    private external fun nativeUpdateShake(
        handle: Long,
        vertexBuffer: FloatBuffer,
        dt: Float,
        dragX: Float,
        dragY: Float,
        isTouching: Boolean
    )
    private external fun nativeUpdateAnimated(handle: Long, vertexBuffer: FloatBuffer, progress: Float, patternId: Int): Boolean
    private external fun nativeUpdateZoomPan(handle: Long, vertexBuffer: FloatBuffer, zoom: Float, panX: Float, panY: Float): Boolean
    private external fun nativeApplyImpulse(handle: Long, impulseX: Float, impulseY: Float)
    private external fun nativeResetPhysics(handle: Long)

    companion object {
        init {
            System.loadLibrary("spatial_mesh")
        }
    }
}
