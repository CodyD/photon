package dev.photon.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {

    private fun keptProgress(gate: FrameGate, yaws: List<Double>): List<Double> =
        yaws.mapNotNull { (gate.offer(it) as? FrameGate.Decision.Keep)?.progressDeg }

    @Test
    fun keepsOneFramePerStep() {
        val gate = FrameGate(stepDeg = 1.0, targetArcDeg = 60.0, maxFrames = 180)
        val kept = keptProgress(gate, (0..40).map { 100.0 + it * 0.25 })
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), kept)
        assertEquals(1, gate.direction)
    }

    @Test
    fun negativeSweepReportsPositiveProgress() {
        val gate = FrameGate(1.0, 60.0, 180)
        val kept = keptProgress(gate, listOf(10.0, 9.5, 8.9, 7.8, 6.0))
        assertEquals(-1, gate.direction)
        assertEquals(listOf(0.0, 1.1, 2.2, 4.0), kept.map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun backtrackingProducesNoFrames() {
        val gate = FrameGate(1.0, 60.0, 180)
        val kept = keptProgress(gate, listOf(0.0, 1.0, 2.0, 1.0, 0.0, 1.5, 2.5, 3.0))
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), kept)
    }

    @Test
    fun stopsAtTargetArc() {
        val gate = FrameGate(1.0, 5.0, 180)
        keptProgress(gate, (0..10).map { it.toDouble() })
        assertTrue(gate.isDone)
        assertEquals(FrameGate.Decision.Skip, gate.offer(11.0))
    }

    @Test
    fun stopsAtMaxFrames() {
        val gate = FrameGate(1.0, 90.0, 3)
        val kept = keptProgress(gate, (0..10).map { it.toDouble() })
        assertEquals(3, kept.size)
        assertTrue(gate.isDone)
    }
}
