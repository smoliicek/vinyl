package com.smoliicek.vinyl

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TICK_MS = 250
private const val SNAP_BACK_MS = 2_000f

/**
 * Wavy progress bar plus "elapsed / total". Hidden when the player doesn't report a duration.
 * The wave flattens when paused, on the same easing as the disc spinning down.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongProgress(nowPlaying: NowPlaying, color: Color, modifier: Modifier = Modifier) {
    val duration = nowPlaying.durationMs ?: return
    val track = listOf(nowPlaying.title, nowPlaying.artist, nowPlaying.album, duration)

    val positionMs = remember { Animatable(0f) }
    val lastTrack = remember { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(nowPlaying) {
        val reported = estimatePosition(nowPlaying, SystemClock.elapsedRealtime()).toFloat()
        // A new track or a jump backwards snaps; small forward corrections may glide.
        if (track != lastTrack.value || reported < positionMs.value - SNAP_BACK_MS) {
            positionMs.snapTo(reported)
        } else {
            positionMs.animateTo(reported, tween(400))
        }
        lastTrack.value = track
        // animateTo runs on the frame clock, so this stops by itself whenever nothing is shown
        // (activity stopped, dream ended) and resumes when it is.
        while (nowPlaying.isPlaying) {
            val next = estimatePosition(nowPlaying, SystemClock.elapsedRealtime() + TICK_MS).toFloat()
            positionMs.animateTo(next, tween(TICK_MS, easing = LinearEasing))
        }
    }

    val waviness by animateFloatAsState(
        targetValue = if (nowPlaying.isPlaying) 1f else 0f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "waviness",
    )
    // Only recompose the label when the displayed second changes, not every frame.
    val elapsedSeconds by remember { derivedStateOf { (positionMs.value / 1000).toLong() } }

    Column(modifier) {
        LinearWavyProgressIndicator(
            progress = { positionMs.value / duration },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            trackColor = Color.White.copy(alpha = 0.2f),
            amplitude = { progress -> WavyProgressIndicatorDefaults.indicatorAmplitude(progress) * waviness },
            // Half the default: one wavelength every 2 seconds, calmer for a screensaver.
            waveSpeed = WavyProgressIndicatorDefaults.LinearDeterminateWavelength / 2,
        )
        Spacer(Modifier.height(10.dp))
        BasicText(
            text = "${formatPlaybackTime(elapsedSeconds * 1000)} / ${formatPlaybackTime(duration)}",
            style = TextStyle(
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 18.sp,
                fontFeatureSettings = "tnum", // tabular figures, so the digits don't jitter
            ),
        )
    }
}
