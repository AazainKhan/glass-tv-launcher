package dev.glasslauncher.widgets

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.random.Random

/**
 * True once Home is idle AND its UI has finished fading out (P226): nothing on it is visible, so nothing on it may animate. Infinite
 * animations (a label's marquee) stop on this, so Home draws no frames. False again the moment any key arrives.
 */
val LocalHomeHidden = androidx.compose.runtime.compositionLocalOf { false }

/**
 * After a period without input (Settings: "Hide UI When Idle", 2 min by default) the apps, the clock, the status pill and the featured
 * row fade out, leaving the backdrop, and Home's own animation stops; the screen content drifts a few pixels each minute (burn-in
 * protection). Any key brings it back in 250 ms. The Aerials screensaver and the Top Shelf's autoplay pause run on the same input clock
 * ([millisSinceInput]) and are independent of this: autoplay holds after 60 s, this hides after the setting, the screensaver starts at its own.
 */
@Stable
class IdleState(val fadeMs: Long, private val clock: () -> Long = SystemClock::uptimeMillis) {
    var idle by mutableStateOf(false)
        private set
    var shift by mutableStateOf(Offset.Zero)
        private set
    private var lastInput = clock()
    internal var alpha: State<Float> = mutableStateOf(1f)
    internal var hiddenState: State<Boolean> = mutableStateOf(false)

    val chromeAlpha: Float get() = alpha.value

    /** Idle and faded all the way out (read it in composition: it changes once, not once per frame of the fade). */
    val hidden: Boolean get() = hiddenState.value

    fun millisSinceInput() = clock() - lastInput

    private var lastRepeat = Long.MIN_VALUE / 2

    /** A key event arrived: its repeat count says whether a key is being held (P158). */
    fun noteKey(repeatCount: Int) { if (repeatCount > 0) lastRepeat = clock() }

    /** Milliseconds since the last key repeat (huge when none). */
    fun millisSinceRepeat(): Long = clock() - lastRepeat

    fun touch() {
        lastInput = clock()
        if (idle) {
            idle = false
            shift = Offset.Zero
        }
    }

    internal suspend fun run() {
        if (fadeMs <= 0) return
        // Both loops test for cancellation: runCatching below swallows the CancellationException too, so a cancelled caller
        // (the LaunchedEffect leaving Home) would otherwise re-enter withTimeout on a dead job forever, spinning at full CPU.
        while (currentCoroutineContext().isActive) {
            val remaining = lastInput + fadeMs - clock()
            if (remaining > 0) {
                delay(remaining)
                continue
            }
            idle = true
            while (idle && currentCoroutineContext().isActive) {
                shift = Offset(Random.nextInt(-6, 7).toFloat(), Random.nextInt(-6, 7).toFloat())
                val woke = runCatching { kotlinx.coroutines.withTimeout(60_000) { snapshotFlow { idle }.first { !it } } }.isSuccess
                if (woke) break
            }
        }
    }
}

@Composable
fun rememberIdleState(minutes: Int): IdleState {
    val state = remember(minutes) { IdleState(minutes * 60_000L) }
    LaunchedEffect(state) { state.run() }
    state.alpha = animateFloatAsState(if (state.idle) 0f else 1f, tween(if (state.idle) 2500 else 250), label = "idle")
    state.hiddenState = remember(state) { androidx.compose.runtime.derivedStateOf { state.idle && state.alpha.value <= 0.001f } }
    return state
}
