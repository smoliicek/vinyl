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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.animation.core.Animatable

private const val SECONDS_PER_TURN = 3.6f // half of 33⅓ RPM; the real speed read as too fast on screen
private const val DRIFT_DP = 10f
private val SAFE_HORIZONTAL = 64.dp // TV overscan-safe margins
private val SAFE_VERTICAL = 36.dp
private val TITLE_SIZES = (60 downTo 44 step 2).map { it.sp }

private val Ink = Color(0xFFF2EDE6)
private val InkDim = Color(0xFFF2EDE6).copy(alpha = 0.62f)
private val InkFaint = Color(0xFFF2EDE6).copy(alpha = 0.4f)
private val Floor = Color(0xFF070707)
private val NeutralTint = Color(0xFF2A2420)

@Composable
fun VinylScreen(nowPlaying: NowPlaying?, modifier: Modifier = Modifier) {
    val art = nowPlaying?.art
    val palette = rememberPalette(art)
    val tint = animateColorAsState(
        targetValue = palette?.let { it.dominantSwatch ?: it.vibrantSwatch }?.rgb?.let(::Color) ?: NeutralTint,
        animationSpec = tween(1500),
        label = "tint",
    )
    val accent by animateColorAsState(
        targetValue = palette?.let(::lightAccent) ?: Color.White.copy(alpha = 0.85f),
        animationSpec = tween(1500),
        label = "accent",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Layout is worked out here, not by the Row, so the backdrop and scrim can line up with the disc.
        val innerWidth = maxWidth - SAFE_HORIZONTAL * 2
        val disc = minOf((maxHeight - SAFE_VERTICAL * 2) * 0.82f, innerWidth * 0.48f)
        val gap = disc * 0.12f
        val infoWidth = minOf(innerWidth - disc - gap, 640.dp)
        val discCenterX = SAFE_HORIZONTAL + (innerWidth - (disc + gap + infoWidth)) / 2 + disc / 2

        Background(art = art, tint = tint, centerX = discCenterX, scrimStartX = discCenterX + disc / 2)
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
                        accent = accent,
                        modifier = Modifier.width(infoWidth),
                    )
                }
            }
        }
    }
}

/**
 * The art backdrop (centred under the disc, covering the screen) with the text scrim, baked once per
 * track into one opaque half-resolution image, so each frame is a single blit with no blending.
 * Without art it falls back to the tinted gradient plus scrim, drawn live.
 */
@Composable
private fun Background(art: Bitmap?, tint: State<Color>, centerX: Dp, scrimStartX: Dp) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cx = with(density) { centerX.toPx() }
        val scrimX = with(density) { scrimStartX.toPx() }
        val baked by produceState<ImageBitmap?>(null, art, width, height, cx, scrimX) {
            value = art?.let { withContext(Dispatchers.Default) { bakeBackground(it, width, height, cx, scrimX) } }
        }
        val fade = rememberImageFade(baked, durationMillis = 1500)
        Canvas(Modifier.fillMaxSize()) {
            val t = fade.fraction
            val current = fade.current
            val previous = fade.previous
            if (current == null || t < 1f) {
                drawRect(
                    Brush.linearGradient(
                        0f to lerp(tint.value, Floor, 0.55f),
                        0.65f to lerp(tint.value, Floor, 0.88f),
                        1f to Floor,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
                drawScrim(scrimX)
            }
            if (previous != null) drawFullScreen(previous, alpha = if (current != null) 1f else 1f - t)
            if (current != null) drawFullScreen(current, alpha = t)
        }
    }
}

private fun DrawScope.drawFullScreen(image: ImageBitmap, alpha: Float) {
    if (alpha <= 0f) return
    drawImage(
        image = image,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low, // bilinear; the content is all soft
    )
}

/** Fades from the previous image to [target] by drawing both with alpha, instead of using alpha layers. */
@Stable
private class ImageFade(initial: ImageBitmap?) {
    var previous by mutableStateOf<ImageBitmap?>(null)
    var current by mutableStateOf(initial)
    val progress = Animatable(1f)
    val fraction: Float get() = progress.value
}

@Composable
private fun rememberImageFade(target: ImageBitmap?, durationMillis: Int): ImageFade {
    val fade = remember { ImageFade(target) }
    LaunchedEffect(target) {
        if (target === fade.current) return@LaunchedEffect
        fade.previous = fade.current
        fade.current = target
        fade.progress.snapTo(0f)
        fade.progress.animateTo(1f, tween(durationMillis))
        fade.previous = null
    }
    return fade
}

@Composable
private fun TrackInfo(nowPlaying: NowPlaying?, accent: Color, modifier: Modifier = Modifier) {
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
        // Outside the crossfade: it snaps to the new track instead of fading.
        SongProgress(
            nowPlaying = nowPlaying,
            color = accent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
        )
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
    val dim = animateFloatAsState(
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

    val label = when {
        nowPlaying == null -> LabelContent.Empty
        nowPlaying.art != null -> LabelContent.Art(nowPlaying.art)
        else -> LabelContent.Text(nowPlaying.album ?: nowPlaying.title)
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val sizePx = constraints.maxWidth
        // The record is drawn once per track and size; frames only rotate the finished image.
        val discImage by produceState<ImageBitmap?>(null, sizePx, label) {
            value = withContext(Dispatchers.Default) { renderDisc(sizePx, label, textMeasurer, density) }
        }
        val sheen = remember(sizePx, density) { renderSheen(sizePx, density) }
        val fade = rememberImageFade(discImage, durationMillis = 900)

        // Only this layer's rotation changes per frame; angle is read inside graphicsLayer, not in composition.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle },
        ) {
            val t = fade.fraction
            fade.previous?.let { drawImage(it, alpha = dim.value * if (fade.current != null) 1f else 1f - t) }
            fade.current?.let { drawImage(it, alpha = dim.value * t) }
        }
        // Static sheen on top. Not rotating is what sells the spin.
        Canvas(Modifier.fillMaxSize()) {
            drawImage(sheen, alpha = dim.value)
        }
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
private fun rememberPalette(art: Bitmap?): Palette? {
    var palette by remember { mutableStateOf<Palette?>(null) }
    LaunchedEffect(art) {
        palette = art?.let {
            withContext(Dispatchers.Default) { Palette.from(it).maximumColorCount(16).generate() }
        }
    }
    return palette
}

/** A light colour from the art, for the progress bar: a light swatch if there is one, else a lightened one. */
private fun lightAccent(palette: Palette): Color? {
    palette.lightVibrantSwatch?.let { return Color(it.rgb) }
    palette.lightMutedSwatch?.let { return Color(it.rgb) }
    val base = palette.vibrantSwatch ?: palette.dominantSwatch ?: return null
    return lerp(Color(base.rgb), Color.White, 0.55f)
}
