package dev.photon.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class YawFrameMapperTest {

    private val yaws = (0..60).map { it.toDouble() }

    @Test
    fun startsInMiddleOfArc() {
        val m = YawFrameMapper(yaws, direction = 1)
        assertEquals(30.0, m.angleFor(200.0), 1e-9)
    }

    @Test
    fun followsYawWithSensitivity() {
        val m = YawFrameMapper(yaws, direction = 1).apply { sensitivity = 2.0 }
        m.angleFor(0.0)
        assertEquals(40.0, m.angleFor(5.0), 1e-9)
        assertEquals(20.0, m.angleFor(-5.0), 1e-9)
    }

    @Test
    fun directionAndInvertFlipSign() {
        val m = YawFrameMapper(yaws, direction = -1).apply { sensitivity = 1.0 }
        m.angleFor(0.0)
        assertEquals(20.0, m.angleFor(10.0), 1e-9)
        m.inverted = true
        m.recenter()
        m.angleFor(0.0)
        assertEquals(40.0, m.angleFor(10.0), 1e-9)
    }

    @Test
    fun clutchesAtArcEnds() {
        val m = YawFrameMapper(yaws, direction = 1).apply { sensitivity = 1.0 }
        m.angleFor(0.0)
        assertEquals(60.0, m.angleFor(100.0), 1e-9)
        // Turning back responds immediately instead of unwinding 70 degrees of overshoot.
        assertEquals(55.0, m.angleFor(95.0), 1e-9)
    }

    @Test
    fun picksNearestFrame() {
        val m = YawFrameMapper(listOf(0.0, 1.0, 2.5, 4.0), direction = 1)
        assertEquals(0, m.frameIndexFor(-3.0))
        assertEquals(1, m.frameIndexFor(1.6))
        assertEquals(2, m.frameIndexFor(1.9))
        assertEquals(3, m.frameIndexFor(9.0))
    }
}
