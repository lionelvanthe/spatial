package com.example.spatial.sensor

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sin
import kotlin.math.sqrt

/** Screen-normal rotation in the calibrated device frame, without Euler singularities. */
internal class RelativeTilt {
    private var reference: FloatArray? = null
    private var refMatrix: FloatArray? = null
    private var smoothX: Float = 0f
    private var smoothY: Float = 0f
    private var prevSinX: Float = 0f
    private var prevSinY: Float = 0f
    private var elapsedMs: Float = 0f

    fun reset() {
        reference = null
        refMatrix = null
        smoothX = 0f
        smoothY = 0f
        prevSinX = 0f
        prevSinY = 0f
        elapsedMs = 0f
    }

    fun update(matrix: FloatArray): Pair<Float, Float> {
        val initial = reference ?: matrix.copyOf().also { reference = it }
        // Horizontal tilt combines yaw rotation (facing left/right) and wrist roll (tilting left/right edge)
        val hYaw = initial[0] * matrix[2] + initial[3] * matrix[5] + initial[6] * matrix[8]
        val hRoll = -(initial[1] * matrix[0] + initial[4] * matrix[3] + initial[7] * matrix[6])
        val horizontal = (hYaw + hRoll).coerceIn(-1f, 1f)

        // Vertical tilt measures pitch rotation (top edge away/closer)
        val vertical = (initial[1] * matrix[2] + initial[4] * matrix[5] + initial[7] * matrix[8]).coerceIn(-1f, 1f)

        return (asin(horizontal) / 0.55f).coerceIn(-1f, 1f) to
            (asin(vertical) / 0.55f).coerceIn(-1f, 1f)
    }

    /**
     * Reference APK (`Lpc1`) tilt tracking:
     * - Extracts screen-plane tilt components directly from relative rotation (no yaw+roll double counting)
     * - Smooths with dt-dependent alpha in [0.30, 0.90] after initial stabilization ramp
     * - Auto-centers reference matrix toward current matrix via quaternion SLERP (t = dtMs * 0.0028)
     */
    fun updateWithAutoCenter(matrix: FloatArray, dtMs: Float): Pair<Float, Float> {
        val ref = refMatrix
        if (ref == null) {
            refMatrix = matrix.copyOf()
            smoothX = 0f
            smoothY = 0f
            prevSinX = 0f
            prevSinY = 0f
            elapsedMs = 0f
            return 0f to 0f
        }

        val clampedDt = dtMs.coerceIn(1f, 100f)
        elapsedMs += clampedDt

        val sinX = (ref[0] * matrix[2] + ref[3] * matrix[5] + ref[6] * matrix[8]).coerceIn(-1f, 1f)
        val sinY = (ref[1] * matrix[2] + ref[4] * matrix[5] + ref[7] * matrix[8]).coerceIn(-1f, 1f)
        // Map ~30 deg horizontal / ~26 deg vertical tilt to full [-1, 1] range so max tilt matches max swipe
        val deltaX = (asin(sinX) / 0.52f).coerceIn(-1f, 1f)
        val deltaY = (asin(sinY) / 0.45f).coerceIn(-1f, 1f)

        val tDt = ((clampedDt - 15f) / 35f).coerceIn(0f, 1f)
        val rawAlpha = 0.30f + (0.90f - 0.30f) * tDt
        val ramp = if (elapsedMs < 400f) 0.25f else ((elapsedMs - 400f) / 700f).coerceIn(0.25f, 1f)
        val alpha = (rawAlpha * ramp).coerceIn(0.05f, 0.90f)

        smoothX += (deltaX - smoothX) * alpha
        smoothY += (deltaY - smoothY) * alpha

        // Adaptive SLERP auto-centering: slower while actively tilting (reaches full ±1.0), faster when resting (matches swipe release decay)
        val motionRate = (abs(sinX - prevSinX) + abs(sinY - prevSinY)) / (clampedDt * 0.001f)
        prevSinX = sinX
        prevSinY = sinY
        val motionBlend = ((motionRate - 0.10f) / 0.45f).coerceIn(0f, 1f)
        val slerpRatePerMs = 0.0024f + (0.0007f - 0.0024f) * motionBlend
        val slerpT = (clampedDt * slerpRatePerMs).coerceIn(0f, 1f)
        slerpMatrixInPlace(ref, matrix, slerpT)

        return smoothX.coerceIn(-1f, 1f) to smoothY.coerceIn(-1f, 1f)
    }

    private fun slerpMatrixInPlace(ref: FloatArray, target: FloatArray, t: Float) {
        if (t <= 0f) return
        val q0 = matrixToQuaternion(ref)
        val q1 = matrixToQuaternion(target)

        var dot = q0[0] * q1[0] + q0[1] * q1[1] + q0[2] * q1[2] + q0[3] * q1[3]
        if (dot < 0f) {
            q1[0] = -q1[0]
            q1[1] = -q1[1]
            q1[2] = -q1[2]
            q1[3] = -q1[3]
            dot = -dot
        }

        val s0: Float
        val s1: Float
        if (dot > 0.9995f) {
            s0 = 1f - t
            s1 = t
        } else {
            val theta0 = acos(dot.coerceIn(-1f, 1f))
            val sinTheta0 = sin(theta0)
            val theta = theta0 * t
            val sinTheta = sin(theta)
            s0 = sin(theta0 - theta) / sinTheta0
            s1 = sinTheta / sinTheta0
        }

        var w = s0 * q0[0] + s1 * q1[0]
        var x = s0 * q0[1] + s1 * q1[1]
        var y = s0 * q0[2] + s1 * q1[2]
        var z = s0 * q0[3] + s1 * q1[3]
        val norm = sqrt(w * w + x * x + y * y + z * z)
        if (norm > 1e-6f) {
            val inv = 1f / norm
            w *= inv
            x *= inv
            y *= inv
            z *= inv
        }
        quaternionToMatrix(w, x, y, z, ref)
    }

    private fun matrixToQuaternion(m: FloatArray): FloatArray {
        val trace = m[0] + m[4] + m[8]
        val w: Float
        val x: Float
        val y: Float
        val z: Float
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            w = 0.25f * s
            x = (m[7] - m[5]) / s
            y = (m[2] - m[6]) / s
            z = (m[3] - m[1]) / s
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1f + m[0] - m[4] - m[8]) * 2f
            w = (m[7] - m[5]) / s
            x = 0.25f * s
            y = (m[1] + m[3]) / s
            z = (m[2] + m[6]) / s
        } else if (m[4] > m[8]) {
            val s = sqrt(1f + m[4] - m[0] - m[8]) * 2f
            w = (m[2] - m[6]) / s
            x = (m[1] + m[3]) / s
            y = 0.25f * s
            z = (m[5] + m[7]) / s
        } else {
            val s = sqrt(1f + m[8] - m[0] - m[4]) * 2f
            w = (m[3] - m[1]) / s
            x = (m[2] + m[6]) / s
            y = (m[5] + m[7]) / s
            z = 0.25f * s
        }
        return floatArrayOf(w, x, y, z)
    }

    private fun quaternionToMatrix(w: Float, x: Float, y: Float, z: Float, out: FloatArray) {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        out[0] = 1f - 2f * (yy + zz)
        out[1] = 2f * (xy - wz)
        out[2] = 2f * (xz + wy)
        out[3] = 2f * (xy + wz)
        out[4] = 1f - 2f * (xx + zz)
        out[5] = 2f * (yz - wx)
        out[6] = 2f * (xz - wy)
        out[7] = 2f * (yz + wx)
        out[8] = 1f - 2f * (xx + yy)
    }
}
