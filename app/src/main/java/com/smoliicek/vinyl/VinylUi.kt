package com.smoliicek.vinyl

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private const val SECONDS_PER_TURN = 3.6f // half of 33⅓ RPM; the real speed read as too fast on screen
private const val LABEL_FRACTION = 0.4f
private const val DRIFT_DP = 10f

private val Ink = Color(0xFFF2EDE6)
private val InkDim = Color(0xFFF2EDE6).copy(alpha = 0.62f)
private val InkFaint = Color(0xFFF2EDE6).copy(alpha = 0.4f)
private val Floor = Color(0xFF070707)
private val NeutralTint = Color(0xFF2A2420)
private val LabelPaper = Color(0xFFE9E1D3)
private val LabelInk = Color(0xFF2B2420)

@Composable
fun VinylScreen(nowPlaying: NowPlaying?, modifier: Modifier = Modifier) {
    val art = nowPlaying?.art
    val tint by animateColorAsState(
        targetValue = rememberDominantColor(art) ?: NeutralTint,
        animationSpec = tween(1500),
        label = "tint",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    0f to lerp(tint, Floor, 0.55f),
                    0.65f to lerp(tint, Floor, 0.88f),
                    1f to Floor,
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            ),
    ) {
        Crossfade(targetState = art, animationSpec = tween(1500), label = "backdrop") { backdrop ->
            if (backdrop != null) ArtBackdrop(backdrop)
        }
        BurnInDrift(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    // TV overscan-safe margins
                    .padding(horizontal = 64.dp, vertical = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                val disc = minOf(maxHeight * 0.82f, maxWidth * 0.48f)
                val infoWidth = minOf(maxWidth - disc * 1.12f, 640.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VinylDisc(
                        nowPlaying = nowPlaying,
                        modifier = Modifier.size(disc),
                    )
                    Spacer(Modifier.width(disc * 0.12f))
                    TrackInfo(
                        nowPlaying = nowPlaying,
                        modifier = Modifier.width(infoWidth),
                    )
                }
            }
        }
    }
}

/** The album art filling the screen, heavily blurred and darkened to about 25% brightness. */
@Composable
private fun ArtBackdrop(art: Bitmap) {
    val image = remember(art) { art.asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(60.dp, BlurredEdgeTreatment.Rectangle),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
        )
    }
}

@Composable
private fun TrackInfo(nowPlaying: NowPlaying?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        if (nowPlaying == null) {
            BasicText(
                text = "Nothing playing",
                style = TextStyle(color = Ink.copy(alpha = 0.32f), fontSize = 26.sp, letterSpacing = 0.5.sp),
            )
            return@Column
        }
        Crossfade(
            targetState = Triple(nowPlaying.title, nowPlaying.artist, nowPlaying.album),
            animationSpec = tween(800),
            label = "info",
        ) { (title, artist, album) ->
            Column {
                BasicText(
                    text = title ?: "Unknown track",
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = Ink,
                        fontSize = 60.sp,
                        lineHeight = 66.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.6).sp,
                    ),
                )
                if (artist != null) {
                    Spacer(Modifier.height(16.dp))
                    BasicText(
                        text = artist,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = InkDim, fontSize = 32.sp, lineHeight = 38.sp),
                    )
                }
                if (album != null) {
                    Spacer(Modifier.height(10.dp))
                    BasicText(
                        text = album,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = InkFaint, fontSize = 24.sp, lineHeight = 30.sp),
                    )
                }
            }
        }
    }
}

@Composable
fun VinylDisc(nowPlaying: NowPlaying?, modifier: Modifier = Modifier) {
    val playing = nowPlaying?.isPlaying == true
    val speed = animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "speed",
    )
    val dim by animateFloatAsState(
        targetValue = if (nowPlaying == null) 0.4f else 1f,
        animationSpec = tween(1200),
        label = "dim",
    )

    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            if (speed.value == 0f) {
                // Sleep until something spins us up again instead of ticking frames at rest.
                snapshotFlow { speed.value }.first { it > 0f }
                last = 0L
            }
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    angle = (angle + speed.value * dt * 360f / SECONDS_PER_TURN) % 360f
                }
                last = now
            }
        }
    }

    Box(modifier = modifier.alpha(dim), contentAlignment = Alignment.Center) {
        // Rotating part: grooves + label.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle },
            contentAlignment = Alignment.Center,
        ) {
            Grooves(Modifier.fillMaxSize())
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize(LABEL_FRACTION)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val labelSize = maxWidth
                Crossfade(targetState = nowPlaying?.art, animationSpec = tween(900), label = "label") { art ->
                    Label(art = art, nowPlaying = nowPlaying, size = labelSize)
                }
            }
        }
        // Static part: label shading, sheen, rim and spindle. Not rotating is what sells the spin.
        Sheen(Modifier.fillMaxSize())
    }
}

