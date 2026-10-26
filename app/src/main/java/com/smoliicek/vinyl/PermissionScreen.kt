package com.smoliicek.vinyl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PermissionScreen() {
    val ink = Color(0xFFF2EDE6)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0B0A))
            .padding(horizontal = 64.dp, vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 1100.dp)) {
            BasicText(
                "Vinyl can't see what's playing yet",
                style = TextStyle(color = ink, fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                "Android TV has no settings screen for notification access, so grant it over adb. " +
                    "This screen switches to the record as soon as access is granted.",
                style = TextStyle(color = ink.copy(alpha = 0.65f), fontSize = 20.sp, lineHeight = 28.sp),
            )
            Spacer(Modifier.height(28.dp))
            Command(GRANT_LISTENER_COMMAND)
            Spacer(Modifier.height(28.dp))
            BasicText(
                "To let Settings switch the system screensaver to Vinyl, also grant:",
                style = TextStyle(color = ink.copy(alpha = 0.65f), fontSize = 20.sp),
            )
            Spacer(Modifier.height(12.dp))
            Command(GRANT_SECURE_SETTINGS_COMMAND)
        }
    }
}

@Composable
private fun Command(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = Color(0xFFE8C9A0),
            fontSize = 17.sp,
            lineHeight = 26.sp,
            fontFamily = FontFamily.Monospace,
        ),
        modifier = Modifier
            .background(Color(0xFF1C1916), RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
