package com.smoliicek.vinyl

import android.graphics.Bitmap

data class NowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val art: Bitmap?,
    val isPlaying: Boolean,
    /** From METADATA_KEY_DURATION; null when the player doesn't say. */
    val durationMs: Long? = null,
    /** PlaybackState position as of [lastPositionUpdateTime] (an elapsedRealtime timestamp). */
    val position: Long = 0L,
    val lastPositionUpdateTime: Long = 0L,
    val playbackSpeed: Float = 0f,
)

/**
 * Where playback is at [nowElapsedRealtime]: the last reported position, advanced by the time since that
 * report while playing, clamped to [0, duration].
 */
fun estimatePosition(state: NowPlaying, nowElapsedRealtime: Long): Long {
    val advanced = if (state.isPlaying && state.lastPositionUpdateTime > 0) {
        state.position + ((nowElapsedRealtime - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
    } else {
        state.position
    }
    return advanced.coerceIn(0L, state.durationMs ?: Long.MAX_VALUE)
}

/** m:ss, or h:mm:ss from an hour up. */
fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
