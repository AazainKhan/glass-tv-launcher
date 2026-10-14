package dev.glasslauncher.system

import android.app.NotificationManager
import dev.glasslauncher.system.BannerQueue.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerQueueTest {
    private fun plain(t: String, ms: Long = 4_000) = Notice(t, ms)
    private fun act(t: String, ms: Long = 15_000) = Notice(t, ms, actionable = true)

    @Test fun oneAtATimeAndTheRestQueueInOrder() {
        val q = BannerQueue()
        assertEquals(Step.Show(plain("a")), q.offer(plain("a")))
        assertEquals(Step.Wait, q.offer(plain("b")))
        assertEquals(Step.Wait, q.offer(plain("c")))
        assertEquals(listOf("b", "c"), q.waiting.map { it.text })
        assertEquals(Step.Show(plain("b")), q.finished())
        assertEquals(Step.Show(plain("c")), q.finished())
        assertEquals(Step.Wait, q.finished())
        assertEquals(null, q.showing)
    }

    @Test fun theSameTextIsTheSameBannerNotAStack() {
        val q = BannerQueue()
        q.offer(plain("Remote connected"))
        assertEquals(Step.Extend(2_000), q.offer(plain("Remote connected", 2_000)))
        q.offer(plain("x"))
        q.offer(plain("x")); q.offer(plain("x"))
        assertEquals("a repeat waiting is not queued twice", 1, q.waiting.size)
    }

    @Test fun anActionableNoticeTakesTheCapsuleFromAPlainOneAndWaitsForAnActionableOne() {
        val q = BannerQueue()
        q.offer(plain("info"))
        assertEquals(Step.Show(act("pair")), q.offer(act("pair")))
        assertTrue("the plain one is dropped, not queued", q.waiting.isEmpty())
        assertEquals("a second actionable waits behind the first", Step.Wait, q.offer(act("code")))
        assertEquals(Step.Show(act("code")), q.finished())
    }

    @Test fun anActionableOneWaitingGoesBeforePlainOnes() {
        val q = BannerQueue()
        q.offer(act("first"))
        q.offer(plain("p1")); q.offer(act("a2")); q.offer(plain("p2"))
        assertEquals(Step.Show(act("a2")), q.finished())
    }

    @Test fun theQueueIsShortAndDropsTheOldestPlainOneFirst() {
        val q = BannerQueue()
        q.offer(plain("now"))
        listOf("p1", "p2", "p3", "p4").forEach { q.offer(plain(it)) }
        assertEquals(listOf("p2", "p3", "p4"), q.waiting.map { it.text })
        q.offer(act("a")); // takes the capsule; the plain waiting ones stay
        val q2 = BannerQueue(); q2.offer(act("cur"))
        q2.offer(act("a1")); q2.offer(plain("p1")); q2.offer(plain("p2")); q2.offer(plain("p3"))
        assertEquals("plain ones go before an actionable one", listOf("a1", "p2", "p3"), q2.waiting.map { it.text })
    }

    @Test fun aPlainNoticeIsAFewSecondsAnActionableOneKeepsItsTime() {
        assertEquals(Notice.MAX_PLAIN_MS, plain("long", 20_000).shownMs)
        assertEquals(2_000L, plain("short", 2_000).shownMs)
        assertEquals(20_000L, act("pair", 20_000).shownMs)
    }

    @Test fun doNotDisturbHoldsPlainNoticesButAnUnreadableFilterDoesNot() {
        assertFalse(DoNotDisturb.suppresses(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse("unknown: show", DoNotDisturb.suppresses(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertFalse("unreadable: show", DoNotDisturb.suppresses(null))
        assertTrue(DoNotDisturb.suppresses(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(DoNotDisturb.suppresses(NotificationManager.INTERRUPTION_FILTER_NONE))
        assertTrue(DoNotDisturb.suppresses(NotificationManager.INTERRUPTION_FILTER_ALARMS))
    }
}
