package com.smoliicek.vinyl

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

private const val TAG = "VinylDebug"

// Raw Settings.Secure keys; the constants for these are hidden API.
private const val KEY_COMPONENTS = "screensaver_components"
private const val KEY_ENABLED = "screensaver_enabled"
private const val KEY_LISTENERS = "enabled_notification_listeners"

private const val PREFS = "screensaver"
private const val PREF_PREVIOUS = "previous_components"
private const val PREF_HAS_PREVIOUS = "has_previous_components"

const val DREAM_COMPONENT = "com.smoliicek.vinyl/com.smoliicek.vinyl.VinylDream"
const val LISTENER_COMPONENT = "com.smoliicek.vinyl/com.smoliicek.vinyl.NowPlayingListener"

const val GRANT_LISTENER_COMMAND = "adb shell cmd notification allow_listener $LISTENER_COMPONENT"
const val GRANT_SECURE_SETTINGS_COMMAND =
    "adb shell pm grant com.smoliicek.vinyl android.permission.WRITE_SECURE_SETTINGS"

/** Reads and writes the system screensaver selection. Every write is guarded; nothing here throws. */
class SystemSetup(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dream = ComponentName.unflattenFromString(DREAM_COMPONENT)
    private val listener = ComponentName.unflattenFromString(LISTENER_COMPONENT)

    fun isListenerGranted(): Boolean = containsComponent(Settings.Secure.getString(resolver, KEY_LISTENERS), listener)

    fun canWriteSecureSettings(): Boolean =
        app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isOurScreensaver(): Boolean = containsComponent(Settings.Secure.getString(resolver, KEY_COMPONENTS), dream)

    /** Returns false if the write was refused (permission missing). */
    fun setOurScreensaver(enabled: Boolean): Boolean = try {
        if (enabled) enable() else disable()
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Writing secure settings refused", e)
        false
    }

    private fun enable() {
        val current = Settings.Secure.getString(resolver, KEY_COMPONENTS)
        // Don't overwrite the saved value with ourselves if we're already set.
        if (!containsComponent(current, dream)) {
            prefs.edit()
                .putString(PREF_PREVIOUS, current)
                .putBoolean(PREF_HAS_PREVIOUS, true)
                .apply()
        }
        Settings.Secure.putString(resolver, KEY_COMPONENTS, DREAM_COMPONENT)
        Settings.Secure.putInt(resolver, KEY_ENABLED, 1)
    }

    private fun disable() {
        if (!prefs.getBoolean(PREF_HAS_PREVIOUS, false)) {
            Log.w(TAG, "No saved screensaver to restore; leaving $KEY_COMPONENTS as is")
            return
        }
        Settings.Secure.putString(resolver, KEY_COMPONENTS, prefs.getString(PREF_PREVIOUS, null))
        prefs.edit().remove(PREF_PREVIOUS).remove(PREF_HAS_PREVIOUS).apply()
    }

    /** Asks SystemUI to start the configured dream right now. Returns false if this firmware won't. */
    fun startScreensaverNow(): Boolean = try {
        app.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Somnambulator not found", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Somnambulator not launchable", e)
        false
    }

    private fun containsComponent(value: String?, component: ComponentName?): Boolean =
        component != null && value.orEmpty().split(':', ',')
            .any { ComponentName.unflattenFromString(it.trim()) == component }
}
