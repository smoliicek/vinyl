package com.smoliicek.vinyl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class TonearmGeometryTest {

    private fun needleRadius(degrees: Float): Float = needlePosition(degrees).let { (x, y) -> hypot(x, y) }

    @Test
    fun startOfTrackPutsNeedleOnOuterGroove() {
        assertEquals(OUTER_GROOVE, needleRadius(armAngleForProgress(0f)), 0.001f)
    }

    @Test
    fun endOfTrackPutsNeedleOnInnerGroove() {
        assertEquals(INNER_GROOVE, needleRadius(armAngleForProgress(1f)), 0.001f)
    }

    @Test
    fun needleMovesInwardSteadily() {
        val radii = (0..10).map { needleRadius(armAngleForProgress(it / 10f)) }
        radii.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b < a) }
    }

    @Test
    fun restPositionIsBesideTheDisc() {
        assertTrue(needleRadius(0f) > 1f)
    }

    @Test
    fun playingAnglesSwingClockwiseFromRest() {
        val outer = armAngleForProgress(0f)
        val inner = armAngleForProgress(1f)
        assertTrue(outer > 0f)
        assertTrue(inner > outer)
        // Sanity: a real arm sweeps a few tens of degrees, not half a turn.
        assertTrue(inner < 60f)
    }

    @Test
    fun clampsProgress() {
        assertEquals(armAngleForProgress(0f), armAngleForProgress(-0.5f), 0f)
        assertEquals(armAngleForProgress(1f), armAngleForProgress(2f), 0f)
    }
}
