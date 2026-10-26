package com.smoliicek.vinyl

import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Arm bitmap layout, in disc radii: the arm hangs straight down from the pivot.
private const val BITMAP_WIDTH = 0.56f
private const val PIVOT_IN_BITMAP_X = 0.24f
private const val PIVOT_IN_BITMAP_Y = 0.42f
private const val BITMAP_HEIGHT = PIVOT_IN_BITMAP_Y + ARM_LENGTH + 0.16f

private class ArmImage(val image: ImageBitmap, val pivotX: Int, val pivotY: Int)

/**
 * The tonearm, drawn once into a bitmap and only rotated around its pivot afterwards.
 * While playing, the needle follows track progress from the outer to the inner groove; when paused or
 * idle it swings to rest beside the disc, on the same 1s easing as the disc spinning down. A new track
 * lifts the arm, carries it back to the outer groove and sets it down.
 *
 * Must be placed in a box the size of the disc: it positions itself relative to the disc's top-left
 * corner and draws outside those bounds.
 */
@Composable
fun Tonearm(nowPlaying: NowPlaying?, discSizePx: Int) {
    val radius = discSizePx / 2f
    val arm by produceState<ArmImage?>(null, discSizePx) {
        value = withContext(Dispatchers.Default) { renderTonearm(radius) }
    }

    val playing = nowPlaying?.isPlaying == true
    val engaged by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "armEngaged",
    )

    var trackAngle by remember { mutableFloatStateOf(armAngleForProgress(0f)) }
    val lift = remember { Animatable(0f) }
    var returning by remember { mutableStateOf(false) }
    val track = nowPlaying?.let { listOf(it.title, it.artist, it.album, it.durationMs) }
    val lastTrack = remember { mutableStateOf(track) }
    LaunchedEffect(track) {
        val previous = lastTrack.value
        lastTrack.value = track
        if (track == null || previous == null || previous == track) return@LaunchedEffect
        // New track: lift, swing back to the outer groove, set down.
        returning = true
        lift.animateTo(1f, tween(180))
        animate(trackAngle, armAngleForProgress(0f), animationSpec = tween(450, easing = FastOutSlowInEasing)) { v, _ ->
            trackAngle = v
        }
        lift.animateTo(0f, tween(180))
        returning = false
    }
    // Follow the track position; per frame while playing (the disc is redrawing then anyway), once when paused.
    LaunchedEffect(nowPlaying) {
        val state = nowPlaying ?: return@LaunchedEffect
        val duration = state.durationMs ?: return@LaunchedEffect
        do {
            withFrameMillis {
                if (!returning) {
                    val position = estimatePosition(state, SystemClock.elapsedRealtime())
                    trackAngle = armAngleForProgress(position.toFloat() / duration)
                }
            }
        } while (state.isPlaying)
    }

    val image = arm ?: return
    val left = (radius * (1 + ARM_PIVOT_X)).roundToInt() - image.pivotX
    val top = (radius * (1 + ARM_PIVOT_Y)).roundToInt() - image.pivotY
    Canvas(
        Modifier
            // Report no size so the disc's box doesn't grow or recentre; just draw at the pivot.
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(image.image.width, image.image.height))
                layout(0, 0) { placeable.place(left, top) }
            }
            .graphicsLayer {
                rotationZ = trackAngle * engaged
                val scale = 1f + 0.035f * lift.value // lifting brings it closer to the viewer
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(
                    image.pivotX / image.image.width.toFloat(),
                    image.pivotY / image.image.height.toFloat(),
                )
            },
    ) {
        drawImage(image.image)
    }
}

/** Brushed silver, shaded across its width like a cylinder. */
private fun metal(left: Float, right: Float, dark: Boolean = false): Shader = LinearGradient(
    left, 0f, right, 0f,
    if (dark) {
        intArrayOf(0xFF2E3033.toInt(), 0xFF7D8186.toInt(), 0xFFC4C8CC.toInt(), 0xFF666A6E.toInt(), 0xFF26282A.toInt())
    } else {
        intArrayOf(0xFF55585C.toInt(), 0xFFD5D9DD.toInt(), 0xFFF5F7F9.toInt(), 0xFFA3A7AC.toInt(), 0xFF46494D.toInt())
    },
    floatArrayOf(0f, 0.3f, 0.45f, 0.75f, 1f),
    Shader.TileMode.CLAMP,
)

