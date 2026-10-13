package dev.glasslauncher.addon

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Glass add-on contract. Additive-only within a level; Glass supports this level and the one before it. */
object AddonApi {
    const val LEVEL = 1
    const val ACTION = "dev.glasslauncher.ADDON"
    /** Signature permission Glass declares; an add-on's service requires it, so only Glass can bind. */
    const val PERMISSION = "dev.glasslauncher.permission.BIND_ADDON"

    const val META_API = "dev.glasslauncher.addon.api"
    const val META_MIN_GLASS_API = "dev.glasslauncher.addon.min_glass_api"
    const val META_TITLE = "dev.glasslauncher.addon.title"
    const val META_ICON = "dev.glasslauncher.addon.icon"
    const val META_NEEDS_ROOT = "dev.glasslauncher.addon.needs_root"

    /** Binder transaction codes; every call carries one JSON string and returns one. */
    const val GET_TILES = 1
    const val GET_TILE_STATE = 2
    const val ON_TAP = 3
    // 4 was onLongPress: dropped (hold-Select is Control Center edit mode); never reuse the code.

    /** Can Glass at [glassLevel] talk to an add-on built for [api] that needs at least [minGlassApi]? */
    fun compatible(api: Int, minGlassApi: Int, glassLevel: Int = LEVEL): Boolean =
        api >= 1 && minGlassApi <= glassLevel && api >= glassLevel - 1
}

/** One Control Center tile. Glass draws it; [icon] is a glyph key from Glass's own set (unknown = a default). */
@Serializable
data class TileState(
    val id: String,
    val label: String,
    val icon: String = "",
    val on: Boolean = false,
    /** Second line (about 18 characters); null shows On or Off from [on]. */
    val value: String? = null,
    val enabled: Boolean = true,
)

@Serializable data class AddonRequest(val api: Int = AddonApi.LEVEL, val tile: String? = null)
@Serializable data class TilesReply(val api: Int = AddonApi.LEVEL, val tiles: List<TileState> = emptyList(), val error: String? = null)
/** Reply to getTileState and onTap: the tile's new state (null = unknown tile). [error] set = the add-on failed. */
@Serializable data class StateReply(val tile: TileState? = null, val error: String? = null)

/** JSON wire format. Unknown fields are ignored on read; decoders return null for malformed input. */
object AddonCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeRequest(r: AddonRequest) = json.encodeToString(AddonRequest.serializer(), r)
    fun encodeTiles(r: TilesReply) = json.encodeToString(TilesReply.serializer(), r)
    fun encodeState(r: StateReply) = json.encodeToString(StateReply.serializer(), r)

    fun decodeRequest(s: String?): AddonRequest? = decode(s, AddonRequest.serializer())
    fun decodeTiles(s: String?): TilesReply? = decode(s, TilesReply.serializer())
    fun decodeState(s: String?): StateReply? = decode(s, StateReply.serializer())

    private fun <T> decode(s: String?, ser: kotlinx.serialization.KSerializer<T>): T? =
        if (s == null) null else try { json.decodeFromString(ser, s) } catch (_: SerializationException) { null } catch (_: IllegalArgumentException) { null }
}
