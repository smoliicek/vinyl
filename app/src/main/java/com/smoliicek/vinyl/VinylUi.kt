package com.smoliicek.vinyl

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val SECONDS_PER_TURN = 3.6f // half of 33⅓ RPM; the real speed read as too fast on screen
private const val LABEL_FRACTION = 0.4f
private const val DRIFT_DP = 10f
private val SAFE_HORIZONTAL = 64.dp // TV overscan-safe margins
private val SAFE_VERTICAL = 36.dp
private val TITLE_SIZES = (60 downTo 44 step 2).map { it.sp }

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

    BoxWithConstraints(
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
        // Layout is worked out here, not by the Row, so the backdrop and scrim can line up with the disc.
        val innerWidth = maxWidth - SAFE_HORIZONTAL * 2
        val disc = minOf((maxHeight - SAFE_VERTICAL * 2) * 0.82f, innerWidth * 0.48f)
        val gap = disc * 0.12f
        val infoWidth = minOf(innerWidth - disc - gap, 640.dp)
        val discCenterX = SAFE_HORIZONTAL + (innerWidth - (disc + gap + infoWidth)) / 2 + disc / 2

        Crossfade(targetState = art, animationSpec = tween(1500), label = "backdrop") { backdrop ->
            if (backdrop != null) ArtBackdrop(backdrop, centerX = discCenterX)
        }
        TextScrim(startX = discCenterX + disc / 2)
        BurnInDrift(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = SAFE_HORIZONTAL, vertical = SAFE_VERTICAL),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VinylDisc(
                        nowPlaying = nowPlaying,
                        modifier = Modifier.size(disc),
                    )
                    Spacer(Modifier.width(gap))
                    TrackInfo(
                        nowPlaying = nowPlaying,
                        modifier = Modifier.width(infoWidth),
                    )
                }
            }
        }
    }
}

/**
 * The album art, centred horizontally under the disc and scaled to cover the whole screen,
 * heavily blurred and darkened to about 25% brightness.
 */
@Composable
private fun ArtBackdrop(art: Bitmap, centerX: Dp) {
    val image = remember(art) { art.asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .blur(60.dp, BlurredEdgeTreatment.Rectangle),
        ) {
            val cx = centerX.toPx()
            // Square, big enough to reach the farther screen edge from cx and the full height.
            val side = maxOf(2 * maxOf(cx, size.width - cx), size.height)
            val srcSide = minOf(image.width, image.height)
            drawImage(
                image = image,
                srcOffset = IntOffset((image.width - srcSide) / 2, (image.height - srcSide) / 2),
                srcSize = IntSize(srcSide, srcSide),
                dstOffset = IntOffset((cx - side / 2).roundToInt(), ((size.height - side) / 2).roundToInt()),
                dstSize = IntSize(side.roundToInt(), side.roundToInt()),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
        )
    }
}

/** Darkens towards the right so the text reads the same over any backdrop. */
@Composable
private fun TextScrim(startX: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.4f),
                startX = startX.toPx(),
                endX = size.width,
            ),
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
                Title(title ?: "Unknown track")
                if (artist != null) {
                    Spacer(Modifier.height(16.dp))
                    MarqueeLine(AnnotatedString(artist), TextStyle(color = InkDim, fontSize = 32.sp))
                }
                if (album != null) {
                    Spacer(Modifier.height(10.dp))
                    val albumStyle = TextStyle(color = InkFaint, fontSize = 24.sp)
                    MarqueeLine(withDimSuffix(splitTitle(album), albumStyle.color), albumStyle)
                }
            }
        }
    }
}

/**
 * Picks the largest size from 60 down to 44sp at which the main title (without its suffix) fits on one line.
 * Below that it stays at 44sp and wraps to two lines. The suffix follows on the same line and is what gets
 * cut short: we keep as much of it as fits with an ellipsis, so the main title is only ever ellipsized
 * when it doesn't fit in two lines by itself.
 */
@Composable
private fun Title(text: String) {
    val parts = remember(text) { splitTitle(text) }
    val baseStyle = TextStyle(color = Ink, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, lineHeight = 1.12.em)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val widthOnly = Constraints(maxWidth = constraints.maxWidth)
        val (style, shown) = remember(parts, widthOnly) {
            val fontSize = TITLE_SIZES.firstOrNull { size ->
                measurer.measure(parts.main, baseStyle.copy(fontSize = size), maxLines = 1, softWrap = false)
                    .size.width <= widthOnly.maxWidth
            } ?: TITLE_SIZES.last()
            val style = baseStyle.copy(fontSize = fontSize)
            fun fits(candidate: SplitTitle) =
                !measurer.measure(withDimSuffix(candidate, style.color), style, maxLines = 2, constraints = widthOnly)
                    .hasVisualOverflow
            style to fitSuffix(parts, ::fits)
        }
        BasicText(
            text = withDimSuffix(shown, style.color),
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The longest prefix of the suffix (ellipsized) that still [fits]; no suffix at all if none does. */
private fun fitSuffix(parts: SplitTitle, fits: (SplitTitle) -> Boolean): SplitTitle {
    val suffix = parts.suffix ?: return parts
    if (fits(parts)) return parts
    var low = 0
    var high = suffix.length - 1
    var best: SplitTitle? = null
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = parts.copy(suffix = suffix.take(mid).trimEnd() + "…")
        if (fits(candidate)) {
            best = candidate
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best?.takeIf { it.suffix != "…" } ?: parts.copy(suffix = null)
}

/** One fixed-size line that scrolls slowly, with a pause before each pass, only when it doesn't fit. */
@Composable
private fun MarqueeLine(text: AnnotatedString, style: TextStyle) {
    BasicText(
        text = text,
        style = style,
        maxLines = 1,
        modifier = Modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 2_000,
            repeatDelayMillis = 2_000,
            velocity = 24.dp,
        ),
    )
}

private fun withDimSuffix(parts: SplitTitle, color: Color): AnnotatedString = buildAnnotatedString {
    append(parts.main)
    parts.suffix?.let { suffix ->
        withStyle(SpanStyle(color = color.copy(alpha = color.alpha * 0.5f), fontSize = 0.8.em, fontWeight = FontWeight.Normal)) {
            append(" $suffix")
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

/**
 * Slow Lissajous drift of a few dp over several minutes, to avoid burn-in.
 * It moves in whole-pixel steps checked once a second: a per-frame animation here would redraw the
 * whole screen at 60 fps even when nothing else on it changes.
 */
@Composable
private fun BurnInDrift(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val amplitude = with(LocalDensity.current) { DRIFT_DP.dp.toPx() }
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(amplitude) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            val minutes = (SystemClock.uptimeMillis() - start) / 60_000.0
            // Only changes (and so only redraws) when it lands on a different pixel.
            offset = IntOffset(
                (sin(2 * PI * minutes / 7) * amplitude).roundToInt(),
                (sin(2 * PI * minutes / 11) * amplitude).roundToInt(),
            )
            delay(1_000)
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            translationX = offset.x.toFloat()
            translationY = offset.y.toFloat()
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
