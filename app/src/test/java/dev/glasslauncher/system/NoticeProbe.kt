package dev.glasslauncher.system

import android.content.Context
import android.view.View

/** A notice surface that records what was put on it (P229/P234): the tests' stand-in for the overlay window. */
internal class NoticeProbe(context: Context) {
    private val cards = ArrayList<View>()
    private val surface = object : NoticeSurface {
        override fun add(card: View): Boolean { cards.add(card); return true }
        override fun remove(card: View) { cards.remove(card) }
    }
    val host = NoticeHost(context, surface, "NoticeProbe")

    /** The latest card's words, title then line, as "title: line" (null: none was shown). */
    fun latestOrNull(): String? = (cards.lastOrNull()?.tag as? Notice)?.let { listOfNotNull(it.text, it.detail).joinToString(": ") }
    fun latest(): String = latestOrNull() ?: error("no notice card was shown")
    val count get() = cards.size
    fun reset() { cards.clear() }
}