@Composable
private fun Label(art: Bitmap?, nowPlaying: NowPlaying?, size: Dp) {
    if (art != null) {
        val image = remember(art) { art.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val text = nowPlaying?.album ?: nowPlaying?.title
    val fontSize = with(LocalDensity.current) { (size * 0.075f).toSp() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (nowPlaying == null) Color(0xFF3A3530) else LabelPaper),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            BasicText(
                text = text.uppercase(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = LabelInk,
                    fontSize = fontSize,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 1.5.sp,
                    textAlign = TextAlign.Center,
                ),
                // Keep text clear of the spindle hole.
                modifier = Modifier
                    .padding(horizontal = size * 0.14f)
                    .padding(bottom = size * 0.3f),
            )
        }
    }
}

@Composable
private fun Grooves(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.drawWithCache {
            val r = size.minDimension / 2f
            val rng = Random(33)
            val rings = buildList {
                var radius = r * (LABEL_FRACTION / 2f + 0.03f)
                while (radius < r * 0.965f) {
                    add(radius to (0.025f + rng.nextFloat() * 0.06f))
                    radius += r * (0.006f + rng.nextFloat() * 0.006f)
                }
            }
            // A couple of wider "track gap" rings, like a real LP.
            val gaps = listOf(0.52f, 0.68f, 0.83f).map { it * r }
            val body = Brush.radialGradient(
                0f to Color(0xFF1A1A1A),
                0.45f to Color(0xFF0E0E0E),
                1f to Color(0xFF050505),
                radius = r,
            )
            onDrawBehind {
                drawCircle(body, radius = r)
                rings.forEach { (radius, a) ->
                    drawCircle(Color.White.copy(alpha = a), radius = radius, style = Stroke(width = 1f))
                }
                gaps.forEach { radius ->
                    drawCircle(Color.Black, radius = radius, style = Stroke(width = r * 0.012f))
                }
            }
        },
    ) {}
}

@Composable
private fun Sheen(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val labelR = r * LABEL_FRACTION

        // Soft inner shadow on the label, darkening towards its edge.
        drawCircle(
            brush = Brush.radialGradient(
                0.70f to Color.Transparent,
                1.00f to Color.Black.copy(alpha = 0.38f),
                center = center,
                radius = labelR,
            ),
            radius = labelR,
        )
        // Thin dark ring where the label meets the vinyl.
        drawCircle(Color(0xFF080808), radius = labelR, style = Stroke(width = 2.dp.toPx()))

        // Bow-tie sheen: two faint highlights at 45° (top-right) and 225° (bottom-left), on the vinyl
        // only, since the label is matte. sweepGradient runs clockwise from 3 o'clock, so 45° is 0.875.
        val vinylWidth = r - labelR
        drawCircle(
            brush = Brush.sweepGradient(
                0.000f to Color.Transparent,
                0.305f to Color.Transparent,
                0.375f to Color.White.copy(alpha = 0.09f),
                0.445f to Color.Transparent,
                0.805f to Color.Transparent,
                0.875f to Color.White.copy(alpha = 0.11f),
                0.945f to Color.Transparent,
                1.000f to Color.Transparent,
                center = center,
            ),
            radius = labelR + vinylWidth / 2f,
            style = Stroke(width = vinylWidth),
        )
        // Rim highlight on the outer edge.
        val rim = 1.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.16f), radius = r - rim / 2f, style = Stroke(width = rim))

        // Spindle hole.
        drawCircle(Color(0xFF0B0B0B), radius = r * 0.022f)
        drawCircle(Color.White.copy(alpha = 0.18f), radius = r * 0.022f, style = Stroke(width = 1.5f))
    }
}

/** Slow Lissajous drift of a few dp over several minutes, to avoid burn-in. */
@Composable
private fun BurnInDrift(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "drift")
    val phaseX by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7 * 60_000, easing = LinearEasing), RepeatMode.Restart),
        label = "driftX",
    )
    val phaseY by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(11 * 60_000, easing = LinearEasing), RepeatMode.Restart),
        label = "driftY",
    )
    Box(
        modifier = modifier.graphicsLayer {
            translationX = sin(phaseX) * DRIFT_DP.dp.toPx()
            translationY = sin(phaseY) * DRIFT_DP.dp.toPx()
        },
    ) {
        content()
    }
}

@Composable
private fun rememberDominantColor(art: Bitmap?): Color? {
    var color by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(art) {
        color = art?.let {
            withContext(Dispatchers.Default) {
                val palette = Palette.from(it).maximumColorCount(16).generate()
                val swatch = palette.dominantSwatch ?: palette.vibrantSwatch
                swatch?.rgb?.let(::Color)
            }
        }
    }
    return color
}
