package dev.glasslauncher.system

import android.app.NotificationManager
import android.content.Context

/** One notice for Glass's top-right capsule. [actionable]: the user has to answer or act (pairing, a code to type, the sleep warning). */
data class Notice(val text: String, val forMs: Long, val actionable: Boolean = false, val detail: String? = null, val iconRes: Int = 0) {
    /** What makes two notices the same banner: its words, both lines (a device that connects and then disconnects is two banners). */
    val key: String get() = text + "\u0000" + detail.orEmpty()

    /** How long it is up: an actionable notice for as long as it asked, a plain one no longer than [MAX_PLAIN_MS] (tvOS: informational banners are a few seconds). */
    val shownMs: Long get() = if (actionable) forMs else forMs.coerceAtMost(MAX_PLAIN_MS)

    companion object { const val MAX_PLAIN_MS = 5_000L }
}

/**
 * tvOS shows one banner at a time and never stacks them (P163). The capsule shows [current]; the rest wait here. The same text offered
 * again is the same banner (its time is extended, nothing is queued twice); an actionable notice takes the place of a plain one that is
 * up (the plain one is dropped: it was only information); everything else queues behind, at most [MAX_PENDING], oldest plain ones dropped
 * first. Pure, so the rules are unit-tested; the accessibility service draws what this says.
 */
class BannerQueue {
    sealed interface Step {
        data class Show(val notice: Notice) : Step
        data class Extend(val ms: Long) : Step
        data object Wait : Step
    }

    private var current: Notice? = null
    private val pending = ArrayDeque<Notice>()

    val showing: Notice? get() = current
    val waiting: List<Notice> get() = pending.toList()

    fun offer(n: Notice): Step {
        val cur = current
        if (cur == null) { current = n; return Step.Show(n) }
        if (cur.key == n.key) {
            if (n.actionable && !cur.actionable) current = n
            return Step.Extend(n.shownMs)
        }
        if (n.actionable && !cur.actionable) { current = n; return Step.Show(n) }
        pending.removeAll { it.key == n.key }
        pending.addLast(n)
        while (pending.size > MAX_PENDING) {
            val drop = pending.firstOrNull { !it.actionable } ?: pending.first()
            pending.remove(drop)
        }
        return Step.Wait
    }

    /** The shown notice has gone: the next one (an actionable one first), or nothing. */
    fun finished(): Step {
        current = null
        val next = pending.firstOrNull { it.actionable } ?: pending.firstOrNull() ?: return Step.Wait
        pending.remove(next)
        current = next
        return Step.Show(next)
    }

    fun clear() { current = null; pending.clear() }

    companion object { const val MAX_PENDING = 3 }
}

/** Android's Do Not Disturb as the interruption filter: anything but "all" (priority only, alarms only, total silence) holds plain notices back. */
object DoNotDisturb {
    /** The filter value says Do Not Disturb is on; unknown or unreadable (null, UNKNOWN) is not, so a box that can't tell shows its notices. */
    fun suppresses(filter: Int?): Boolean =
        filter != null && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN && filter != NotificationManager.INTERRUPTION_FILTER_ALL

    fun on(context: Context): Boolean = runCatching {
        suppresses(context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter)
    }.getOrDefault(false)
}