private fun renderTonearm(radius: Float): ArmImage {
    val u = radius
    val width = (BITMAP_WIDTH * u).roundToInt().coerceAtLeast(1)
    val height = (BITMAP_HEIGHT * u).roundToInt().coerceAtLeast(1)
    val px = PIVOT_IN_BITMAP_X * u
    val py = PIVOT_IN_BITMAP_Y * u
    val needleY = py + ARM_LENGTH * u
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    val counterweight = RectF(px - 0.065f * u, py - 0.36f * u, px + 0.065f * u, py - 0.17f * u)
    val stub = RectF(px - 0.018f * u, py - 0.17f * u, px + 0.018f * u, py)
    val tube = RectF(px - 0.014f * u, py, px + 0.014f * u, needleY - 0.13f * u)
    val headshell = RectF(px - 0.04f * u, needleY - 0.14f * u, px + 0.04f * u, needleY - 0.02f * u)
    val fingerLift = RectF(px + 0.03f * u, needleY - 0.125f * u, px + 0.11f * u, needleY - 0.111f * u)
    val cartridge = RectF(px - 0.026f * u, needleY - 0.065f * u, px + 0.026f * u, needleY - 0.006f * u)
    val corner = 0.014f * u

    fun silhouette(paint: Paint) {
        canvas.drawRoundRect(counterweight, corner, corner, paint)
        canvas.drawRect(stub, paint)
        canvas.drawRect(tube, paint)
        canvas.drawRoundRect(headshell, corner, corner, paint)
        canvas.drawRoundRect(fingerLift, corner, corner, paint)
        canvas.drawCircle(px, py, 0.11f * u, paint)
    }

    // Soft drop shadow, as if lit from the top-left.
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x73000000
        maskFilter = BlurMaskFilter(0.025f * u, BlurMaskFilter.Blur.NORMAL)
    }
    canvas.save()
    canvas.translate(0.03f * u, 0.045f * u)
    silhouette(shadow)
    canvas.restore()

    // Counterweight, with two machined grooves.
    fill.shader = metal(counterweight.left, counterweight.right)
    canvas.drawRoundRect(counterweight, corner, corner, fill)
    fill.shader = null
    fill.color = 0x55000000
    for (y in listOf(py - 0.30f * u, py - 0.23f * u)) {
        canvas.drawRect(counterweight.left, y, counterweight.right, y + 0.006f * u, fill)
    }

    fill.shader = metal(stub.left, stub.right)
    canvas.drawRect(stub, fill)
    fill.shader = metal(tube.left, tube.right)
    canvas.drawRect(tube, fill)

    // Pivot base: a domed housing with a cap.
    fill.shader = RadialGradient(
        px - 0.04f * u, py - 0.045f * u, 0.16f * u,
        intArrayOf(0xFFEDEFF2.toInt(), 0xFF9A9EA3.toInt(), 0xFF45484C.toInt()),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawCircle(px, py, 0.11f * u, fill)
    fill.shader = null
    fill.style = Paint.Style.STROKE
    fill.strokeWidth = 0.008f * u
    fill.color = 0x66000000
    canvas.drawCircle(px, py, 0.106f * u, fill)
    fill.style = Paint.Style.FILL
    fill.shader = metal(px - 0.055f * u, px + 0.055f * u)
    canvas.drawCircle(px, py, 0.055f * u, fill)
    fill.shader = null
    fill.color = 0xFF2A2C2E.toInt()
    canvas.drawCircle(px, py, 0.015f * u, fill)

    // Headshell, finger lift, cartridge and needle.
    fill.shader = metal(headshell.left, headshell.right, dark = true)
    canvas.drawRoundRect(headshell, corner, corner, fill)
    fill.shader = metal(fingerLift.left, fingerLift.right)
    canvas.drawRoundRect(fingerLift, corner, corner, fill)
    fill.shader = null
    fill.color = 0xFF151515.toInt()
    canvas.drawRoundRect(cartridge, corner / 2, corner / 2, fill)
    fill.color = 0xFF8E2A22.toInt()
    canvas.drawRect(cartridge.left, cartridge.top + 0.012f * u, cartridge.right, cartridge.top + 0.018f * u, fill)
    fill.color = 0xFFC9CDD1.toInt()
    canvas.drawRect(px - 0.003f * u, cartridge.bottom, px + 0.003f * u, needleY, fill)

    return ArmImage(bitmap.asImageBitmap(), px.roundToInt(), py.roundToInt())
}
