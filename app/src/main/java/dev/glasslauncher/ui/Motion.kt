package dev.glasslauncher.ui

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.exp

/**
 * Motion curves fitted to tvOS 27, measured frame by frame (tvos27-guidelines skill,
 * references/motion-spec.md). Fits, not Apple's source values: tune on the stick with scripts/clip.
 */
object Motion {
    /** Focus gained: fast, slightly springy arrival (~7% overshoot, peak ≈130 ms, settled ≈270 ms). */
    fun <T> focusIn() = spring<T>(dampingRatio = 0.65f, stiffness = 850f)

    /** Focus lost: soft, no bounce (~90% by ≈170 ms). */
    fun <T> focusOut() = spring<T>(dampingRatio = 1f, stiffness = 500f)

    /** Full speed on the first frame, then exponential deceleration (τ ≈ 160 ms over 700 ms). */
    val ExpOut = Easing { x -> ((1f - exp(-4.4f * x)) / (1f - exp(-4.4f))) }

    /** Vertical Home scroll: 90% by ≈350 ms, settled by ≈700 ms. */
    fun <T> scroll() = tween<T>(700, easing = ExpOut)

    /** Focus label: wait for the tile to get most of the way, then a quick fade; the old one fades out. */
    fun <T> labelIn() = tween<T>(70, delayMillis = 80)
    fun <T> labelOut() = tween<T>(130)

    /** Overlays that come from somewhere (Control Center, menus, folders): ~130 ms out-curve; exits mirror. */
    const val OVERLAY_MS = 130
    fun <T> overlay() = tween<T>(OVERLAY_MS, easing = FastOutSlowInEasing)

    /** List rows: the new selection is there within a frame; the old one fades over ~60 ms. */
    fun <T> selectIn() = tween<T>(33)
    fun <T> selectOut() = tween<T>(60)

    /** Settings page push: slide plus fade, decelerating, ~200 ms. */
    const val PAGE_MS = 200
}
