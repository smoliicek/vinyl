package com.smoliicek.vinyl

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// Everything here is drawn once (per track or size) into a bitmap, off the main thread where possible,
// so each frame only blits or rotates finished images instead of redrawing gradients and strokes.

const val LABEL_FRACTION = 0.4f
private const val BACKDROP_SIZE = 128
private const val BACKDROP_BLUR_RADIUS = 8

private val LabelPaper = Color(0xFFE9E1D3)
private val LabelInk = Color(0xFF2B2420)
private val EmptyLabel = Color(0xFF3A3530)

/**
 * A small, blurred and darkened (to ~25%) square crop of the art. Drawn stretched over the screen with
 * bilinear filtering it reads as a heavy blur, without a per-frame blur pass. It has to be blurred here
 * first: a raw tiny bitmap stretched that far shows its pixels as blotches.
 */
fun makeBackdrop(art: Bitmap): ImageBitmap {
    var small = centerSquare(art)
    // Halve step by step so every source pixel contributes; one big jump would just sample a few.
    while (small.width > BACKDROP_SIZE * 2) {
        small = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
    }
    small = Bitmap.createScaledBitmap(small, BACKDROP_SIZE, BACKDROP_SIZE, true)

    val pixels = IntArray(BACKDROP_SIZE * BACKDROP_SIZE)
    small.getPixels(pixels, 0, BACKDROP_SIZE, 0, 0, BACKDROP_SIZE, BACKDROP_SIZE)
    // Three box passes approximate a Gaussian. A radius of 8 at 128px is roughly the old 60dp blur.
    repeat(3) { boxBlur(pixels, BACKDROP_SIZE, BACKDROP_BLUR_RADIUS) }
    for (i in pixels.indices) pixels[i] = darken(pixels[i], 0.25f)

    return Bitmap.createBitmap(pixels, BACKDROP_SIZE, BACKDROP_SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Separable box blur of opaque ARGB pixels in place, clamping at the edges. */
private fun boxBlur(pixels: IntArray, side: Int, radius: Int) {
    val tmp = IntArray(pixels.size)
    blurPass(pixels, tmp, side, radius, horizontal = true)
    blurPass(tmp, pixels, side, radius, horizontal = false)
}

private fun blurPass(src: IntArray, dst: IntArray, side: Int, radius: Int, horizontal: Boolean) {
    val window = radius * 2 + 1
    for (line in 0 until side) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, side - 1)
            return if (horizontal) src[line * side + c] else src[c * side + line]
        }
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = at(i)
            r += p shr 16 and 0xFF
            g += p shr 8 and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until side) {
            val out = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            if (horizontal) dst[line * side + i] = out else dst[i * side + line] = out
            val add = at(i + radius + 1)
            val drop = at(i - radius)
            r += (add shr 16 and 0xFF) - (drop shr 16 and 0xFF)
            g += (add shr 8 and 0xFF) - (drop shr 8 and 0xFF)
            b += (add and 0xFF) - (drop and 0xFF)
        }
    }
}

private fun darken(pixel: Int, factor: Float): Int {
    val r = ((pixel shr 16 and 0xFF) * factor).toInt()
    val g = ((pixel shr 8 and 0xFF) * factor).toInt()
    val b = ((pixel and 0xFF) * factor).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * The blurred backdrop, centred on [centerX] and covering the screen, with the text scrim on top,
 * at half the screen resolution. Opaque, so drawing it needs no blending.
 */
fun bakeBackground(art: Bitmap, width: Int, height: Int, centerX: Float, scrimStartX: Float): ImageBitmap {
    val backdrop = makeBackdrop(art)
    val w = (width / 2).coerceAtLeast(1)
    val h = (height / 2).coerceAtLeast(1)
    val image = ImageBitmap(w, h, hasAlpha = false)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
        val cx = centerX / 2
        // Square, big enough to reach the farther screen edge from cx and the full height.
        val side = maxOf(2 * maxOf(cx, size.width - cx), size.height)
        drawImage(
            image = backdrop,
            dstOffset = IntOffset((cx - side / 2).roundToInt(), ((size.height - side) / 2).roundToInt()),
            dstSize = IntSize(side.roundToInt(), side.roundToInt()),
            filterQuality = FilterQuality.High,
        )
        drawScrim(scrimStartX / 2)
    }
    return image
}

/** Darkens towards the right so the text reads the same over any backdrop. */
fun DrawScope.drawScrim(startX: Float) {
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.4f),
            startX = startX,
            endX = size.width,
        ),
    )
}

