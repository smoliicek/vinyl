package com.smoliicek.vinyl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Tonearm geometry in units of the disc radius, origin at the disc centre, y pointing down.
// The arm is drawn hanging straight down from its pivot; that is its rest position, beside the disc.

/** Pivot: top-right of the disc, in the gap before the text column. */
const val ARM_PIVOT_X = 1.16f
const val ARM_PIVOT_Y = -0.78f

/** Pivot to needle tip. */
const val ARM_LENGTH = 1.15f

/** Where the needle sits at 0% and 100% of a track. */
const val OUTER_GROOVE = 0.94f
const val INNER_GROOVE = 0.44f

/**
 * Clockwise rotation in degrees, from the rest position, that puts the needle at [radius] from the
 * disc centre. Of the two arm angles that reach that radius it takes the one nearer the rest position,
 * so the needle stays on the side of the disc facing the pivot.
 */
fun armAngleForRadius(radius: Float): Float {
    val pivotDistance = sqrt(ARM_PIVOT_X * ARM_PIVOT_X + ARM_PIVOT_Y * ARM_PIVOT_Y)
    // Law of cosines in the pivot / disc centre / needle triangle.
    val cosTheta = (pivotDistance * pivotDistance + ARM_LENGTH * ARM_LENGTH - radius * radius) /
        (2 * pivotDistance * ARM_LENGTH)
    val theta = acos(cosTheta.coerceIn(-1f, 1f))
    val towardCentre = atan2(-ARM_PIVOT_Y, -ARM_PIVOT_X)
    val down = (PI / 2).toFloat()
    val direction = listOf(towardCentre + theta, towardCentre - theta).minBy { abs(it - down) }
    return Math.toDegrees((direction - down).toDouble()).toFloat()
}

/** Arm rotation for a track [progress] in 0..1: outer groove at the start, inner groove at the end. */
fun armAngleForProgress(progress: Float): Float =
    armAngleForRadius(OUTER_GROOVE - (OUTER_GROOVE - INNER_GROOVE) * progress.coerceIn(0f, 1f))

/** Needle tip for a clockwise arm rotation of [degrees] from rest, as (x, y) in disc radii. */
fun needlePosition(degrees: Float): Pair<Float, Float> {
    val a = Math.toRadians(degrees.toDouble())
    // Rotating the rest direction (0, 1) clockwise on screen (y down) by a gives (-sin a, cos a).
    return Pair(
        ARM_PIVOT_X - ARM_LENGTH * sin(a).toFloat(),
        ARM_PIVOT_Y + ARM_LENGTH * cos(a).toFloat(),
    )
}
