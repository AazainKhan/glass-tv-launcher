package dev.glasslauncher.addons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import dev.glasslauncher.addon.AddonApi
import dev.glasslauncher.addon.AddonPoint
import dev.glasslauncher.addon.ProviderRequest
import dev.glasslauncher.featured.FeaturedFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A Home row from an add-on, ready to draw. */
internal data class HomeRowShown(val pkg: String, val addon: String, val row: SafeRow)

/**
 * The provider points (Top Shelf, Home row, Settings page) on Glass's side. Everything an add-on sends is cleaned by
 * [AddonContent] before it is held here; Glass fetches the art itself and builds every Intent itself.
 */
internal object AddonSurfaces {
    /** pkg -> its Home row. Written off the main thread; Home reads it as state. */
    val homeRows = mutableStateMapOf<String, HomeRowShown>()
    private val gate = IntervalGate { SystemClock.elapsedRealtime() }
    private val focus = FocusRefresh(gate)
    private val rowRunning = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val PARALLEL = 3
    /** Home rows drawn at most this many (memory), first by package name. */
    const val MAX_ROWS = 3
    /** A row whose refresh failed stays up this long (a blip must not blank it), then goes; the retry comes after [RETRY_MS]. */
    const val STALE_MS = 30 * 60_000L
    const val RETRY_MS = 90_000L

    /** Whether a row last read fine at [lastGoodAt] may still be shown after a failed refresh at [now]. */
    internal fun keepStale(lastGoodAt: Long?, now: Long) = lastGoodAt != null && now - lastGoodAt <= STALE_MS

    /** Nothing is switched on, nothing is drawn and nothing is stored: no reason to scan the packages. */
    internal fun nothingToRefresh(snap: AddonSnapshot, haveRows: Boolean) = snap.enabled.isEmpty() && !haveRows && snap.settings.isEmpty()

    internal enum class RowDecision { Replace, KeepAndRetry, ClearAndRetry, Nothing }

