package com.generativemascot.app.physics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.atan2

data class PhoneSample(val tiltX: Float = 0f, val tiltY: Float = 0f, val accelerationX: Float = 0f, val accelerationY: Float = 0f, val gravityX: Float = 0f, val gravityY: Float = 0f)

class PhoneMotion(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val acceleration = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private var reference: FloatArray? = null
    private var referenceGravity: Pair<Float, Float>? = null
    private var previousRotation = -1
    private var running = false
    @Volatile var sample = PhoneSample(); private set
    val available get() = rotation != null && acceleration != null && gravity != null
    fun start() {
        if (running || !available) return
        running = true; reference = null; referenceGravity = null; sample = PhoneSample()
        manager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME)
        manager.registerListener(this, acceleration, SensorManager.SENSOR_DELAY_GAME)
        manager.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME)
    }
    fun stop() { manager.unregisterListener(this); running = false; reference = null; referenceGravity = null; sample = PhoneSample() }
    @Suppress("DEPRECATION")
    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.values.any { !it.isFinite() }) return
        val displayRotation = windows.defaultDisplay.rotation
        if (displayRotation != previousRotation) { reference = null; referenceGravity = null; previousRotation = displayRotation; sample = PhoneSample() }
        fun screen(x: Float, y: Float): Pair<Float, Float> = when (displayRotation) {
            Surface.ROTATION_90 -> -y to x
            Surface.ROTATION_180 -> -x to -y
            Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            val (x, screenY) = screen(event.values[0] / SensorManager.GRAVITY_EARTH, event.values[1] / SensorManager.GRAVITY_EARTH)
            val y = -screenY
            val baseline = referenceGravity ?: (x to y).also { referenceGravity = it }
            sample = sample.copy(gravityX = sample.gravityX + .2f * ((x - baseline.first).coerceIn(-1f, 1f) - sample.gravityX),
                gravityY = sample.gravityY + .2f * ((y - baseline.second).coerceIn(-1f, 1f) - sample.gravityY))
        } else if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val (x, y) = screen(event.values[0], event.values[1])
            sample = sample.copy(accelerationX = x.coerceIn(-12f, 12f), accelerationY = -y.coerceIn(-12f, 12f))
        } else {
            val q = FloatArray(4); SensorManager.getQuaternionFromVector(q, event.values)
            val r = reference ?: q.copyOf().also { reference = it }
            // inverse(reference) * current; no integration drift or Euler-angle gimbal lock.
            var w = r[0]*q[0] + r[1]*q[1] + r[2]*q[2] + r[3]*q[3]
            var x = r[0]*q[1] - r[1]*q[0] - r[2]*q[3] + r[3]*q[2]
            var y = r[0]*q[2] + r[1]*q[3] - r[2]*q[0] - r[3]*q[1]
            if (w < 0) { w = -w; x = -x; y = -y }
            val (xx, yy) = screen(2f * atan2(y, w), 2f * atan2(x, w))
            // Sensor filtering is separate from the physical spring response.
            sample = sample.copy(tiltX = sample.tiltX + .35f * (xx.coerceIn(-.7f, .7f) - sample.tiltX), tiltY = sample.tiltY + .35f * (yy.coerceIn(-.7f, .7f) - sample.tiltY))
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
