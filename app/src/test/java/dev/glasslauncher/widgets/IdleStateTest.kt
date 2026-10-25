package dev.glasslauncher.widgets

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P226: Home goes idle after its setting's minutes without input and any key wakes it; the clock is injected so the test needs no real wait. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleStateTest {
    private val twoMinutes = 2 * 60_000L

    @Test fun itGoesIdleAfterTheSettingAndNotBefore() = runTest {
        val state = IdleState(twoMinutes) { testScheduler.currentTime }
        val job = launch { state.run() }
        runCurrent()
        advanceTimeBy(twoMinutes - 1); runCurrent()
        assertFalse("not yet", state.idle)
        advanceTimeBy(2); runCurrent()
        assertTrue("two minutes without input", state.idle)
        job.cancel()
    }

    @Test fun anyInputBeforeTheLimitStartsTheCountAgain() = runTest {
        val state = IdleState(twoMinutes) { testScheduler.currentTime }
        val job = launch { state.run() }
        runCurrent()
        advanceTimeBy(twoMinutes - 10_000); runCurrent()
        state.touch()
        advanceTimeBy(twoMinutes - 10_000); runCurrent()
        assertFalse("the key moved the deadline", state.idle)
        assertEquals(twoMinutes - 10_000, state.millisSinceInput())
        job.cancel()
    }

    @Test fun aKeyWakesAnIdleHomeAndClearsTheDrift() = runTest {
        val state = IdleState(twoMinutes) { testScheduler.currentTime }
        val job = launch { state.run() }
        runCurrent()
        advanceTimeBy(twoMinutes + 1); runCurrent()
        assertTrue(state.idle)
        state.touch()
        Snapshot.sendApplyNotifications(); runCurrent()
        assertFalse("awake", state.idle)
        assertEquals("no drift left", androidx.compose.ui.geometry.Offset.Zero, state.shift)
        job.cancel()
    }

    @Test fun neverMeansNeverIdle() = runTest {
        val state = IdleState(0L) { testScheduler.currentTime }
        val job = launch { state.run() }
        runCurrent()
        advanceTimeBy(10 * 60 * 60_000L); runCurrent()
        assertFalse(state.idle)
        job.cancel()
    }
}
