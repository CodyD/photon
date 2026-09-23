package dev.photon.app.viewer

/**
 * Maps the viewer's live yaw to a position in a capture's sweep.
 *
 * The reference yaw is taken as the middle of the arc. When the phone turns past either end of the
 * arc the reference slides along with it (a clutch), so turning back always responds immediately
 * instead of first having to unwind the overshoot.
 */
class YawFrameMapper(
    private val frameYaws: List<Double>,
    private val direction: Int,
) {
    val arcDeg: Double = frameYaws.lastOrNull() ?: 0.0

    var sensitivity = 1.5
    var inverted = false

    private var referenceYaw: Double? = null

    fun recenter() {
        referenceYaw = null
    }

    /** Shifts playback by [deltaAngleDeg] along the sweep, e.g. from a touch drag. */
    fun nudge(deltaAngleDeg: Double) {
        val ref = referenceYaw ?: return
        referenceYaw = ref - deltaAngleDeg / gain()
    }

    private fun gain() = sensitivity * direction * (if (inverted) -1 else 1)

    /** Returns the sweep angle in degrees (0..arcDeg) for the current device yaw. */
    fun angleFor(deviceYawDeg: Double): Double {
        val gain = gain()
        val ref = referenceYaw ?: (deviceYawDeg - (arcDeg / 2) / gain).also { referenceYaw = it }
        val raw = (deviceYawDeg - ref) * gain
        val clamped = raw.coerceIn(0.0, arcDeg)
        if (raw != clamped) referenceYaw = deviceYawDeg - clamped / gain
        return clamped
    }

    /** Index of the frame captured nearest to [angleDeg]. */
    fun frameIndexFor(angleDeg: Double): Int {
        if (frameYaws.isEmpty()) return 0
        var lo = 0
        var hi = frameYaws.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (frameYaws[mid] < angleDeg) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && angleDeg - frameYaws[lo - 1] < frameYaws[lo] - angleDeg) lo - 1 else lo
    }
}
