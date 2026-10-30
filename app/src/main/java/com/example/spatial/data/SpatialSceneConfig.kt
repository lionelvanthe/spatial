package com.example.spatial.data

import org.json.JSONObject

/**
 * Data model for 3D spatial scene parameters matching _desc.dat
 */
object MotionLimits {
    const val DEFAULT_INTENSITY = 0.08f
    const val MAX_INTENSITY = 0.15f
    const val MIN_ZOOM = -0.35f
    const val MAX_ZOOM = 0.35f
    const val MAX_ZOOM_3D = 0.35f
}

data class SpatialSceneConfig(
    val version: Int = 2,
    val intensity: Float = MotionLimits.DEFAULT_INTENSITY,
    val depthCenter: Float = 0.5f,
    val speed: Float = 0.35f,
    val zoom: Float = 0.0f,             // 2D/3D image zoom (-1.0 to 1.0)
    val zoom3D: Float = 0.0f,           // 3D perspective depth scale (0.0 to 1.0)
    val rInverted: Boolean = false,     // Invert gyro / motion
    val wallTargetX: Float = 0.5f,
    val wallTargetY: Float = 0.5f,
    val patternId: String = "none"
) {
    fun sanitized(): SpatialSceneConfig = copy(
        intensity = intensity.finiteOr(MotionLimits.DEFAULT_INTENSITY).coerceIn(0f, MotionLimits.MAX_INTENSITY),
        depthCenter = depthCenter.finiteOr(0.5f).coerceIn(0f, 1f),
        speed = speed.finiteOr(0.35f).coerceIn(0f, 1f),
        zoom = zoom.finiteOr(0f).coerceIn(MotionLimits.MIN_ZOOM, MotionLimits.MAX_ZOOM),
        zoom3D = zoom3D.finiteOr(0f).coerceIn(0f, MotionLimits.MAX_ZOOM_3D)
    )

    private fun Float.finiteOr(default: Float) = if (isFinite()) this else default

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("intensity", intensity.toDouble())
        obj.put("depthCenter", depthCenter.toDouble())
        obj.put("speed", speed.toDouble())
        obj.put("zoom", zoom.toDouble())
        obj.put("zoom3D", zoom3D.toDouble())
        obj.put("rInverted", rInverted)
        obj.put("wallTargetX", wallTargetX.toDouble())
        obj.put("wallTargetY", wallTargetY.toDouble())
        obj.put("patternId", patternId)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SpatialSceneConfig {
            return try {
                val obj = JSONObject(jsonStr)
                SpatialSceneConfig(
                    version = obj.optInt("version", 2),
                    intensity = obj.optDouble("intensity", MotionLimits.DEFAULT_INTENSITY.toDouble()).toFloat(),
                    depthCenter = obj.optDouble("depthCenter", 0.5).toFloat(),
                    speed = obj.optDouble("speed", 0.35).toFloat(),
                    zoom = obj.optDouble("zoom", 0.0).toFloat(),
                    zoom3D = obj.optDouble("zoom3D", 0.0).toFloat(),
                    rInverted = obj.optBoolean("rInverted", false),
                    wallTargetX = obj.optDouble("wallTargetX", 0.5).toFloat(),
                    wallTargetY = obj.optDouble("wallTargetY", 0.5).toFloat(),
                    patternId = obj.optString("patternId", "none")
                ).sanitized()
            } catch (e: Exception) {
                SpatialSceneConfig()
            }
        }
    }
}
