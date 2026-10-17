package dev.glasslauncher.system

import android.bluetooth.BluetoothManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * P169 #4: every 25 s rescan added another self-reposting battery check without removing the last, so the main
 * thread's queue grew without bound (about 2,600 checks after 18 h, an ANR and overnight reboots on the stick).
 * Scanning for hours must leave a constant number of pending tasks.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RemotePairerLeakTest {
    /** Messages waiting in the main looper's queue (the paused looper has no scheduler to ask). */
    private fun pending(): Int {
        val q = Looper.getMainLooper().queue
        val head = android.os.MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
        val next = android.os.Message::class.java.getDeclaredField("next").apply { isAccessible = true }
        var m = head.get(q) as android.os.Message?
        var n = 0
        while (m != null) { n++; m = next.get(m) as android.os.Message? }
        return n
    }

    @Test fun hoursOfRescansLeaveAConstantQueue() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(ctx.getSystemService(BluetoothManager::class.java).adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        shadowOf(ctx.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        val pairer = RemotePairer(ctx)
        pairer.start()
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMinutes(5))
        val early = pending()
        looper.idleFor(Duration.ofHours(2))
        val late = pending()
        pairer.stop()
        assertTrue("pending main-thread tasks grew from $early to $late over 2 h of rescans", late <= early + 1)
    }
}
