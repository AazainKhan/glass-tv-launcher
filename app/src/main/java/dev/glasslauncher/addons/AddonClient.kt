package dev.glasslauncher.addons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import dev.glasslauncher.addon.AddonApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure core of a call: at most one call in flight per add-on [key], a bounded wait, and every failure (connect error,
 * dead object, exception, timeout) is a null. The transaction runs on [scope], outside the caller, because a call
 * stuck inside the add-on cannot be interrupted; it keeps the add-on "in flight" until it really returns.
 */
internal class AddonCall(private val timeoutMs: Long, private val scope: CoroutineScope) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    suspend fun <B> run(key: String, connect: suspend () -> B, transact: (B) -> String?): String? {
        if (!inFlight.add(key)) return null
        var handedOff = false
        try {
            return withTimeoutOrNull(timeoutMs) {
                val binder = connect()
                val job = scope.async { try { transact(binder) } catch (_: Exception) { null } finally { inFlight.remove(key) } }
                handedOff = true
                job.await()
            }
        } catch (e: CancellationException) {
            throw e // our caller was cancelled: not an add-on failure, never swallowed
        } catch (_: Exception) {
            return null
        } finally {
            if (!handedOff) inFlight.remove(key)
        }
    }
}

/** Bind on demand, one transaction, unbind. Everything runs off Main; the main thread only sees the connect callbacks. */
@OptIn(ExperimentalCoroutinesApi::class)
internal object AddonClient {
    const val TIMEOUT_MS = 2_000L
    private val core = AddonCall(TIMEOUT_MS, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4)))

    /** The add-on's reply string, or null on timeout, bind failure or error. */
    suspend fun call(context: Context, info: AddonInfo, code: Int, request: String): String? = withContext(Dispatchers.IO) {
        val connected = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) { connected.completeExceptionally(IllegalStateException("disconnected")) }
            override fun onBindingDied(name: ComponentName) { connected.completeExceptionally(IllegalStateException("binding died")) }
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("null binding")) }
        }
        val intent = Intent(AddonApi.ACTION).setComponent(ComponentName(info.pkg, info.service))
        try {
            core.run(info.pkg, connect = {
                check(context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) { "bind refused" }
                connected.await()
            }, transact = { transact(it, code, request) })
        } finally {
            try { context.unbindService(conn) } catch (_: IllegalArgumentException) {}
        }
    }

    private fun transact(binder: IBinder, code: Int, request: String): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(request)
            if (binder.transact(code, data, reply, 0)) reply.readString() else null
        } finally {
            data.recycle(); reply.recycle()
        }
    }
}
