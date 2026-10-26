package com.smoliicek.vinyl

import org.junit.Assert.assertEquals
import org.junit.Test

class EstimatePositionTest {

    private fun state(
        isPlaying: Boolean = true,
        position: Long = 30_000,
        lastUpdate: Long = 1_000_000,
        speed: Float = 1f,
        duration: Long? = 200_000,
    ) = NowPlaying(
        title = "t",
        artist = null,
        album = null,
        art = null,
        isPlaying = isPlaying,
        durationMs = duration,
        position = position,
        lastPositionUpdateTime = lastUpdate,
        playbackSpeed = speed,
    )

    @Test
    fun advancesWithElapsedTimeWhilePlaying() {
        assertEquals(35_000, estimatePosition(state(), 1_005_000))
    }

    @Test
    fun scalesByPlaybackSpeed() {
        assertEquals(40_000, estimatePosition(state(speed = 2f), 1_005_000))
        assertEquals(32_500, estimatePosition(state(speed = 0.5f), 1_005_000))
    }

    @Test
    fun staysPutWhilePaused() {
        assertEquals(30_000, estimatePosition(state(isPlaying = false), 1_500_000))
    }

    @Test
    fun clampsToDuration() {
        assertEquals(200_000, estimatePosition(state(), 1_500_000))
    }

    @Test
    fun neverGoesNegative() {
        assertEquals(0, estimatePosition(state(position = -500, isPlaying = false), 1_000_000))
        // A report stamped after "now" (clock skew) can't push it below zero either.
        assertEquals(0, estimatePosition(state(position = 1_000), 990_000))
    }

    @Test
    fun noUpperClampWithoutDuration() {
        assertEquals(530_000, estimatePosition(state(duration = null), 1_500_000))
    }

    @Test
    fun ignoresMissingUpdateTime() {
        assertEquals(30_000, estimatePosition(state(lastUpdate = 0), 1_500_000))
    }
}

class FormatPlaybackTimeTest {

    @Test
    fun minutesAndSeconds() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("0:09", formatPlaybackTime(9_999))
        assertEquals("3:05", formatPlaybackTime(185_000))
        assertEquals("59:59", formatPlaybackTime(3_599_000))
    }

    @Test
    fun hoursFromAnHourUp() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
    }
}
