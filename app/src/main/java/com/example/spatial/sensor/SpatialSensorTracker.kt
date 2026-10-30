package com.example.spatial.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Sensor tracker for device tilt (pitch & roll) using Sensor Fusion.
 */
class SpatialSensorTracker(
    context: Context,
    private val onSensorUpdate: (tiltX: Float, tiltY: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val relativeTilt = RelativeTilt()

    private var initialPitch = 0.0f
    private var initialRoll = 0.0f
    private var isCalibrated = false

    var isEnabled = true

    fun start() {
        if (sensorManager == null) return
        resetCalibration()
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun resetCalibration() {
        isCalibrated = false
        relativeTilt.reset()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isEnabled || event == null) return

        var pitch = 0.0f
        var roll = 0.0f

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val (tiltX, tiltY) = relativeTilt.update(rotationMatrix)
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

