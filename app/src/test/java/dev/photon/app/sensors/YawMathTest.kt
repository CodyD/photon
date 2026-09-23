package dev.photon.app.sensors

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class YawMathTest {

    /** Rotation matrix for a phone held upright in portrait, camera facing [headingDeg] (0 = north, 90 = east). */
    private fun uprightFacing(headingDeg: Double): FloatArray {
        val h = Math.toRadians(headingDeg)
        // Columns: device X (right), device Y (up), device Z (toward the user, opposite the camera).
        val xAxis = doubleArrayOf(cos(h), -sin(h), 0.0)
        val yAxis = doubleArrayOf(0.0, 0.0, 1.0)
        val zAxis = doubleArrayOf(-sin(h), -cos(h), 0.0)
        return floatArrayOf(
            xAxis[0].toFloat(), yAxis[0].toFloat(), zAxis[0].toFloat(),
            xAxis[1].toFloat(), yAxis[1].toFloat(), zAxis[1].toFloat(),
            xAxis[2].toFloat(), yAxis[2].toFloat(), zAxis[2].toFloat(),
        )
    }

    @Test
    fun uprightPhoneGivesStableYaw() {
        for (heading in listOf(0.0, 45.0, 90.0, -135.0)) {
            val o = YawMath.fromRotationMatrix(uprightFacing(heading))
            assertEquals(heading, o.yawDeg, 1e-4)
            assertEquals(0.0, o.pitchDeg, 1e-4)
            assertEquals(0.0, o.rollDeg, 1e-4)
        }
    }

    @Test
    fun wrapKeepsRange() {
        assertEquals(-170.0, YawMath.wrap(190.0), 1e-9)
        assertEquals(170.0, YawMath.wrap(-190.0), 1e-9)
        assertEquals(0.0, YawMath.wrap(360.0), 1e-9)
    }

    @Test
    fun unwrapperCrossesSeam() {
        val u = YawUnwrapper()
        u.update(170.0)
        u.update(179.0)
        assertEquals(189.0, u.update(-171.0), 1e-9)
        assertEquals(160.0, u.update(160.0), 1e-9)
    }
}
