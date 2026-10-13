package dev.glasslauncher.addon

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/** The add-on's side of the contract, without Android types so it tests on the JVM. */
interface AddonHandler {
    fun tiles(): List<TileState> = emptyList()
    fun tile(id: String): TileState? = tiles().firstOrNull { it.id == id }
    /** Handle Select; return the tile's new state. */
    fun onTap(id: String): TileState? = tile(id)
    /** The remote-button actions this add-on offers (declare the `actions` point). */
    fun actions(): List<ActionInfo> = emptyList()
    /** Run action [id]; return an optional short notice for Glass to show (null = none). */
    fun onAction(id: String): String? = null
}

/** Decodes a call, runs the handler and encodes the reply; an exception in the add-on becomes an error reply. */
object AddonServer {
    /** Null = refused or unknown code (the caller then sees a failed transaction). */
    fun handle(code: Int, request: String?, h: AddonHandler, permitted: Boolean): String? {
        if (!permitted) return null
        val req = AddonCodec.decodeRequest(request)
        val tile = req?.tile
        return try {
            when (code) {
                AddonApi.GET_TILES -> AddonCodec.encodeTiles(TilesReply(tiles = h.tiles()))
                AddonApi.GET_TILE_STATE -> AddonCodec.encodeState(StateReply(tile?.let(h::tile)))
                AddonApi.ON_TAP -> AddonCodec.encodeState(StateReply(tile?.let(h::onTap)))
                AddonApi.GET_ACTIONS -> AddonCodec.encodeActions(ActionsReply(actions = h.actions()))
                AddonApi.ON_ACTION -> AddonCodec.encodeActionResult(ActionResult(notice = req?.action?.let(h::onAction)))
                else -> null
            }
        } catch (e: Exception) {
            val msg = e.javaClass.simpleName
            when (code) {
                AddonApi.GET_TILES -> AddonCodec.encodeTiles(TilesReply(error = msg))
                AddonApi.GET_ACTIONS -> AddonCodec.encodeActions(ActionsReply(error = msg))
                AddonApi.ON_ACTION -> AddonCodec.encodeActionResult(ActionResult(error = msg))
                else -> AddonCodec.encodeState(StateReply(error = msg))
            }
        }
    }
}

/**
 * Base class for an add-on's Control Center tiles. Declare the subclass as an exported service with the
 * [AddonApi.ACTION] intent filter, `android:permission="dev.glasslauncher.permission.BIND_ADDON"` and the metadata
 * keys in [AddonApi]. Glass binds per call, so keep state in storage, not in the service.
 * One add-on service per package: Glass reads only the first it finds.
 */
abstract class GlassAddonService : Service(), AddonHandler {
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Defence in depth next to android:permission: only a holder of BIND_ADDON (Glass) gets an answer.
            val permitted = checkCallingPermission(AddonApi.PERMISSION) == PackageManager.PERMISSION_GRANTED
            if (!permitted) return false
            val out = AddonServer.handle(code, data.readString(), this@GlassAddonService, true)
                ?: return super.onTransact(code, data, reply, flags)
            reply?.writeString(out)
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
