package com.smoliicek.vinyl

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var repository: NowPlayingRepository
    private lateinit var setup: SystemSetup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = NowPlayingRepository(this)
        setup = SystemSetup(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showSettings) { showSettings = false }

                val permissionMissing by repository.permissionMissing.collectAsStateWithLifecycle()
                val nowPlaying by repository.nowPlaying.collectAsStateWithLifecycle()

                when {
                    showSettings -> SettingsScreen(
                        setup = setup,
                        onStartScreensaver = {
                            if (!setup.startScreensaverNow()) {
                                Toast.makeText(this, "Screensaver can't be started on this TV", Toast.LENGTH_LONG).show()
                                showSettings = false
                            }
                        },
                    )
                    else -> {
                        var buttonVisible by remember { mutableStateOf(true) }
                        var lastKeyPress by remember { mutableIntStateOf(0) }
                        var swallowKeyUp by remember { mutableStateOf(false) }
                        LaunchedEffect(lastKeyPress) {
                            buttonVisible = true
                            delay(BUTTON_HIDE_DELAY_MS)
                            buttonVisible = false
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .onPreviewKeyEvent { event ->
                                    when (event.type) {
                                        KeyEventType.KeyDown -> {
                                            // While hidden, a key press only brings the button back.
                                            swallowKeyUp = !buttonVisible
                                            lastKeyPress++
                                            swallowKeyUp
                                        }
                                        KeyEventType.KeyUp -> swallowKeyUp.also { swallowKeyUp = false }
                                        else -> false
                                    }
                                },
                        ) {
                            if (permissionMissing) PermissionScreen() else VinylScreen(nowPlaying)
                            SettingsButton(
                                visible = buttonVisible,
                                onClick = { showSettings = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(horizontal = 48.dp, vertical = 27.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        repository.start()
    }

    override fun onStop() {
        repository.stop()
        super.onStop()
    }
}

private const val BUTTON_HIDE_DELAY_MS = 5_000L

/**
 * Takes focus so a single OK press opens settings, stays faint, and fades out entirely
 * when no key has been pressed for a while.
 */
@Composable
private fun SettingsButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val alpha by animateFloatAsState(
        targetValue = when {
            !visible -> 0f
            focused -> 0.55f
            else -> 0.3f
        },
        animationSpec = tween(if (visible) 250 else 1200),
        label = "settingsButtonAlpha",
    )
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .alpha(alpha),
    ) {
        Text("Settings")
    }
}
