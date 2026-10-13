package dev.glasslauncher.addons

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.runtime.mutableStateMapOf
import dev.glasslauncher.R
import dev.glasslauncher.addon.AddonApi
import dev.glasslauncher.addon.AddonCodec
import dev.glasslauncher.addon.AddonPoint
import dev.glasslauncher.addon.AddonRequest
import dev.glasslauncher.addon.ProviderRequest
import dev.glasslauncher.home.CcCatalogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Glass's side of the add-ons: finds the enabled ones, asks for their tiles, relays Select. Never runs anything for them. */
internal object AddonHost {
    private const val TAG = "AddonHost"
    private const val PARALLEL_BINDS = 3
    private val clock = { SystemClock.elapsedRealtime() }

    /** Tile id (`addon:<pkg>/<tile>`) -> what to draw. Written off the main thread; Compose reads it as state. */
    val shown = mutableStateMapOf<String, ShownTile>()
    @Volatile var catalog: List<CcCatalogEntry> = emptyList()
        private set
    @Volatile private var enabled: Map<String, String> = emptyMap()
    private val policy = AddonPolicy(clock)
    private val gate = RefreshGate(clock)
    private val infos = HashMap<String, AddonInfo>()
    /** Calls to one add-on, tiles and provider points alike, run one after another. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun lock(pkg: String) = locks.getOrPut(pkg) { Mutex() }

    fun tileId(pkg: String, tile: String) = addonTileId(pkg, tile)
    fun isMuted(pkg: String) = policy.isMuted(pkg)

    /** Everything installed that declares the action (for Settings > Add-ons). */
    suspend fun installed(context: Context): List<AddonInfo> = withContext(Dispatchers.IO) { AddonScanner.scan(context) }

    /** Unmutes an add-on (the user switched it off and on again). */
    fun resume(pkg: String) {
        policy.resume(pkg)
        gate.reset()
        for ((id, t) in shown.entries.toList()) if (t.pkg == pkg) shown[id] = t.copy(muted = false)
    }

    /**
     * Reads every enabled add-on's tiles, at most every 30 s (or when the enabled set changes), [PARALLEL_BINDS] binds at a
     * time; each add-on is bound for that one call only.
     */
    suspend fun refresh(context: Context, enabledNow: Map<String, String>) = withContext(Dispatchers.IO) {
        if (!gate.due(enabledNow)) return@withContext
        enabled = enabledNow
        val live = AddonScanner.scan(context).filter { it.compatible && AddonTrust.isEnabled(enabledNow, it.pkg, it.cert) }
        synchronized(infos) { infos.clear(); live.forEach { infos[it.pkg] = it } }
        shown.values.map { it.pkg }.distinct().filter { p -> live.none { it.pkg == p } }.forEach { shown.dropPkg(it) }
        val sem = Semaphore(PARALLEL_BINDS)
        coroutineScope { live.map { info -> async { sem.withPermit { readTiles(context, info) } } }.awaitAll() }
        catalog = shown.entries.sortedBy { it.key }.map { (id, t) ->
            CcCatalogEntry(id, t.state.label, "Add-on · ${t.addon}", glyph(t.state.icon), addon = t.addon)
        }
        gate.mark(enabledNow)
    }

    private suspend fun readTiles(context: Context, info: AddonInfo) {
        if (!policy.tryAcquire(info.pkg, "")) { if (policy.isMuted(info.pkg)) shown.failPkg(info.pkg, true); return }
        val tiles = invoke(context, info, AddonApi.GET_TILES, AddonRequest(), enabled, AddonReplies::tiles)
        if (tiles != null) shown.setTiles(info.pkg, info.title, tiles) else shown.failPkg(info.pkg, policy.isMuted(info.pkg))
    }

    suspend fun tap(context: Context, id: String) {
        val t = shown[id]?.takeIf { !it.muted && it.state.enabled } ?: return
        val info = synchronized(infos) { infos[t.pkg] } ?: return
        if (!policy.tryAcquire(info.pkg, t.state.id)) return
        // The reply carries the new state, so no second call lands inside the rate limit.
        val next = invoke(context, info, AddonApi.ON_TAP, AddonRequest(tile = t.state.id), enabled) { AddonReplies.state(it, t.state.id) } ?: return
        shown[id] = t.copy(state = next)
    }

    /** One verified call: the package and its signing certificate must still match what the user enabled. A reply that does not parse is a failure. */
    private suspend fun <T> invoke(context: Context, info: AddonInfo, code: Int, request: AddonRequest, enabledNow: Map<String, String>, parse: (String?) -> T?): T? = lock(info.pkg).withLock {
        val now = AddonScanner.scan(context, info.pkg).firstOrNull { it.service == info.service }
        if (now == null || !now.compatible || !AddonTrust.isEnabled(enabledNow, now.pkg, now.cert)) return@withLock null
        val t0 = SystemClock.elapsedRealtime()
        val out = parse(AddonClient.call(context, now, code, AddonCodec.encodeRequest(request)))
        // Cold start (process launch plus bind) must stay well inside the 2 s budget.
        Log.i(TAG, "${info.pkg} call=$code ${SystemClock.elapsedRealtime() - t0} ms ok=${out != null}")
        // Listing reads (the picker) do not count toward muting; real calls do.
        if (AddonNotices.countsTowardMute(code) && policy.report(info.pkg, out != null)) shown.failPkg(info.pkg, true)
        out
    }

    /** pkg -> the actions it offers (read when the button picker or the buttons page opens); Settings reads it as state. */
    val actionLists = mutableStateMapOf<String, ActionListing>()

