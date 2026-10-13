package com.glasslauncher.browser

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import dev.glasslauncher.browserlink.Link
import dev.glasslauncher.browserlink.Msg

/** The browser's end of the channel to Glass: sends only [Msg]s, and takes messages only from Glass's uid. */
class GlassLink(private val context: Context, private val onMsg: (Msg) -> Unit) {
    /** Where Glass listens; handed over in the launch intent. */
    var glass: Messenger? = null
    private val handler = Handler(Looper.getMainLooper()) { m -> receive(m); true }
    private val incoming = Messenger(handler)

    private fun glassUid(): Int? = runCatching { context.packageManager.getPackageUid(Link.GLASS_PACKAGE, 0) }.getOrNull()

    private fun receive(m: Message) {
        val uid = glassUid() ?: return
        if (m.sendingUid != uid) return
        if (context.packageManager.checkPermission(Link.PERMISSION, Link.GLASS_PACKAGE) != PackageManager.PERMISSION_GRANTED) return
        val msg = runCatching { Msg.decode(m.what) { m.data?.getString(it) } }.getOrNull() ?: return
        onMsg(msg)
    }

    /** False when Glass is not there to take it. */
    fun send(msg: Msg): Boolean {
        val target = glass ?: return false
        val m = Message.obtain(null, msg.what)
        m.replyTo = incoming
        m.data = Bundle().also { b -> msg.fields().forEach { (k, v) -> b.putString(k, v) } }
        return runCatching { target.send(m) }.isSuccess
    }

    fun dispose() = handler.removeCallbacksAndMessages(null)
}
