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
    /** Authority of the add-on's ContentProvider (Top Shelf, Home row, Settings page); it must require [PERMISSION] too. */
    const val META_PROVIDER = "dev.glasslauncher.addon.provider"
    /** Comma list of the points the add-on supplies: any of [AddonPoint.ALL]; missing = tiles only. */
    const val META_POINTS = "dev.glasslauncher.addon.points"

    /** ContentProvider.call methods; the argument is a [ProviderRequest] JSON, the reply Bundle holds the reply JSON under [KEY_JSON]. */
    const val METHOD_FEED = "getFeed"
    const val METHOD_HOME_ROW = "getHomeRow"
    const val METHOD_SETTINGS_FORM = "getSettingsForm"
    const val METHOD_SETTINGS_ACTION = "onSettingsAction"
    const val KEY_JSON = "json"

    /** Binder transaction codes; every call carries one JSON string and returns one. */
    const val GET_TILES = 1
    const val GET_TILE_STATE = 2
    const val ON_TAP = 3
    /** Remote-button actions: the named actions the add-on offers, and running one. */
    const val GET_ACTIONS = 5
    const val ON_ACTION = 6
    // 4 was onLongPress: dropped (hold-Select is Control Center edit mode); never reuse the code.

    /** Can Glass at [glassLevel] talk to an add-on built for [api] that needs at least [minGlassApi]? */
    fun compatible(api: Int, minGlassApi: Int, glassLevel: Int = LEVEL): Boolean =
        api >= 1 && minGlassApi <= glassLevel && api >= glassLevel - 1
}

/** The extension points an add-on can declare in [AddonApi.META_POINTS]. */
object AddonPoint {
    const val TILES = "tiles"
    const val TOPSHELF = "topshelf"
    const val HOME_ROW = "homerow"
    const val SETTINGS = "settings"
    /** Named actions Glass lists in the remote-button picker (served by the service, like tiles). */
    const val ACTIONS = "actions"
    val ALL = setOf(TILES, TOPSHELF, HOME_ROW, SETTINGS, ACTIONS)
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

/** A remote-button action the add-on offers: [id] is stable (it is stored in the user's button mapping), [label] is what the picker shows. */
@Serializable data class ActionInfo(val id: String, val label: String)
@Serializable data class ActionsReply(val api: Int = AddonApi.LEVEL, val actions: List<ActionInfo> = emptyList(), val error: String? = null)
/** Reply to onAction: an optional short [notice] Glass shows briefly. */
@Serializable data class ActionResult(val notice: String? = null, val error: String? = null)

@Serializable data class AddonRequest(val api: Int = AddonApi.LEVEL, val tile: String? = null, val action: String? = null)
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

    fun encodeActions(r: ActionsReply) = json.encodeToString(ActionsReply.serializer(), r)
    fun decodeActions(s: String?): ActionsReply? = decode(s, ActionsReply.serializer())
    fun encodeActionResult(r: ActionResult) = json.encodeToString(ActionResult.serializer(), r)
    fun decodeActionResult(s: String?): ActionResult? = decode(s, ActionResult.serializer())

    fun encodeProviderRequest(r: ProviderRequest) = json.encodeToString(ProviderRequest.serializer(), r)
    fun decodeProviderRequest(s: String?): ProviderRequest? = decode(s, ProviderRequest.serializer())
    fun encodeFeed(r: FeedReply) = json.encodeToString(FeedReply.serializer(), r)
    fun decodeFeed(s: String?): FeedReply? = decode(s, FeedReply.serializer())
    fun encodeHomeRow(r: HomeRowReply) = json.encodeToString(HomeRowReply.serializer(), r)
    fun decodeHomeRow(s: String?): HomeRowReply? = decode(s, HomeRowReply.serializer())
    fun encodeForm(r: FormReply) = json.encodeToString(FormReply.serializer(), r)
    fun decodeForm(s: String?): FormReply? = decode(s, FormReply.serializer())
    fun encodeAction(r: ActionReply) = json.encodeToString(ActionReply.serializer(), r)
    fun decodeAction(s: String?): ActionReply? = decode(s, ActionReply.serializer())

    private fun <T> decode(s: String?, ser: kotlinx.serialization.KSerializer<T>): T? =
        if (s == null) null else try { json.decodeFromString(ser, s) } catch (_: SerializationException) { null } catch (_: IllegalArgumentException) { null }
}
