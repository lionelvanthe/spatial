package com.example.spatial.data

import org.json.JSONObject

enum class ImageFitMode(val shaderValue: Float, val label: String) {
    // Old values remain readable for scene compatibility; the app only uses FIT.
    FIT(0f, "Fit"), FILL(1f, "Fill"), BLURRED_BACKGROUND(2f, "Blurred background");

    companion object {
        fun parse(value: String): ImageFitMode = FIT
    }
}

/**
 * Data model for 3D spatial scene parameters matching _desc.dat
 */
object MotionLimits {
    // Photos 3D scale, as stored in the bundled _desc.dat files (slider 0..1.25, default 0.8).
    const val DEFAULT_INTENSITY = 0.8f
    const val MAX_INTENSITY = 1.25f
    const val MIN_ZOOM = -0.35f
    const val MAX_ZOOM = 0.35f
    const val MAX_ZOOM_3D = 0.35f
    const val DEFAULT_ELASTICITY = 0.5f
    const val MIN_ELASTICITY = 0.0f
    const val MAX_ELASTICITY = 1.0f

    fun springK(elasticity: Float): Float {
        val e = elasticity.coerceIn(MIN_ELASTICITY, MAX_ELASTICITY)
        return 30.0f + e * 70.0f
    }

    fun dampC(elasticity: Float): Float {
        val e = elasticity.coerceIn(MIN_ELASTICITY, MAX_ELASTICITY)
        return if (e <= 0.5f) {
            9.0f - (e / 0.5f) * (9.0f - 2.8f)
        } else {
            2.8f - ((e - 0.5f) / 0.5f) * (2.8f - 0.8f)
        }
    }
}

data class SpatialSceneConfig(
    val version: Int = 2,
    val intensity: Float = MotionLimits.DEFAULT_INTENSITY,
    val depthCenter: Float = 0.5f,
    val speed: Float = 0.35f,
    val zoom: Float = 0.0f,             // 2D/3D image zoom (-1.0 to 1.0)
    val zoom3D: Float = 0.0f,           // Legacy metadata only; no longer used for rendering.
    val elasticity: Float = MotionLimits.DEFAULT_ELASTICITY, // Jelly bounce/elasticity (0.0 to 1.0)
    val rInverted: Boolean = false,     // Invert gyro / motion
    val wallTargetX: Float = 0.5f,
    val wallTargetY: Float = 0.5f,
    val patternId: String = "none",
    val vignette: Float = 0.0f,         // edge darkening (0.0 to 1.0)
    val blur: Float = 0.0f,             // depth-of-field blur away from the neutral plane (0.0 to 1.0)
    val saturation: Float = 1.0f,       // 0 = grayscale, 1 = unchanged, 2 = vivid
    val contrast: Float = 1.0f,
    val brightness: Float = 1.0f,
    val sepia: Float = 0.0f,            // brown tint (0.0 to 1.0)
    val isometric: Float = 1.0f,        // 1 = parallel view rays (flat), 0 = full perspective
    val dolly: Float = 0.0f,            // camera push (+) in / pull (-) out, magnifying near layers
    val imageFitMode: ImageFitMode = ImageFitMode.FIT,
    val backgroundColor: Int = 0xFF0F0F17.toInt()
) {
    fun sanitized(): SpatialSceneConfig = copy(
        imageFitMode = ImageFitMode.FIT,
        intensity = intensity.finiteOr(MotionLimits.DEFAULT_INTENSITY).coerceIn(0f, MotionLimits.MAX_INTENSITY),
        depthCenter = depthCenter.finiteOr(0.5f).coerceIn(0f, 1f),
        speed = speed.finiteOr(0.35f).coerceIn(0f, 1f),
        zoom = zoom.finiteOr(0f).coerceIn(MotionLimits.MIN_ZOOM, MotionLimits.MAX_ZOOM),
        zoom3D = zoom3D.finiteOr(0f).coerceIn(0f, MotionLimits.MAX_ZOOM_3D),
        elasticity = elasticity.finiteOr(MotionLimits.DEFAULT_ELASTICITY).coerceIn(MotionLimits.MIN_ELASTICITY, MotionLimits.MAX_ELASTICITY),
        vignette = vignette.finiteOr(0f).coerceIn(0f, 1f),
        blur = blur.finiteOr(0f).coerceIn(0f, 1f),
        saturation = saturation.finiteOr(1f).coerceIn(0f, 2f),
        contrast = contrast.finiteOr(1f).coerceIn(0f, 2f),
        brightness = brightness.finiteOr(1f).coerceIn(0f, 2f),
        sepia = sepia.finiteOr(0f).coerceIn(0f, 1f),
        isometric = isometric.finiteOr(1f).coerceIn(0f, 1f),
        dolly = dolly.finiteOr(0f).coerceIn(-1f, 1f)
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
        obj.put("elasticity", elasticity.toDouble())
        obj.put("rInverted", rInverted)
        obj.put("wallTargetX", wallTargetX.toDouble())
        obj.put("wallTargetY", wallTargetY.toDouble())
        obj.put("patternId", patternId)
        obj.put("vignette", vignette.toDouble())
        obj.put("blur", blur.toDouble())
        obj.put("saturation", saturation.toDouble())
        obj.put("contrast", contrast.toDouble())
        obj.put("brightness", brightness.toDouble())
        obj.put("sepia", sepia.toDouble())
        obj.put("isometric", isometric.toDouble())
        obj.put("dolly", dolly.toDouble())
        obj.put("imageFitMode", ImageFitMode.FIT.name)
        obj.put("backgroundColor", backgroundColor)
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
                    elasticity = obj.optDouble("elasticity", MotionLimits.DEFAULT_ELASTICITY.toDouble()).toFloat(),
                    rInverted = obj.optBoolean("rInverted", false),
                    wallTargetX = obj.optDouble("wallTargetX", 0.5).toFloat(),
                    wallTargetY = obj.optDouble("wallTargetY", 0.5).toFloat(),
                    patternId = obj.optString("patternId", "none"),
                    vignette = obj.optDouble("vignette", 0.0).toFloat(),
                    blur = obj.optDouble("blur", 0.0).toFloat(),
                    saturation = obj.optDouble("saturation", 1.0).toFloat(),
                    contrast = obj.optDouble("contrast", 1.0).toFloat(),
                    brightness = obj.optDouble("brightness", 1.0).toFloat(),
                    sepia = obj.optDouble("sepia", 0.0).toFloat(),
                    isometric = obj.optDouble("isometric", 1.0).toFloat(),
                    dolly = obj.optDouble("dolly", 0.0).toFloat(),
                    imageFitMode = ImageFitMode.parse(obj.optString("imageFitMode", "FIT")),
                    backgroundColor = obj.optInt("backgroundColor", 0xFF0F0F17.toInt())
                ).sanitized()
            } catch (e: Exception) {
                SpatialSceneConfig()
            }
        }
    }
}
