package dev.glasslauncher.system

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Back while the keyboard is up (P230). Fire's keyboard is a modal window of its own that did not close on Back (its Back moved focus
 * within its own buttons), so a remote user was stuck until Home. With the accessibility service on, Glass takes that Back itself: the
 * service sees it before the keyboard does, asks the text field Glass has open to end its editing (which hides the keyboard through the
 * activity, as the keyboard's Search key does), and swallows the key; the next Back then closes Search as usual. Without the service
 * (plain APK) the keyboard keeps Back, as before.
 */
object ImeBack {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** One emission per Back that Glass took for the keyboard; Glass's text field listens while it has focus. */
    val requests: SharedFlow<Unit> = _requests

    fun request() = _requests.tryEmit(Unit)

    /** A Glass text field has focus and is waiting for this (so the key is never taken for nothing). */
    val listening: Boolean get() = _requests.subscriptionCount.value > 0

    /**
     * Whether this Back is Glass's: a keyboard is showing, Glass's own screen is the one in front (a keyboard over another app is that
     * app's), and one of Glass's text fields is there to end its editing (else the key would be swallowed for nothing and the user stuck).
     * The key's other half is taken with it, so the keyboard never sees a lone Back-up.
     */
    fun takesBack(imeShowing: Boolean, glassInFront: Boolean, fieldListening: Boolean) = imeShowing && glassInFront && fieldListening
}
