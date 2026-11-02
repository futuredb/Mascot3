package com.generativemascot.app.physics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager

class PhoneMotion(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val acceleration = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val orientation = RelativeOrientation()
    private var previousRotation = -1
    private var running = false
    @Volatile var rotationEvents = 0; private set
    @Volatile var accelerationEvents = 0; private set
    @Volatile var sample = PhoneSample(); private set
    @Volatile var calibrated = false; private set
    val available get() = rotation != null && acceleration != null
    fun recalibrate() {
        orientation.reset()
        calibrated = false; sample = PhoneSample()
    }
    fun start(): Boolean {
        if (running) return true
        if (!available) return false
        running = true; recalibrate()
        val registered=manager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME) &&
            manager.registerListener(this, acceleration, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) stop()
        return registered
    }
    fun stop() { manager.unregisterListener(this); running = false; recalibrate() }
    @Suppress("DEPRECATION")
    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.values.any { !it.isFinite() }) return
        val displayRotation = windows.defaultDisplay.rotation
        if (displayRotation != previousRotation) { recalibrate(); previousRotation = displayRotation }
        fun screen(x: Float, y: Float): Pair<Float, Float> = when (displayRotation) {
            Surface.ROTATION_90 -> -y to x
            Surface.ROTATION_180 -> -x to -y
            Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            accelerationEvents++
            if (!calibrated) return
            val (x, y) = screen(event.values[0], event.values[1])
            sample = sample.copy(accelerationX = x.coerceIn(-12f, 12f), accelerationY = -y.coerceIn(-12f, 12f),
                accelerationZ = event.values[2].coerceIn(-12f, 12f), accelerationTimestamp = event.timestamp)
        } else {
            rotationEvents++
            val q = FloatArray(4); SensorManager.getQuaternionFromVector(q, event.values)
            val relative = orientation.update(q, event.timestamp, displayRotation)
            calibrated = orientation.calibrated
            if (!calibrated) return
            // Android already fuses gyro + accelerometer. The visual springs do
            // the remaining smoothing; another sensor filter adds input lag.
            sample = sample.copy(tiltX = relative.first, tiltY = relative.second)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
