package com.smoliicek.vinyl

import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val IDLE_AFTER_PAUSE_MS = 45_000L

/** The last track shown, kept for the process so the screensaver can still show it after playback ends. */
object LastPlayed {
    @Volatile
    var track: Pair<String?, String?>? = null
}

/** True with no session, or once playback has been paused for 45s (counted from when it paused). */
@Composable
fun rememberIdle(nowPlaying: NowPlaying?): State<Boolean> =
    produceState(nowPlaying == null, nowPlaying == null, nowPlaying?.isPlaying, nowPlaying?.lastPositionUpdateTime) {
        val state = nowPlaying
        value = when {
            state == null -> true
            state.isPlaying -> false
            else -> {
                val pausedFor = if (state.lastPositionUpdateTime > 0) {
                    SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                } else {
                    0L
                }
                val remaining = IDLE_AFTER_PAUSE_MS - pausedFor
                if (remaining > 0) {
                    value = false
                    delay(remaining)
                }
                true
            }
        }
    }

/** The current time, updated only when the minute changes, and only while [active]. */
@Composable
fun rememberMinuteClock(active: Boolean): State<LocalDateTime> =
    produceState(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES), active) {
        if (!active) return@produceState
        while (true) {
            val now = LocalDateTime.now()
            value = now.truncatedTo(ChronoUnit.MINUTES)
            val nextMinute = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            delay(ChronoUnit.MILLIS.between(now, nextMinute) + 50)
        }
    }

private class ClockImages(val face: ImageBitmap, val hourHand: ImageBitmap, val minuteHand: ImageBitmap)

/**
 * A plain label with hour ticks and hour/minute hands on the spindle, laid over the disc's label.
 * It doesn't rotate with the disc (the disc is stopped in idle anyway), and it only redraws when the
 * minute changes or while fading in or out.
 */
@Composable
fun IdleClockOverlay(visible: Boolean, time: LocalDateTime, discSizePx: Int) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(800), label = "clock")
    val images = remember(discSizePx) { renderClock(discSizePx * LABEL_FRACTION / 2f) }
    Canvas(Modifier.fillMaxSize()) {
        val a = alpha
        if (a <= 0f) return@Canvas
        val topLeft = Offset(center.x - images.face.width / 2f, center.y - images.face.height / 2f)
        drawImage(images.face, topLeft, alpha = a)
        val minutes = time.minute
        val hours = time.hour % 12 + minutes / 60f
        rotate(hours * 30f, center) { drawImage(images.hourHand, topLeft, alpha = a) }
        rotate(minutes * 6f, center) { drawImage(images.minuteHand, topLeft, alpha = a) }
    }
}

/** Last played track, dimmed, then the time and date; just the time and date if nothing has played yet. */
@Composable
fun IdleInfo(lastTrack: Pair<String?, String?>?, time: LocalDateTime, modifier: Modifier = Modifier) {
    val ink = Color(0xFFF2EDE6)
    val date = remember(time.toLocalDate()) { time.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) }
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val clock = remember(time, use24Hour) { time.format(DateTimeFormatter.ofPattern(if (use24Hour) "H:mm" else "h:mm a")) }
    Column(modifier) {
        lastTrack?.first?.let { title ->
            BasicText(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = ink.copy(alpha = 0.45f), fontSize = 40.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        lastTrack?.second?.let { artist ->
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = ink.copy(alpha = 0.35f), fontSize = 26.sp),
            )
        }
        if (lastTrack != null) Spacer(Modifier.height(28.dp))
        BasicText(
            text = clock,
            style = TextStyle(
                color = ink.copy(alpha = 0.75f),
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                fontFeatureSettings = "tnum", // tabular figures, so the width doesn't jump each minute
            ),
        )
        BasicText(
            text = date,
            style = TextStyle(color = ink.copy(alpha = 0.5f), fontSize = 20.sp, letterSpacing = 0.5.sp),
        )
    }
}

/**
 * Face and hands at the label's size, all centred on the same point so they can be drawn at one offset
 * and rotated around the spindle. The face is cream: idle mode has a dark neutral background, and the
 * vinyl around the label is black, so a light label is what stands out.
 */
private fun renderClock(labelRadius: Float): ClockImages {
    val side = (labelRadius * 2).roundToInt().coerceAtLeast(2)
    val c = side / 2f
    val r = labelRadius - 1.5f // leave the disc's dark label ring visible
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val face = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(face).apply {
        paint.shader = RadialGradient(
            c - r * 0.25f, c - r * 0.3f, r * 1.3f,
            intArrayOf(0xFFF1EADF.toInt(), 0xFFE2D9CA.toInt(), 0xFFC9BFAF.toInt()),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        drawCircle(c, c, r, paint)
        paint.shader = null
        paint.color = 0xCC2B2420.toInt()
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        for (hour in 0 until 12) {
            val major = hour % 3 == 0
            paint.strokeWidth = r * if (major) 0.035f else 0.02f
            val angle = Math.toRadians(hour * 30.0)
            val inner = r * if (major) 0.76f else 0.81f
            val outer = r * 0.9f
            drawLine(
                c + inner * sin(angle).toFloat(), c - inner * cos(angle).toFloat(),
                c + outer * sin(angle).toFloat(), c - outer * cos(angle).toFloat(),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
    }

    fun hand(length: Float, baseWidth: Float, tipWidth: Float, withCap: Boolean): ImageBitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val tail = r * 0.14f
        val path = Path().apply {
            moveTo(c - baseWidth / 2, c + tail)
            lineTo(c - tipWidth / 2, c - length)
            lineTo(c + tipWidth / 2, c - length)
            lineTo(c + baseWidth / 2, c + tail)
            close()
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x59000000
            maskFilter = BlurMaskFilter(r * 0.025f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.translate(r * 0.02f, r * 0.03f)
        canvas.drawPath(path, shadow)
        canvas.restore()
        // Same brushed metal as the tonearm, a shade darker so it reads on the cream face.
        val metal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                c - baseWidth / 2, 0f, c + baseWidth / 2, 0f,
                intArrayOf(0xFF3A3D41.toInt(), 0xFF9DA1A6.toInt(), 0xFFD0D4D8.toInt(), 0xFF6E7277.toInt(), 0xFF303235.toInt()),
                floatArrayOf(0f, 0.3f, 0.45f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(path, metal)
        if (withCap) {
            // Spindle cap on top of both hands.
            metal.shader = RadialGradient(
                c - r * 0.02f, c - r * 0.025f, r * 0.08f,
                intArrayOf(0xFFF0F2F4.toInt(), 0xFF9A9EA3.toInt(), 0xFF4A4D51.toInt()),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(c, c, r * 0.06f, metal)
            metal.shader = null
            metal.color = 0xFF1E1F21.toInt()
            canvas.drawCircle(c, c, r * 0.018f, metal)
        }
        return bitmap.asImageBitmap()
    }

    return ClockImages(
        face = face.asImageBitmap(),
        hourHand = hand(length = r * 0.52f, baseWidth = r * 0.075f, tipWidth = r * 0.03f, withCap = false),
        minuteHand = hand(length = r * 0.8f, baseWidth = r * 0.055f, tipWidth = r * 0.02f, withCap = true),
    )
}
