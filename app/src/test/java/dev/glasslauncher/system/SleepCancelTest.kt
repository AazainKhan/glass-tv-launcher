package dev.glasslauncher.system

import android.app.Application
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.dream.AerialActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** P83: the Sleep Timer's one-minute banner is cancelled by any interaction Glass sees, not only by the key filter. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SleepCancelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private fun arm(endIn: Long = 40_000L, warned: Boolean = true) {
        ctx.getSharedPreferences("sleepTimer", 0).edit()
            .putLong("endAt", System.currentTimeMillis() + endIn).putBoolean("warned", warned).putInt("choice", 15).apply()
    }

    @Test fun anInteractionAfterTheBannerCancels() {
        arm()
        assertTrue(SleepTimer.cancelOnInteraction(ctx))
        assertEquals(0L, SleepTimer.endAt(ctx))
        assertFalse("nothing left to cancel", SleepTimer.warning(ctx))
    }

    @Test fun beforeTheBannerAnInteractionDoesNotCancel() {
        arm(endIn = 600_000L, warned = false)
        assertFalse(SleepTimer.cancelOnInteraction(ctx))
        assertTrue("the timer still runs", SleepTimer.endAt(ctx) > System.currentTimeMillis())
    }

    @Test fun aKeyThatDismissesTheScreensaverCancelsIt() {
        arm()
        val activity = Robolectric.buildActivity(AerialActivity::class.java).create().get()
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("the timer is cancelled by the dismissing key", 0L, SleepTimer.endAt(ctx))
    }
}
