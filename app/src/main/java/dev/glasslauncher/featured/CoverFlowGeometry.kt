package dev.glasslauncher.featured

import kotlin.math.abs
import kotlin.math.sign

/** Where one cover sits in the flow: all horizontal values as fractions of a cover's width. */
data class CoverPose(
    /** Horizontal offset of the cover's centre from the flow's centre, in cover widths. */
    val x: Float,
    /** Turn about the vertical axis, degrees: negative leans a left-hand cover's face to the right (towards the centre). */
    val rotationY: Float,
    val scale: Float,
    /** Stacking order: larger is nearer; the centre cover is on top. */
    val z: Float,
    val alpha: Float,
)

/**
 * The Cover Flow's geometry (tvOS/iTunes style): the centre cover flat and full size, up to [VISIBLE_SIDE]
 * neighbours a side turned [ANGLE] degrees, smaller, and stacked closely, each nearer the centre drawn over the
 * one outside it. Everything is a continuous function of [offset] (a cover's index minus the flow's position,
 * which is fractional while moving), so one animated position gives every cover's motion and a cover never jumps.
 */
object CoverFlowGeometry {
    const val ANGLE = 62f
    const val SIDE_SCALE = 0.8f
    /** Neighbours drawn each side of the centre; one more fades out. */
    const val VISIBLE_SIDE = 4
    /** The first neighbour's centre, in cover widths from the centre cover's. */
    const val FIRST_GAP = 0.74f
    /** Each further neighbour's step. */
    const val STEP = 0.2f

    fun pose(offset: Float): CoverPose {
        val d = abs(offset)
        val side = sign(offset)
        // 0 at the centre, 1 at the first neighbour and beyond: the turn and the shrink ease in over one step.
        val t = d.coerceAtMost(1f)
        val x = if (d <= 1f) d * FIRST_GAP else FIRST_GAP + (d - 1f) * STEP
        return CoverPose(
            x = side * x,
            rotationY = -side * ANGLE * smooth(t),
            scale = 1f - (1f - SIDE_SCALE) * smooth(t),
            z = -d,
            alpha = (VISIBLE_SIDE + 1f - d).coerceIn(0f, 1f),
        )
    }

    /** Whether a cover at [offset] is worth composing at all. */
    fun visible(offset: Float) = abs(offset) < VISIBLE_SIDE + 1f

    /** Smoothstep: no kink at the centre, so the cover leaves flat gently. */
    private fun smooth(t: Float) = t * t * (3f - 2f * t)
}
