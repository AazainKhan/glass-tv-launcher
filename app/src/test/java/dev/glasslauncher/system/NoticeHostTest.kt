package dev.glasslauncher.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** P229/P234: notices show one at a time on the host's surface, and a later one waits for the earlier to leave. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NoticeHostTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun noticesShowOneAtATimeInOrder() {
        val probe = NoticeProbe(context)
        probe.host.show(Notice("First", 3_000))
        probe.host.show(Notice("Second", 3_000))
        ShadowLooper.idleMainLooper()
        assertEquals("First", probe.latest())
        assertEquals(1, probe.count)
        ShadowLooper.idleMainLooper(3_600, TimeUnit.MILLISECONDS) // first up 3 s, slide out, then the second
        assertEquals("Second", probe.latest())
    }

    @Test fun clearTakesTheCardOffAtOnce() {
        val probe = NoticeProbe(context)
        probe.host.show(Notice("Up", 3_000))
        ShadowLooper.idleMainLooper()
        probe.host.clear()
        assertEquals(0, probe.count)
        assertNull(probe.latestOrNull())
    }
}
