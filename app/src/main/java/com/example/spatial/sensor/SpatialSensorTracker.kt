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
    private val onSensorUpdate: (tiltX: Float, tiltY: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gameRotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val relativeTilt = RelativeTilt()
    private var lastTimestampNs: Long = 0L

    private var initialPitch = 0.0f
    private var initialRoll = 0.0f
    private var isCalibrated = false

    var isEnabled = true

    fun start() {
        if (sensorManager == null) return
        resetCalibration()
        val primaryRotation = gameRotationSensor ?: rotationSensor
        if (primaryRotation != null) {
            sensorManager.registerListener(this, primaryRotation, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun resetCalibration() {
        isCalibrated = false
        lastTimestampNs = 0L
        relativeTilt.reset()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isEnabled || event == null) return

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