    /** Reads the actions of every enabled add-on that declares them. A read that fails clears that add-on's stale list; one that is rate limited keeps it. */
    suspend fun loadActions(context: Context, snap: AddonSnapshot) = withContext(Dispatchers.IO) {
        val live = AddonScanner.scan(context).filter { it.compatible && AddonPoint.ACTIONS in it.points && AddonTrust.isEnabled(snap.enabled, it.pkg, it.cert) }
        actionLists.keys.filter { p -> live.none { it.pkg == p } }.forEach { actionLists.remove(it) }
        for (info in live) {
            if (!policy.tryAcquire(info.pkg, "actions")) continue
            val list = invoke(context, info, AddonApi.GET_ACTIONS, AddonRequest(), snap.enabled, AddonReplies::actions)
            if (list != null) actionLists[info.pkg] = ActionListing(info.service, info.title, list) else actionLists.remove(info.pkg)
        }
    }

    private val actionGate = ActionGate()

    /**
     * A remote button mapped to an add-on action was pressed: the add-on must be installed, the same service, compatible, switched
     * on with its current certificate and declare `actions`; then the usual budget applies (2 s, one call at a time, mute). A press
     * within the 1 s rate window waits for it and runs once more (one pending press per action; more are dropped). Glass runs nothing for the add-on.
     */
    suspend fun runAction(context: Context, snap: AddonSnapshot, ref: AddonActionRef): ActionOutcome = withContext(Dispatchers.IO) {
        val key = "${ref.pkg}/${ref.id}"
        if (!actionGate.enter(key)) return@withContext ActionOutcome.Dropped
        try {
            val info = AddonScanner.scan(context, ref.pkg).firstOrNull() ?: return@withContext ActionOutcome.Unavailable(null)
            if (info.service != ref.service || AddonPoint.ACTIONS !in info.points || policy.isMuted(ref.pkg)) return@withContext ActionOutcome.Unavailable(info.title)
            val wait = policy.nextAllowedIn(ref.pkg, "action:${ref.id}")
            if (wait > 0) kotlinx.coroutines.delay(wait)
            if (!policy.tryAcquire(ref.pkg, "action:${ref.id}")) return@withContext ActionOutcome.Unavailable(info.title)
            val notice = invoke(context, info, AddonApi.ON_ACTION, AddonRequest(action = ref.id), snap.enabled, AddonReplies::actionNotice)
                ?: return@withContext ActionOutcome.Unavailable(info.title)
            ActionOutcome.Done(AddonNotices.success(info.title, notice))
        } finally {
            actionGate.exit(key)
        }
    }

    /** Up to 20 option reads a minute per add-on, however many fields it has (a form cannot hammer its provider). */
    fun optionsBudget(pkg: String): Boolean = policy.windowAcquire(pkg, "options", 20, 60_000)

    /**
     * One provider call for a Top Shelf, Home row or Settings point: the add-on must be installed, compatible, switched on
     * with its current certificate, and declare [point]; then the shared budget applies (rate limit, mute, 2 s, one call at a
     * time). [ProviderResult.Skipped] = nothing was asked (refused, rate limited, muted); [ProviderResult.Failed] = it was asked and the
     * reply was junk, an error or too late (counted toward muting).
     */
    suspend fun <T> providerResult(context: Context, snap: AddonSnapshot, pkg: String, point: String, method: String, request: ProviderRequest, budgetKey: String = method, parse: (String?) -> T?): ProviderResult<T> = withContext(Dispatchers.IO) {
        val info = AddonScanner.scan(context, pkg).firstOrNull() ?: return@withContext ProviderResult.Skipped
        if (!info.compatible || !AddonTrust.isEnabled(snap.enabled, info.pkg, info.cert) || point !in info.points || info.authority == null) return@withContext ProviderResult.Skipped
        if (!policy.tryAcquire(pkg, budgetKey)) return@withContext ProviderResult.Skipped
        lock(pkg).withLock {
            val t0 = SystemClock.elapsedRealtime()
            val out = parse(AddonClient.provider(context, info, method, AddonCodec.encodeProviderRequest(request)))
            Log.i(TAG, "$pkg $method ${SystemClock.elapsedRealtime() - t0} ms ok=${out != null}")
            if (policy.report(pkg, out != null)) shown.failPkg(pkg, true)
            if (out != null) ProviderResult.Ok(out) else ProviderResult.Failed
        }
    }

    suspend fun <T> provider(context: Context, snap: AddonSnapshot, pkg: String, point: String, method: String, request: ProviderRequest, budgetKey: String = method, parse: (String?) -> T?): T? =
        (providerResult(context, snap, pkg, point, method, request, budgetKey, parse) as? ProviderResult.Ok<T>)?.value

    /** Glass's own template glyphs an add-on can name; anything else gets the puzzle piece. */
    @DrawableRes fun glyph(key: String): Int = when (key) {
        "add" -> R.drawable.ic_add
        "home" -> R.drawable.ic_home
        "code" -> R.drawable.ic_code
        "play" -> R.drawable.ic_play_arrow
        "music" -> R.drawable.ic_music_note
        "movie" -> R.drawable.ic_movie
        "weather" -> R.drawable.ic_weather_clear_day
        "speed" -> R.drawable.ic_speed
        "power" -> R.drawable.ic_power_settings_new
        "download" -> R.drawable.ic_download
        "tv" -> R.drawable.ic_tv
        "info" -> R.drawable.ic_info
        "settings" -> R.drawable.ic_settings
        else -> R.drawable.ic_widgets
    }
}
