package dev.photon.app.sensors

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Camera orientation in degrees. Yaw is about world up, pitch is above the horizon, roll is about the view axis. */
data class Orientation(val yawDeg: Double, val pitchDeg: Double, val rollDeg: Double)

object YawMath {

    private const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * Derives camera orientation from a row-major 3x3 rotation matrix (device -> world, as produced by
     * SensorManager.getRotationMatrixFromVector).
     *
     * Uses the camera's forward vector (device -Z) projected onto the horizontal plane rather than
     * SensorManager.getOrientation(), whose azimuth is unstable when the phone is held upright.
     */
    fun fromRotationMatrix(r: FloatArray): Orientation {
        // Device -Z in world coordinates is the negated third column.
        val fx = -r[2].toDouble()
        val fy = -r[5].toDouble()
        val fz = -r[8].toDouble()

        val horizontal = hypot(fx, fy)
        val yaw = atan2(fx, fy) * RAD_TO_DEG
        val pitch = atan2(fz, horizontal) * RAD_TO_DEG

        // Device +Y (top of the phone) in world coordinates is the second column.
        val ux = r[1].toDouble()
        val uy = r[4].toDouble()
        val uz = r[7].toDouble()

        // Reference frame perpendicular to forward: right = f x worldUp, up = right x f.
        var rx = fy
        var ry = -fx
        val rz = 0.0
        val rLen = sqrt(rx * rx + ry * ry)
        val roll = if (rLen < 1e-6) {
            0.0
        } else {
            rx /= rLen
            ry /= rLen
            val upX = ry * fz - rz * fy
            val upY = rz * fx - rx * fz
            val upZ = rx * fy - ry * fx
            atan2(ux * rx + uy * ry + uz * rz, ux * upX + uy * upY + uz * upZ) * RAD_TO_DEG
        }

        return Orientation(yaw, pitch, roll)
    }

    /** Wraps an angle into [-180, 180). */
    fun wrap(deg: Double): Double {
        var d = (deg + 180.0) % 360.0
        if (d < 0) d += 360.0
        return d - 180.0
    }
}

/** Turns a stream of wrapped yaw readings into a continuous angle that can exceed +-180. */
class YawUnwrapper {
    private var last: Double? = null
    private var unwrapped = 0.0

    fun update(wrappedDeg: Double): Double {
        val prev = last
        unwrapped = if (prev == null) wrappedDeg else unwrapped + YawMath.wrap(wrappedDeg - prev)
        last = wrappedDeg
        return unwrapped
    }
}
