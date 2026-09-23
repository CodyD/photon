package dev.photon.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One orientation reading with an unwrapped yaw. Timestamp is in the SensorEvent clock (elapsedRealtimeNanos). */
data class OrientationSample(
    val timestampNs: Long,
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
)

/**
 * Listens to the game rotation vector (gyro + accel, no magnetometer) and publishes camera orientation.
 * Keeps a short history so camera frames can be matched to the orientation at their exposure time.
 */
class OrientationSampler(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = sensor != null

    private val rotation = FloatArray(9)
    private val unwrapper = YawUnwrapper()

    private val history = ArrayDeque<OrientationSample>()
    private val historyLock = Any()

    private val _latest = MutableStateFlow<OrientationSample?>(null)
    val latest: StateFlow<OrientationSample?> = _latest.asStateFlow()

    fun start() {
        val s = sensor ?: return
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val o = YawMath.fromRotationMatrix(rotation)
        val sample = OrientationSample(
            timestampNs = event.timestamp,
            yawDeg = unwrapper.update(o.yawDeg),
            pitchDeg = o.pitchDeg,
            rollDeg = o.rollDeg,
        )
        synchronized(historyLock) {
            history.addLast(sample)
            while (history.size > 1 && sample.timestampNs - history.first().timestampNs > HISTORY_NS) {
                history.removeFirst()
            }
        }
        _latest.value = sample
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Orientation at [timestampNs], linearly interpolated from history. Null if no samples yet. */
    fun sampleAt(timestampNs: Long): OrientationSample? = synchronized(historyLock) {
        if (history.isEmpty()) return null
        if (timestampNs <= history.first().timestampNs) return history.first()
        if (timestampNs >= history.last().timestampNs) return history.last()
        // History is short (~2 s), linear scan from the end is fine.
        var i = history.size - 1
        while (i > 0 && history[i - 1].timestampNs > timestampNs) i--
        val a = history[i - 1]
        val b = history[i]
        val span = (b.timestampNs - a.timestampNs).toDouble()
        val t = if (span <= 0) 0.0 else (timestampNs - a.timestampNs) / span
        OrientationSample(
            timestampNs = timestampNs,
            yawDeg = a.yawDeg + (b.yawDeg - a.yawDeg) * t,
            pitchDeg = a.pitchDeg + (b.pitchDeg - a.pitchDeg) * t,
            rollDeg = a.rollDeg + YawMath.wrap(b.rollDeg - a.rollDeg) * t,
        )
    }

    /** Angular speed of yaw in degrees per second over the last ~150 ms. */
    fun yawSpeedDegPerSec(): Double = synchronized(historyLock) {
        if (history.size < 2) return 0.0
        val last = history.last()
        var i = history.size - 1
        while (i > 0 && last.timestampNs - history[i].timestampNs < SPEED_WINDOW_NS) i--
        val first = history[i]
        val dt = (last.timestampNs - first.timestampNs) / 1e9
        if (dt <= 0) 0.0 else (last.yawDeg - first.yawDeg) / dt
    }

    private companion object {
        const val HISTORY_NS = 2_000_000_000L
        const val SPEED_WINDOW_NS = 150_000_000L
    }
}
