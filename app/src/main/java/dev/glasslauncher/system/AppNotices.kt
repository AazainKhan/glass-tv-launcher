package dev.glasslauncher.system

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * A notification from any app, as the drawer shows it (P98).
 *
 * Deliberately a flat snapshot rather than a [StatusBarNotification]: the drawer renders from this and never touches
 * the platform object, so it can be built and asserted on without a device, and an app dying under a stale notification
 * cannot take Home with it.
 */
data class AppNotice(
    /** The platform's own key - stable per notification, and what dismissing needs. */
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String?,
    val whenMs: Long,
    /** A notification that cannot be swiped away (a foreground service, a call). */
    val ongoing: Boolean,
    /** Published by a media app. Now Playing already owns those, so the drawer keeps them out by default. */
    val media: Boolean,
    /** The app's own small icon, for the row. Kept as an [android.graphics.drawable.Icon] because turning it into a
     *  Drawable needs a Context, which this snapshot deliberately does not have. */
    val icon: android.graphics.drawable.Icon? = null,
)

/**
 * The live notifications, newest first.
 *
 * A plain in-memory map on purpose: notifications are cheap to lose (the listener re-posts the active set when it
 * connects) and a disk copy would need eviction rules for no visible gain.
 */
object NoticeStore {
    private val items = java.util.concurrent.ConcurrentHashMap<String, AppNotice>()

    /**
     * Bumped on every change. Snapshot state, so the drawer re-reads when it moves and nothing has to diff - and it
     * works outside a composition, which is what lets this object stay testable without a Compose host.
     */
    private val _state = androidx.compose.runtime.mutableIntStateOf(0)
    val state: androidx.compose.runtime.State<Int> get() = _state

    /** True once the listener is connected, so the drawer can say "grant me" rather than "nothing here". */
    @Volatile private var live = false

    fun connected(): Boolean = live

    private fun bump() { _state.intValue++ }

    fun onConnected() { live = true }

    fun disconnected() {
        live = false
        items.clear()
        bump()
    }

    fun posted(n: AppNotice) {
        val old = items.put(n.key, n)
        if (old != n) bump()
    }

    fun removed(key: String) {
        if (items.remove(key) != null) bump()
    }

    /** Everything not on [blocked], newest first. [includeMedia] is off by default: Now Playing owns those. */
    fun list(blocked: Set<String> = emptySet(), includeMedia: Boolean = false): List<AppNotice> =
        items.values
            .filter { it.pkg !in blocked && (includeMedia || !it.media) }
            .sortedWith(compareByDescending<AppNotice> { it.whenMs })

    /** How many rows the drawer's dot would count. */
    fun count(blocked: Set<String> = emptySet(), includeMedia: Boolean = false): Int = list(blocked, includeMedia).size

    /** Moves [key] one place towards the top; [list] is rebuilt by the caller, so this re-stamps the time. */
    fun touch(key: String, nowMs: Long) {
        items[key]?.let { val n = it.copy(whenMs = nowMs); items[key] = n; bump() }
    }

    fun clear() { items.clear(); bump() }

    internal fun size() = items.size
}

/**
 * Turn a platform notification into an [AppNotice], or null when it is not showable (a group summary, an empty one).
 *
 * Two entry points: the [StatusBarNotification] one the listener calls, and a plain one taking the three things it
 * reads. The second exists because constructing a StatusBarNotification in a JVM test is awkward - the compiler
 * resolves its multi-argument constructor to the `(Parcel)` overload - and the extraction is the part worth testing.
 */
fun noticeOf(sbn: StatusBarNotification): AppNotice? = noticeOf(
    sbn.packageName ?: return null,
    if (sbn.postTime > 0) sbn.postTime else sbn.notification?.`when` ?: 0L,
    sbn.notification,
    key = sbn.key,
)

fun noticeOf(pkg: String, whenMs: Long, notification: Notification?, key: String? = null): AppNotice? {
    val n = notification ?: return null
    val ex = n.extras ?: return null
    // A group summary is the "3 messages" header, not a row of its own: the flag, not an extra.
    if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
    val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        ?: ""
    val text = (ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())?.takeIf { it.isNotBlank() }
    if (title.isBlank() && text == null) return null
    val category = n.category
    return AppNotice(
        key = key ?: "$pkg:$whenMs:$title",
        pkg = pkg,
        app = pkg,
        title = title,
        text = text,
        whenMs = whenMs,
        ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        media = category == Notification.CATEGORY_TRANSPORT || category == Notification.CATEGORY_SERVICE && "media" in (n.extras?.keySet() ?: emptySet()),
        icon = n.smallIcon,
    )
}
