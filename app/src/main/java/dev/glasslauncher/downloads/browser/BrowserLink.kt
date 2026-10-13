package dev.glasslauncher.downloads.browser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import dev.glasslauncher.browserlink.Link
import dev.glasslauncher.browserlink.Msg
import dev.glasslauncher.downloads.Downloads
import dev.glasslauncher.home.Overlay
import dev.glasslauncher.system.RemoteNotice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Glass's end of the Glass Browser (a separate, unprivileged APK, scripts/browser). Glass opens it by component and
 * hands it a Messenger; only that package, holding the signature permission, is listened to. It asks for downloads and
 * the keyboard, Glass applies its own rules, and the browser never writes the Downloads folder.
 */
object BrowserLink {
    private val component = ComponentName(Link.BROWSER_PACKAGE, Link.BROWSER_ACTIVITY)
    private const val TEXT_TTL_MS = 60_000L

    /** An overlay Home should open for the browser (the keyboard, or a confirm card). */
    class Ask(val overlay: () -> Overlay)

    private val _asks = MutableSharedFlow<Ask>(extraBufferCapacity = 4)
    /** Collected by Home; with no collector a request is dropped, since nothing could show it. */
    val asks: SharedFlow<Ask> = _asks.asSharedFlow()

    // Main thread only (the handler's looper).
    private var app: Context? = null
    private val approved = HashSet<String>()
    private var pendingText: Long? = null
    private var pendingAt = 0L
    private val handler = Handler(Looper.getMainLooper()) { m -> handle(m); true }
    private val messenger = Messenger(handler)

    /** Whether the browser is installed and Glass holds the permission, as the AirPlay row checks PhairPlay. */
    fun available(context: Context): Boolean {
        val pm = context.packageManager
        return runCatching { pm.getActivityInfo(component, 0) }.isSuccess &&
            pm.checkPermission(Link.PERMISSION, context.packageName) == PackageManager.PERMISSION_GRANTED
    }

    /** Starts (or brings forward) the browser; [url] is opened when it is https, else the browser starts on its address bar. */
    fun open(context: Context, url: String = "") {
        app = context.applicationContext
        val intent = Intent().setComponent(component).putExtra(Link.EXTRA_LINK, messenger).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Link.httpsHost(url) != null) intent.putExtra(Link.EXTRA_URL, url)
        runCatching { context.startActivity(intent) }
    }

    /** Glass is in front again after a keyboard or a card: show the page the user left, with no new URL. */
    private fun backToBrowser(c: Context) {
        runCatching { c.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun trusted(c: Context, m: Message): Boolean {
        val pm = c.packageManager
        val uid = runCatching { pm.getPackageUid(Link.BROWSER_PACKAGE, 0) }.getOrNull() ?: return false
        return m.sendingUid == uid && pm.checkPermission(Link.PERMISSION, Link.BROWSER_PACKAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun handle(m: Message) {
        val c = app ?: return
        if (!trusted(c, m)) return
        val msg = runCatching { Msg.decode(m.what) { m.data?.getString(it) } }.getOrNull() ?: return
        when (msg) {
            is Msg.Download -> download(c, msg, m.replyTo)
            is Msg.TextRequest -> text(c, msg, m.replyTo)
            Msg.Closed -> pendingText = null
            else -> Unit
        }
    }

    private fun reply(to: Messenger?, msg: Msg) {
        val m = Message.obtain(null, msg.what)
        m.data = Bundle().also { b -> msg.fields().forEach { (k, v) -> b.putString(k, v) } }
        runCatching { to?.send(m) }
    }

    /** Emits [ask] and tells the browser to bring Glass forward; false when Home isn't there to show it. */
    private fun show(ask: Ask, to: Messenger?): Boolean {
        if (_asks.subscriptionCount.value == 0 || !_asks.tryEmit(ask)) return false
        reply(to, Msg.ShowGlass)
        return true
    }

    private fun download(c: Context, d: Msg.Download, to: Messenger?) {
        fun queue(url: String, name: String?) {
            runCatching { Downloads.queue(c).add(url, name) }
                .onSuccess { RemoteNotice.show(c, "Downloading ${name ?: "file"}") }
                .onFailure { RemoteNotice.show(c, it.message ?: "Couldn't start that download") }
        }
        when (val r = BrowserDownload.decide(d, approved)) {
            is BrowserDownload.Queue -> queue(r.url, r.name)
            is BrowserDownload.Ask -> show(Ask {
                val from = if (r.referrer.isNotEmpty() && r.referrer != r.host) " (from a page on ${r.referrer})" else ""
                Overlay.Confirm("Download an app file?", "${r.host} is offering ${r.name ?: "a file"}$from. Only keep files from sites you trust.", "Download") {
                    approved += r.key
                    queue(r.url, r.name)
                    backToBrowser(c)
                }
            }, to)
            is BrowserDownload.Refuse -> RemoteNotice.show(c, r.reason)
        }
    }

    private fun text(c: Context, r: Msg.TextRequest, to: Messenger?) {
        val live = pendingText != null && SystemClock.elapsedRealtime() - pendingAt < TEXT_TTL_MS
        val prompt = TextPrompt.of(r)
        if (live || prompt == null) return
        val shown = show(Ask {
            Overlay.TextInput(prompt.title, prompt.initial, prompt.hint) { typed ->
                if (pendingText == r.id) pendingText = null
                reply(to, Msg.TextResult(r.id, typed.take(Link.MAX_TEXT)))
                backToBrowser(c)
            }
        }, to)
        if (shown) { pendingText = r.id; pendingAt = SystemClock.elapsedRealtime() }
    }
}
