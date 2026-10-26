package com.smoliicek.vinyl

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val art: Bitmap?,
    val isPlaying: Boolean,
)

private const val TAG = "VinylDebug"

private val ART_BITMAP_KEYS = listOf(
    MediaMetadata.METADATA_KEY_ALBUM_ART,
    MediaMetadata.METADATA_KEY_ART,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON,
)

private val ART_URI_KEYS = listOf(
    MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
    MediaMetadata.METADATA_KEY_ART_URI,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
)

/**
 * Tracks active media sessions and exposes the one worth showing.
 * Everything runs on the main thread; callers pair [start] with [stop].
 */
class NowPlayingRepository(context: Context) {

    private val app = context.applicationContext
    private val sessionManager = app.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(app, NowPlayingListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var scope = newScope()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _permissionMissing = MutableStateFlow(false)
    val permissionMissing: StateFlow<Boolean> = _permissionMissing.asStateFlow()

    private class Tracked(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var lastActivity: Long,
    )

    private val tracked = mutableMapOf<android.media.session.MediaSession.Token, Tracked>()
    private var started = false

    private var artUri: Uri? = null
    private var artFromUri: Bitmap? = null
    private var artJob: Job? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessionsChanged(controllers.orEmpty())
    }

    // When the listener permission is missing, wait for it to be granted instead of polling.
    private val listenerSettingObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (started && _permissionMissing.value) attach()
        }
    }

    fun start() {
        if (started) return
        started = true
        scope = newScope()
        app.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("enabled_notification_listeners"),
            false,
            listenerSettingObserver,
        )
        attach()
    }

    fun stop() {
        if (!started) return
        started = false
        app.contentResolver.unregisterContentObserver(listenerSettingObserver)
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        tracked.values.forEach { it.controller.unregisterCallback(it.callback) }
        tracked.clear()
        scope.cancel()
        artJob = null
        artUri = null
        artFromUri = null
        _nowPlaying.value = null
    }

    private fun attach() {
        try {
            val controllers = sessionManager.getActiveSessions(listenerComponent)
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            _permissionMissing.value = false
            onSessionsChanged(controllers)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification listener access not granted", e)
            _permissionMissing.value = true
            _nowPlaying.value = null
        }
    }

    private fun onSessionsChanged(controllers: List<MediaController>) {
        val incoming = controllers.associateBy { it.sessionToken }

        tracked.keys.filter { it !in incoming }.forEach { token ->
            tracked.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        }

        incoming.forEach { (token, controller) ->
            if (token in tracked) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    tracked[token]?.lastActivity = SystemClock.elapsedRealtime()
                    recompute()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    tracked[token]?.lastActivity = SystemClock.elapsedRealtime()
                    logMetadata(controller, metadata)
                    recompute()
                }

                override fun onSessionDestroyed() {
                    tracked.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
                    recompute()
                }
            }
            controller.registerCallback(callback, handler)
            val lastActivity = controller.playbackState?.lastPositionUpdateTime ?: 0L
            tracked[token] = Tracked(controller, callback, lastActivity)
            logMetadata(controller, controller.metadata)
        }

        Log.d(TAG, "Sessions: " + tracked.values.joinToString { describe(it.controller) })
        recompute()
    }

    private fun recompute() {
        val candidates = tracked.values.filter { it.controller.playbackState?.state != PlaybackState.STATE_ERROR }
        val chosen = candidates
            .filter { it.controller.playbackState?.state == PlaybackState.STATE_PLAYING }
            .maxByOrNull { it.lastActivity }
            ?: candidates
                .filter { it.controller.metadata != null }
                .maxByOrNull { it.lastActivity }

        val metadata = chosen?.controller?.metadata
        if (chosen == null || metadata == null) {
            setArtUri(null)
            _nowPlaying.value = null
            return
        }

        val bitmapArt = ART_BITMAP_KEYS.firstNotNullOfOrNull { metadata.getBitmap(it) }
        val uriArt = if (bitmapArt == null) {
            ART_URI_KEYS.firstNotNullOfOrNull { key -> metadata.getString(key)?.takeIf { it.isNotBlank() } }
                ?.let(Uri::parse)
        } else {
            null
        }
        setArtUri(uriArt)

        _nowPlaying.value = NowPlaying(
            title = metadata.text(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM),
            art = bitmapArt ?: artFromUri,
            isPlaying = chosen.controller.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    private fun setArtUri(uri: Uri?) {
        if (uri == artUri) return
        artUri = uri
        artFromUri = null
        artJob?.cancel()
        if (uri == null) return
        artJob = scope.launch {
            val request = ImageRequest.Builder(app)
                .data(uri)
                .allowHardware(false) // Palette needs to read pixels
                .size(1024)
                .build()
            val result = SingletonImageLoader.get(app).execute(request)
            if (result is SuccessResult) {
                artFromUri = result.image.toBitmap()
                recompute()
            } else {
                Log.w(TAG, "Could not load art from $uri: $result")
            }
        }
    }

    private fun logMetadata(controller: MediaController, metadata: MediaMetadata?) {
        if (metadata == null) {
            Log.d(TAG, "${controller.packageName}: metadata=null")
            return
        }
        val details = metadata.keySet().sorted().joinToString { key ->
            when {
                key in ART_BITMAP_KEYS -> "$key=bitmap(${metadata.getBitmap(key)?.let { "${it.width}x${it.height}" }})"
                key in ART_URI_KEYS -> "$key=${metadata.getString(key)}"
                else -> key
            }
        }
        Log.d(TAG, "${controller.packageName}: keys=[$details]")
    }

    private fun describe(controller: MediaController): String {
        val state = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_NONE, null -> "NONE"
            else -> controller.playbackState?.state.toString()
        }
        return "${controller.packageName}/${controller.tag}=$state"
    }

    private fun MediaMetadata.text(key: String): String? =
        getText(key)?.toString()?.takeIf { it.isNotBlank() }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
