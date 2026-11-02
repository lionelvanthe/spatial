package com.example.spatial.gl

import com.example.spatial.data.MotionLimits
import kotlin.math.pow

/** Geometry reconstructed from a fixed source camera, independent of live zoom. */
internal object DepthProjection {
    const val SOURCE_DISTANCE = 2.4f

    fun curvedDepth(depth: Float, intensity: Float): Float {
        val strength = 0.4f + 0.7f * intensity.coerceIn(0f, 1.25f)
        val exponent = 0.3f + 0.7f * strength * strength * strength
        return depth.coerceIn(0f, 1f).pow(exponent)
    }

    fun depthScale(intensity: Float): Float {
        val i = intensity.coerceIn(0f, 1.25f)
        return 0.30f + 0.70f * i * i * i
    }

    // Shader parallax size at MAX_INTENSITY: the largest value validated on device.
    const val MAX_DEPTH_HEIGHT = 0.06225f

    /** Shader parallax size, following the native depthScale curve up to MAX_DEPTH_HEIGHT. */
    fun depthHeight(intensity: Float): Float =
        MAX_DEPTH_HEIGHT * depthScale(intensity) / depthScale(MotionLimits.MAX_INTENSITY)

    /** Physical height of the mesh above the photo plane; the camera shift turns it into parallax. */
    fun relief(depth: Float, intensity: Float, center: Float): Float =
        0.30f * depthScale(intensity) * depth.coerceIn(0f, 1f)

    /** Curved depth of the plane that stays motionless for a given "center" setting. */
    fun focalDepth(center: Float): Float = center.coerceIn(0f, 1f) * 0.5f

    fun rayScale(relief: Float): Float = (SOURCE_DISTANCE - relief) / SOURCE_DISTANCE

    fun zoomOffset(depth: Float, zoom: Float, zoom3D: Float): Float =
        zoom * zoom3D.coerceIn(0f, 1f) * depth.coerceIn(0f, 1f)
}
