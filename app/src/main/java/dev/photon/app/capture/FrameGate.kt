package dev.photon.app.capture

import kotlin.math.abs
import kotlin.math.sign

/**
 * Decides which camera frames to keep during a sweep, based on yaw rather than time.
 *
 * The first offered frame is always kept at progress 0. Sweep direction is fixed once yaw moves
 * one step away from the start. After that a frame is kept each time progress along that direction
 * advances by at least [stepDeg]; moving backwards never produces frames.
 */
class FrameGate(
    private val stepDeg: Double,
    private val targetArcDeg: Double,
    private val maxFrames: Int,
) {
    sealed interface Decision {
        data class Keep(val progressDeg: Double) : Decision
        data object Skip : Decision
    }

    private var startYaw: Double? = null
    private var lastKept = 0.0
    private var kept = 0

    /** +1 or -1 once known, 0 before. */
    var direction = 0
        private set

    /** Furthest progress reached along the sweep direction, in degrees. */
    var progressDeg = 0.0
        private set

    val isDone: Boolean get() = kept >= maxFrames || progressDeg >= targetArcDeg

    fun offer(yawDeg: Double): Decision {
        val start = startYaw
        if (start == null) {
            startYaw = yawDeg
            kept = 1
            return Decision.Keep(0.0)
        }
        if (isDone) return Decision.Skip

        val delta = yawDeg - start
        if (direction == 0) {
            if (abs(delta) < stepDeg) return Decision.Skip
            direction = sign(delta).toInt()
        }
        val progress = delta * direction
        if (progress > progressDeg) progressDeg = progress
        if (progress < lastKept + stepDeg) return Decision.Skip

        lastKept = progress
        kept++
        return Decision.Keep(progress)
    }
}
