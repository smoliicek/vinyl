package com.smoliicek.vinyl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text

private val Ok = Color(0xFF8FD19E)
private val Missing = Color(0xFFE8A06A)

@Composable
fun SettingsScreen(setup: SystemSetup, onStartScreensaver: () -> Unit) {
    var isOurs by remember { mutableStateOf(false) }
    var listenerGranted by remember { mutableStateOf(false) }
    var canWrite by remember { mutableStateOf(false) }
    var writeRefused by remember { mutableStateOf(false) }

    fun refresh() {
        isOurs = setup.isOurScreensaver()
        listenerGranted = setup.isListenerGranted()
        canWrite = setup.canWriteSecureSettings()
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstItem.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0B0A))
            .padding(horizontal = 64.dp, vertical = 36.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFF2EDE6)) {
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.width(420.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))
                ListItem(
                    selected = false,
                    onClick = {
                        writeRefused = !setup.setOurScreensaver(!isOurs)
                        refresh()
                    },
                    headlineContent = { Text("Use as screensaver") },
                    supportingContent = { Text(if (isOurs) "Vinyl is the screensaver" else "Another screensaver is set") },
                    trailingContent = { Switch(checked = isOurs, onCheckedChange = null) },
                    modifier = Modifier.focusRequester(firstItem),
                )
                Spacer(Modifier.height(8.dp))
                ListItem(
                    selected = false,
                    onClick = onStartScreensaver,
                    headlineContent = { Text("Start screensaver now") },
                    supportingContent = { Text("Starts whichever screensaver is set") },
                )
                if (writeRefused) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Couldn't change the screensaver: secure settings access is missing.",
                        color = Missing,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Spacer(Modifier.height(48.dp))
                StatusRow("Notification access", listenerGranted, GRANT_LISTENER_COMMAND)
                StatusRow("Secure settings access", canWrite, GRANT_SECURE_SETTINGS_COMMAND)
            }
        }
        }
    }
}

@Composable
private fun StatusRow(label: String, granted: Boolean, command: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (granted) Ok else Missing, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "$label: ${if (granted) "granted" else "missing"}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (!granted) {
            Spacer(Modifier.height(10.dp))
            Text(
                command,
                color = Color(0xFFE8C9A0),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier
                    .background(Color(0xFF1C1916), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}
