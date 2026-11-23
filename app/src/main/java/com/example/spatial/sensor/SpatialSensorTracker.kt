package com.example.spatial.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Sensor tracker for device tilt using Game Rotation Vector (no magnetometer drift),
 * falling back to Rotation Vector or Accelerometer, matching reference APK (`Lqc1`, `Lrc1`, `Lpc1`).
 */
class SpatialSensorTracker(
    private val context: Context,
    private val onLinearAcceleration: ((ax: Float, ay: Float) -> Unit)? = null,
    private val onSensorUpdate: (tiltX: Float, tiltY: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gameRotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val relativeTilt = RelativeTilt()

    private var initialPitch = 0.0f
    private var initialRoll = 0.0f
    private var isCalibrated = false
    private var gravityX = 0.0f
    private var gravityY = 0.0f
    private var gravityZ = 0.0f
    private var hasGravityEstimate = false

    var isEnabled = true

    val hasSensor: Boolean
        get() = gameRotationSensor != null || rotationSensor != null || accelSensor != null

    companion object {
        fun hasTiltSensor(context: Context): Boolean {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
            return sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
                sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        }
    }

    fun start() {
        if (sensorManager == null) return
        resetCalibration()
        val primaryRotation = gameRotationSensor ?: rotationSensor
        if (primaryRotation != null) {
            sensorManager.registerListener(this, primaryRotation, SensorManager.SENSOR_DELAY_GAME)
            if (onLinearAcceleration != null) {
                val shakeSensor = linearAccelSensor ?: accelSensor
                if (shakeSensor != null) {
                    sensorManager.registerListener(this, shakeSensor, SensorManager.SENSOR_DELAY_GAME)
                }
            }
        } else if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun resetCalibration() {
        isCalibrated = false
        hasGravityEstimate = false
        relativeTilt.reset()
    }

    private fun dispatchLinearAccel(rawX: Float, rawY: Float) {
        val cb = onLinearAcceleration ?: return
        val (ax, ay) = when (currentDisplayRotation()) {
            Surface.ROTATION_90 -> -rawY to rawX
            Surface.ROTATION_180 -> -rawX to -rawY
            Surface.ROTATION_270 -> rawY to -rawX
            else -> rawX to rawY
        }
        cb(ax, ay)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isEnabled || event == null) return

        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            dispatchLinearAccel(event.values[0], event.values[1])
            return
        }

        var pitch = 0.0f
        var roll = 0.0f

        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_ROTATION_VECTOR
        ) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val matrix = remapForDisplay(rotationMatrix)
            val (tiltX, tiltY) = relativeTilt.update(matrix)
            onSensorUpdate(tiltX, tiltY)
            return
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            if (!hasGravityEstimate) {
                gravityX = ax
                gravityY = ay
                gravityZ = az
                hasGravityEstimate = true
            } else {
                val alpha = 0.8f
                gravityX = alpha * gravityX + (1f - alpha) * ax
                gravityY = alpha * gravityY + (1f - alpha) * ay
                gravityZ = alpha * gravityZ + (1f - alpha) * az
            }
            if (linearAccelSensor == null) {
                dispatchLinearAccel(ax - gravityX, ay - gravityY)
            }
            // If a rotation-vector sensor is already driving tilt, don't overwrite tilt with accel
            if (gameRotationSensor != null || rotationSensor != null) {
                return
            }
            roll = atan2(-ax.toDouble(), sqrt((ay * ay + az * az).toDouble())).toFloat()
            pitch = atan2(ay.toDouble(), az.toDouble()).toFloat()
        }

        if (!isCalibrated) {
            initialPitch = pitch
            initialRoll = roll
            isCalibrated = true
        }

        // Relative delta in radians
        val deltaPitch = pitch - initialPitch
        val deltaRoll = roll - initialRoll

        // Normalize to [-1.0, 1.0] range (approx +/- 30 degrees = 0.52 rad)
        val maxRad = 0.55f
        val normTiltX = (deltaRoll / maxRad).coerceIn(-1.0f, 1.0f)
        val normTiltY = (-deltaPitch / maxRad).coerceIn(-1.0f, 1.0f)

        onSensorUpdate(normTiltX, normTiltY)
    }

    private fun remapForDisplay(src: FloatArray): FloatArray {
        val rotation = currentDisplayRotation()
        return when (rotation) {
            Surface.ROTATION_90 -> {
                SensorManager.remapCoordinateSystem(
                    src,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    remappedMatrix
                )
                remappedMatrix
            }
            Surface.ROTATION_180 -> {
                SensorManager.remapCoordinateSystem(
                    src,
                    SensorManager.AXIS_MINUS_X,
                    SensorManager.AXIS_MINUS_Y,
                    remappedMatrix
                )
                remappedMatrix
            }
            Surface.ROTATION_270 -> {
                SensorManager.remapCoordinateSystem(
                    src,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X,
                    remappedMatrix
                )
                remappedMatrix
            }
            else -> src
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

