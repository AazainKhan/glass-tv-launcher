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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * Burn-in protection: after a period without input the clock, status bar and featured row fade
 * out and the screen content drifts a few pixels each minute. Wakes at most once a minute.
 */
@Stable
class IdleState(val fadeMs: Long) {
    var idle by mutableStateOf(false)
        private set
    var shift by mutableStateOf(Offset.Zero)
        private set
    private var lastInput = SystemClock.uptimeMillis()
    internal var alpha: State<Float> = mutableStateOf(1f)

    val chromeAlpha: Float get() = alpha.value

    fun touch() {
        lastInput = SystemClock.uptimeMillis()
        if (idle) {
            idle = false
            shift = Offset.Zero
        }
    }

    internal suspend fun run() {
        if (fadeMs <= 0) return
        while (true) {
            val remaining = lastInput + fadeMs - SystemClock.uptimeMillis()
            if (remaining > 0) {
                delay(remaining)
                continue
            }
            idle = true
            while (idle) {
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
    return state
}