    /** What a read does to the row and its marks. A call that was never made ([ProviderResult.Skipped]) touches nothing: it must not shorten a fresh 15-minute mark to a retry. */
    internal fun rowDecision(r: ProviderResult<*>, lastGoodAt: Long?, now: Long) = when (r) {
        is ProviderResult.Ok -> RowDecision.Replace
        ProviderResult.Failed -> if (keepStale(lastGoodAt, now)) RowDecision.KeepAndRetry else RowDecision.ClearAndRetry
        ProviderResult.Skipped -> RowDecision.Nothing
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun req(snap: AddonSnapshot, pkg: String, id: String? = null) = ProviderRequest(id = id, values = snap.valuesOf(pkg))

    /**
     * What makes an add-on's content "new" so that it is read again before its interval is up: the user changed one of its
     * settings, or switched it on. (A re-signed add-on, or anything the add-on itself does, never changes this: the 15-minute
     * floor holds for those.)
     */
    internal fun version(snap: AddonSnapshot, pkg: String): Any = snap.valuesOf(pkg) to (snap.enabled[pkg] != null)

    /** Fills or clears the Home rows: at most every declared interval (15 min or more) unless the user changed a setting; never continuously. */
    suspend fun refreshRows(context: Context, snap: AddonSnapshot, purge: (Set<String>) -> Unit = {}) = withContext(Dispatchers.IO) {
        // Nothing switched on and nothing drawn: nothing to scan for.
        if (nothingToRefresh(snap, homeRows.isNotEmpty())) return@withContext
        val all = AddonScanner.scan(context)
        val live = all.filter { liveRow(it, snap) }.sortedBy { it.pkg }.take(MAX_ROWS)
        homeRows.keys.filter { p -> live.none { it.pkg == p } }.forEach { homeRows.remove(it); gate.forget("row:$it"); lastGood.remove(it); AddonArt.forget(it) }
        // An uninstalled add-on's stored settings go (checked per package, so a failed scan never purges anything).
        AddonPurge.uninstalled(snap.settings) { AddonScanner.isInstalled(context, it) }.takeIf { it.isNotEmpty() }?.let(purge)
        val sem = Semaphore(PARALLEL)
        coroutineScope { live.map { info -> async { sem.withPermit { refreshOne(context, snap, info) { true } } } }.awaitAll() }
    }

    private fun liveRow(i: AddonInfo, snap: AddonSnapshot) = i.compatible && AddonPoint.HOME_ROW in i.points && AddonTrust.isEnabled(snap.enabled, i.pkg, i.cert)

    /** One add-on's row, one at a time per add-on. A call that was not made (rate limited, muted, Home hidden, already running) changes nothing: no mark is overwritten. */
    private suspend fun refreshOne(context: Context, snap: AddonSnapshot, info: AddonInfo, homeVisible: () -> Boolean) {
        val key = "row:${info.pkg}"
        if (!rowRunning.add(info.pkg)) return
        try {
            val version = version(snap, info.pkg)
            if (!gate.due(key, version) || !homeVisible()) return
            val r = AddonHost.providerResult(context, snap, info.pkg, AddonPoint.HOME_ROW, AddonApi.METHOD_HOME_ROW, req(snap, info.pkg)) { AddonContent.row(it, info.pkg, context.packageName, info.title) }
            when (rowDecision(r, lastGood[info.pkg], now())) {
                RowDecision.Replace -> {
                    val row = (r as ProviderResult.Ok).value
                    homeRows[info.pkg] = HomeRowShown(info.pkg, info.title, row)
                    AddonArt.allow(info.pkg, row.items.mapNotNull { it.image })
                    lastGood[info.pkg] = now()
                    gate.mark(key, version, row.refreshMinutes)
                }
                RowDecision.KeepAndRetry -> gate.retryIn(key, version, RETRY_MS)
                RowDecision.ClearAndRetry -> { homeRows.remove(info.pkg); AddonArt.forget(info.pkg); gate.retryIn(key, version, RETRY_MS) }
                RowDecision.Nothing -> Unit
            }
        } finally {
            rowRunning.remove(info.pkg)
        }
    }

    /**
     * The user focused [pkg]'s Home row: read that one add-on again if its interval is over (never earlier, never while Home is hidden,
     * one at a time). [homeVisible] is asked again right before the call. Called off Main; it never blocks focus or scrolling.
     */
    suspend fun refreshRowOnFocus(context: Context, snap: AddonSnapshot, pkg: String, homeVisible: () -> Boolean) {
        val key = "row:$pkg"
        if (!focus.begin(key, version(snap, pkg), homeVisible())) return
        try {
            val info = withContext(Dispatchers.IO) { AddonScanner.scan(context, pkg).firstOrNull() }?.takeIf { liveRow(it, snap) } ?: return
            refreshOne(context, snap, info, homeVisible)
        } finally { focus.end(key) }
    }

    /** The Top Shelf feed of [pkg] and how often it may be asked again, or null. */
    suspend fun feed(context: Context, snap: AddonSnapshot, pkg: String): Pair<FeaturedFeed, Int>? {
        val title = titleOf(context, pkg) ?: return null
        val r = AddonHost.provider(context, snap, pkg, AddonPoint.TOPSHELF, AddonApi.METHOD_FEED, req(snap, pkg)) { AddonContent.feed(it, pkg, title) }
        AddonArt.allow("$pkg#feed", r?.first?.items?.flatMap { listOfNotNull(it.image, it.logo) } ?: emptyList())
        return r
    }

    suspend fun form(context: Context, snap: AddonSnapshot, pkg: String): SafeForm? {
        val title = titleOf(context, pkg) ?: return null
        return AddonHost.provider(context, snap, pkg, AddonPoint.SETTINGS, AddonApi.METHOD_SETTINGS_FORM, req(snap, pkg)) { AddonContent.form(it, title) }
    }

    /** The options of dynamic choice field [fieldId] (the current values ride along in the request), or null. Own rate budget per field. */
    suspend fun options(context: Context, snap: AddonSnapshot, pkg: String, fieldId: String): List<dev.glasslauncher.addon.Choice>? =
        if (!AddonHost.optionsBudget(pkg)) null else AddonHost.provider(context, snap, pkg, AddonPoint.SETTINGS, AddonApi.METHOD_FIELD_OPTIONS, req(snap, pkg, fieldId), "${AddonApi.METHOD_FIELD_OPTIONS}:$fieldId") { AddonContent.options(it) }

    // The package scan is a binder call: never on Main.
    private suspend fun titleOf(context: Context, pkg: String) = withContext(Dispatchers.IO) { AddonScanner.scan(context, pkg).firstOrNull()?.title }

    /** Runs a form action button; the add-on's short message (maybe empty), or null if it failed. */
    suspend fun action(context: Context, snap: AddonSnapshot, pkg: String, fieldId: String): String? =
        AddonHost.provider(context, snap, pkg, AddonPoint.SETTINGS, AddonApi.METHOD_SETTINGS_ACTION, req(snap, pkg, fieldId)) { AddonContent.action(it) }

    /**
     * Select on an add-on card. Glass builds the Intent: VIEW of a validated https/market link, an activity of the add-on's own
     * package, or its own launcher entry. No URI permission is ever granted and nothing else is reachable.
     */
    fun open(context: Context, pkg: String, action: AddonAction): Boolean {
        val intent = when (action) {
            is AddonAction.View -> Intent(Intent.ACTION_VIEW, Uri.parse(action.uri))
            is AddonAction.Component -> Intent().setComponent(ComponentName(action.pkg, action.cls))
            AddonAction.LaunchApp -> context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: context.packageManager.getLaunchIntentForPackage(pkg)
        } ?: return false
        return try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }
}
