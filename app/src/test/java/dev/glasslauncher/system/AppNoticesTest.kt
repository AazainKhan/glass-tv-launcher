package dev.glasslauncher.system

import android.app.Notification
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P98: the drawer's source. Newest first, blocklist honoured, media kept out (Now Playing owns those). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppNoticesTest {

    private fun notice(pkg: String, title: String, whenMs: Long, media: Boolean = false) =
        AppNotice(key = "$pkg:$title", pkg = pkg, app = pkg, title = title, text = null, whenMs = whenMs, ongoing = false, media = media)

    @Before fun clear() = NoticeStore.clear()

    @Test fun newestComesFirst() {
        NoticeStore.posted(notice("a", "old", 100))
        NoticeStore.posted(notice("a", "new", 300))
        NoticeStore.posted(notice("a", "middle", 200))
        assertEquals(listOf("new", "middle", "old"), NoticeStore.list().map { it.title })
    }

    @Test fun aRemovedNotificationGoes() {
        NoticeStore.posted(notice("a", "one", 100))
        NoticeStore.posted(notice("a", "two", 200))
        NoticeStore.removed(notice("a", "one", 100).key)
        assertEquals(listOf("two"), NoticeStore.list().map { it.title })
        NoticeStore.removed("nothing-like-this")   // must not throw
        assertEquals(1, NoticeStore.count())
    }

    @Test fun theBlocklistHidesAPackageAndNothingElse() {
        NoticeStore.posted(notice("spam", "s", 100))
        NoticeStore.posted(notice("keep", "k", 200))
        assertEquals(listOf("keep"), NoticeStore.list(blocked = setOf("spam")).map { it.pkg })
        assertEquals(2, NoticeStore.count())                       // blocked only affects the view
        assertEquals(1, NoticeStore.count(blocked = setOf("spam")))
    }

    @Test fun mediaIsKeptOutUnlessAskedFor() {
        NoticeStore.posted(notice("spotify", "song", 100, media = true))
        NoticeStore.posted(notice("mail", "hi", 200))
        assertEquals(listOf("mail"), NoticeStore.list().map { it.pkg })
        assertEquals(2, NoticeStore.list(includeMedia = true).size)
    }

    @Test fun anOngoingNotificationRaisesItWhenTouched() {
        NoticeStore.posted(notice("a", "first", 100))
        NoticeStore.posted(notice("a", "second", 200))
        NoticeStore.touch(notice("a", "first", 100).key, 300)
        assertEquals(listOf("first", "second"), NoticeStore.list().map { it.title })
    }

    /** The listener's own state, so the drawer can say "grant me" instead of "nothing here". */
    @Test fun connectionStateIsTracked() {
        assertFalse(NoticeStore.connected())
        NoticeStore.onConnected()
        assertTrue(NoticeStore.connected())
        NoticeStore.posted(notice("a", "x", 1))
        NoticeStore.disconnected()
        assertFalse(NoticeStore.connected())
        assertEquals(0, NoticeStore.count())     // and the snapshot is dropped, not left stale
    }

    @Test fun aNotificationBecomesARowAndAGroupSummaryDoesNot() {
        fun post(flags: Int, title: String?, text: String? = "body") = Notification().apply {
            this.flags = flags
            extras = Bundle().apply {
                title?.let { putCharSequence(Notification.EXTRA_TITLE, it) }
                text?.let { putCharSequence(Notification.EXTRA_TEXT, it) }
            }
            category = Notification.CATEGORY_MESSAGE
        }
        val row = noticeOf("com.app", 500L, post(0, "Hello"))
        assertEquals("Hello", row?.title)
        assertEquals("com.app", row?.pkg)
        assertEquals(500L, row?.whenMs)
        assertEquals(false, row?.ongoing)
        // A group summary is the "3 messages" header, not a row of its own.
        assertEquals(null, noticeOf("com.app", 500L, post(Notification.FLAG_GROUP_SUMMARY, "3 messages")))
        // And a notification with nothing to show is not a row either.
        assertEquals(null, noticeOf("com.app", 500L, post(0, null, text = null)))
        assertEquals(null, noticeOf("com.app", 500L, null))
    }

    @Test fun aTransportNotificationCountsAsMedia() {
        val n = Notification().apply {
            extras = Bundle().apply { putCharSequence(Notification.EXTRA_TITLE, "song") }
            category = Notification.CATEGORY_TRANSPORT
        }
        assertEquals(true, noticeOf("com.spotify", 1L, n)?.media)
    }
}