/** What goes on the label: the art, or (without art) a paper label with text, or the empty dark label. */
sealed interface LabelContent {
    data class Art(val bitmap: Bitmap) : LabelContent
    data class Text(val text: String?) : LabelContent
    data object Empty : LabelContent
}

/**
 * The whole record as one image: body, grooves, label (art or text), label shading and ring, rim
 * highlight and spindle hole. All of it is rotationally symmetric apart from the label, so rotating the
 * image looks the same as rotating the label alone.
 */
fun renderDisc(sizePx: Int, label: LabelContent, textMeasurer: TextMeasurer, density: Density): ImageBitmap =
    renderSquare(sizePx, density) {
        val r = size.minDimension / 2f
        val labelR = r * LABEL_FRACTION // label diameter is LABEL_FRACTION of the disc diameter
        drawGrooves(r)
        drawLabel(label, labelR, textMeasurer)

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
        // Rim highlight on the outer edge.
        val rim = 1.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.16f), radius = r - rim / 2f, style = Stroke(width = rim))
        // Spindle hole.
        drawCircle(Color(0xFF0B0B0B), radius = r * 0.022f)
        drawCircle(Color.White.copy(alpha = 0.18f), radius = r * 0.022f, style = Stroke(width = 1.5f))
    }

/**
 * Bow-tie sheen: two faint highlights at 45° (top-right) and 225° (bottom-left), on the vinyl only,
 * since the label is matte. sweepGradient runs clockwise from 3 o'clock, so 45° is 0.875.
 * Drawn on top without rotating, which is what sells the spin.
 */
fun renderSheen(sizePx: Int, density: Density): ImageBitmap = renderSquare(sizePx, density) {
    val r = size.minDimension / 2f
    val labelR = r * LABEL_FRACTION
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
}

private fun DrawScope.drawGrooves(r: Float) {
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFF1A1A1A),
            0.45f to Color(0xFF0E0E0E),
            1f to Color(0xFF050505),
            center = center,
            radius = r,
        ),
        radius = r,
    )
    val rng = Random(33)
    var radius = r * (LABEL_FRACTION / 2f + 0.03f)
    while (radius < r * 0.965f) {
        drawCircle(Color.White.copy(alpha = 0.025f + rng.nextFloat() * 0.06f), radius = radius, style = Stroke(width = 1f))
        radius += r * (0.006f + rng.nextFloat() * 0.006f)
    }
    // A couple of wider "track gap" rings, like a real LP.
    listOf(0.52f, 0.68f, 0.83f).forEach {
        drawCircle(Color.Black, radius = it * r, style = Stroke(width = r * 0.012f))
    }
}

private fun DrawScope.drawLabel(label: LabelContent, labelR: Float, textMeasurer: TextMeasurer) {
    when (label) {
        is LabelContent.Art -> {
            // A shader-filled circle gives an antialiased edge; a clipPath on a software canvas wouldn't.
            val square = centerSquare(label.bitmap)
            val shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(
                    android.graphics.Matrix().apply {
                        setScale(labelR * 2 / square.width, labelR * 2 / square.height)
                        postTranslate(center.x - labelR, center.y - labelR)
                    },
                )
            }
            drawCircle(ShaderBrush(shader), radius = labelR)
        }
        is LabelContent.Text -> {
            drawCircle(LabelPaper, radius = labelR)
            val text = label.text ?: return
            val diameter = labelR * 2
            val layout = textMeasurer.measure(
                text = text.uppercase(),
                style = TextStyle(
                    color = LabelInk,
                    fontSize = (diameter * 0.075f).toSp(),
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 1.5.sp,
                    textAlign = TextAlign.Center,
                ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                constraints = Constraints.fixedWidth((diameter * (1 - 0.28f)).toInt()),
            )
            // Centred in the label's upper part, clear of the spindle hole.
            val top = center.y - labelR + (diameter * 0.7f - layout.size.height) / 2f
            drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, top))
        }
        LabelContent.Empty -> drawCircle(EmptyLabel, radius = labelR)
    }
}

private fun renderSquare(sizePx: Int, density: Density, block: DrawScope.() -> Unit): ImageBitmap {
    val image = ImageBitmap(sizePx, sizePx)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(sizePx.toFloat(), sizePx.toFloat()), block)
    return image
}

private fun centerSquare(bitmap: Bitmap): Bitmap {
    val side = min(bitmap.width, bitmap.height)
    if (bitmap.width == side && bitmap.height == side) return bitmap
    return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
}
