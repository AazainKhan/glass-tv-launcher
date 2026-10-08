package dev.glasslauncher.home

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.glasslauncher.app
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.apps.TileSpec
import dev.glasslauncher.data.Folder
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.appKey
import dev.glasslauncher.data.folderKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface GridItem {
    val key: String

    data class App(val app: AppEntry) : GridItem {
        override val key get() = appKey(app.packageName)
    }

    data class FolderItem(val folder: Folder, val apps: List<AppEntry>) : GridItem {
        override val key get() = folderKey(folder.id)
    }
}

data class HomeLayout(
    val dock: List<AppEntry> = emptyList(),
    val grid: List<GridItem> = emptyList(),
    val installed: List<AppEntry> = emptyList(),
    val loaded: Boolean = false,
)

const val DOCK_SIZE = 6
const val RECENT_LIMIT = 12
const val COLUMNS = 6

class HomeModel(application: Application) : AndroidViewModel(application) {

    private val graph = application.app
    private val store = graph.config
    val config: StateFlow<LauncherConfig> = store.config

    val layout: StateFlow<HomeLayout> = combine(graph.apps.apps(), store.config) { apps, cfg ->
        buildLayout(apps, cfg)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout())

    init {
        viewModelScope.launch {
            layout.collect { l ->
                if (!l.loaded) return@collect
                seedDefaults(l.installed)
                seedSeen(l.installed)
                // Render every tile ahead of time so scrolling never waits on bitmap generation.
                val cfg = config.value
                l.installed.forEach { graph.tileArt.load(spec(it, cfg)) }
            }
        }
    }

    fun spec(app: AppEntry, cfg: LauncherConfig = config.value) =
        TileSpec(app, cfg.customIcons[app.packageName], cfg.iconPack)

    /** Opens an app; with a source view and tile bounds the system zooms it out of the tile (MOTION-03). */
    fun launch(app: AppEntry, from: android.view.View? = null, bounds: androidx.compose.ui.geometry.Rect? = null) {
        val intent = graph.apps.launchIntent(app) ?: return
        val options = if (from != null && bounds != null) {
            android.app.ActivityOptions.makeScaleUpAnimation(
                from, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt(),
            ).toBundle()
        } else null
        try { getApplication<Application>().startActivity(intent, options) } catch (_: ActivityNotFoundException) { }
        noteLaunched(app.packageName)
    }

    fun launchIntent(app: AppEntry): Intent? = graph.apps.launchIntent(app)

    /** Remembers the launch for the app switcher and clears the new-app dot. */
    fun noteLaunched(pkg: String) = edit {
        it.copy(seenApps = it.seenApps + pkg, recentApps = (listOf(pkg) + (it.recentApps - pkg)).take(RECENT_LIMIT))
    }

    /** New since the launcher last looked, and not opened yet: shows the blue dot. */
    fun isNew(pkg: String, cfg: LauncherConfig = config.value) = cfg.seenApps.isNotEmpty() && pkg !in cfg.seenApps

    fun appInfo(app: AppEntry) = startSafely(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")),
    )

    fun uninstall(app: AppEntry) = startSafely(
        Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")),
    )

    private fun startSafely(intent: Intent) {
        try { getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    fun addToDock(pkg: String) = edit { c ->
        if (pkg in c.dock || c.dock.size >= DOCK_SIZE) c
        else c.copy(dock = c.dock + pkg, folders = c.folders.withoutApp(pkg))
    }

    /** Out of the tray to the front of the grid; the first other grid app takes its place (the tray stays six). */
    fun removeFromDock(pkg: String) {
        val l = layout.value
        val next = l.grid.filterIsInstance<GridItem.App>().firstOrNull { it.app.packageName != pkg }?.app?.packageName
        edit { c ->
            val shown = l.dock.map { it.packageName }.ifEmpty { c.dock }
            val dock = shown.map { if (it == pkg && next != null) next else it }.filter { it != pkg }
            c.copy(dock = dock, order = listOf(appKey(pkg)) + (c.order - appKey(pkg)))
        }
    }

    fun hide(pkg: String) = edit { c ->
        c.copy(hidden = c.hidden + pkg, dock = c.dock - pkg, folders = c.folders.withoutApp(pkg))
    }

    fun unhide(pkg: String) = edit { c -> c.copy(hidden = c.hidden - pkg) }

    fun setCustomIcon(pkg: String, path: String?) = edit { c ->
        c.copy(customIcons = if (path == null) c.customIcons - pkg else c.customIcons + (pkg to path))
    }

    /** Moves a dock or grid item by [delta] positions within its own row set. Returns false at an edge. */
    fun move(key: String, delta: Int): Boolean {
        val l = layout.value
        val pkg = key.removePrefix("app:")
        val dockIndex = l.dock.indexOfFirst { it.packageName == pkg }
        if (key.startsWith("app:") && dockIndex >= 0) {
            val target = dockIndex + delta
            if (target !in l.dock.indices) return false
            edit { c ->
                // The tray as shown (filled apps included), so positions match what's on screen.
                val d = l.dock.map { it.packageName }.toMutableList()
                d.add(target, d.removeAt(dockIndex)); c.copy(dock = d)
            }
            return true
        }
        val keys = l.grid.map { it.key }.toMutableList()
        val from = keys.indexOf(key)
        val target = from + delta
        if (from < 0 || target !in keys.indices) return false
        keys.add(target, keys.removeAt(from))
        edit { it.copy(order = keys) }
        return true
    }

    /** Moves an app between the dock and the grid while in move mode. */
    fun moveIntoDock(pkg: String): Boolean {
        // A full tray of chosen apps has no room; a filled-in app gives way to one moved up on purpose.
        if (config.value.dock.count { d -> layout.value.dock.any { it.packageName == d } } >= DOCK_SIZE) return false
        addToDock(pkg)
        return true
    }

    fun moveOutOfDock(pkg: String) = removeFromDock(pkg)

    fun newFolder(pkg: String, name: String = "Folder"): String {
        val id = UUID.randomUUID().toString().take(8)
        edit { c ->
            val folders = c.folders.withoutApp(pkg) + Folder(id, name, listOf(pkg))
            val key = appKey(pkg)
            val order = c.order.map { if (it == key) folderKey(id) else it }.let { if (folderKey(id) in it) it else it + folderKey(id) }
            c.copy(folders = folders, dock = c.dock - pkg, order = order)
        }
        return id
    }

    fun addToFolder(pkg: String, folderId: String) = edit { c ->
        c.copy(
            folders = c.folders.withoutApp(pkg).map { if (it.id == folderId) it.copy(apps = it.apps + pkg) else it },
            dock = c.dock - pkg,
        )
    }

    fun removeFromFolder(pkg: String, folderId: String) = edit { c ->
        val folderIndex = c.order.indexOf(folderKey(folderId)).coerceAtLeast(0)
        val order = c.order.toMutableList().apply { remove(appKey(pkg)); add((folderIndex + 1).coerceAtMost(size), appKey(pkg)) }
        c.copy(folders = c.folders.map { if (it.id == folderId) it.copy(apps = it.apps - pkg) else it }, order = order)
    }

    fun renameFolder(folderId: String, name: String) = edit { c ->
        c.copy(folders = c.folders.map { if (it.id == folderId) it.copy(name = name.ifBlank { it.name }) else it })
    }

    fun deleteFolder(folderId: String) = edit { c ->
        val folder = c.folders.firstOrNull { it.id == folderId } ?: return@edit c
        val idx = c.order.indexOf(folderKey(folderId))
        val order = c.order.toMutableList().apply {
            if (idx >= 0) { removeAt(idx); addAll(idx, folder.apps.map(::appKey)) }
        }
        c.copy(folders = c.folders - folder, order = order)
    }

    fun edit(transform: (LauncherConfig) -> LauncherConfig) {
        viewModelScope.launch { store.update(transform) }
    }

    private suspend fun seedDefaults(installed: List<AppEntry>) {
        // Hidden-by-default entries added after a launcher was already set up (seeding runs once).
        if (config.value.seededDefaults && config.value.hiddenMigration < HIDDEN_MIGRATION) {
            val have = installed.map { it.packageName }.toSet()
            store.update { c -> c.copy(hidden = c.hidden + LATER_HIDDEN.filter { it in have }, hiddenMigration = HIDDEN_MIGRATION) }
        }
        if (config.value.seededDefaults) return
        val have = installed.map { it.packageName }.toSet()
        val dock = DEFAULT_DOCK.filter { it in have }.distinct().take(DOCK_SIZE)
        val hidden = DEFAULT_HIDDEN.filter { it in have }.toSet()
        store.update { c -> c.copy(dock = c.dock.ifEmpty { dock }, hidden = c.hidden + hidden, seededDefaults = true) }
    }

    /** Everything installed when the launcher first runs counts as already seen. */
    private suspend fun seedSeen(installed: List<AppEntry>) {
        if (config.value.seenApps.isNotEmpty()) return
        store.update { c -> c.copy(seenApps = c.seenApps + installed.map { it.packageName }) }
    }

    companion object {
        private val DEFAULT_DOCK = listOf(
            "com.netflix.ninja", "com.netflix.mediaclient",
            "com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.teamsmart.videomanager.tv",
            "com.amazon.avod", "com.amazon.amazonvideo.livingroom",
            "com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv",
            "com.disney.disneyplus", "com.plexapp.android", "com.stremio.one", "com.spotify.tv.android",
        )

        /** Fire TV system entries with placeholder icons that most people never open from a launcher. */
        private val DEFAULT_HIDDEN = listOf(
            "com.amazon.firebat", "com.amazon.firetv.troubleshooting", "com.amazon.ftv.profilepicker",
            "com.amazon.ftv.screensaver", "com.amazon.hedwig", "com.amazon.ssm", "com.amazon.tv.earlyaccess",
            "com.amazon.tv.ftvambient", "com.amazon.whasettings",
        )

        /** Added to existing installs once (version [HIDDEN_MIGRATION]): Fire TV Early Access traps the remote. */
        private val LATER_HIDDEN = listOf("com.amazon.tv.earlyaccess")
        private const val HIDDEN_MIGRATION = 1

        fun buildLayout(apps: List<AppEntry>, cfg: LauncherConfig): HomeLayout {
            val byPkg = apps.associateBy { it.packageName }
            val visible = apps.filter { it.packageName !in cfg.hidden }
            val visiblePkgs = visible.map { it.packageName }.toSet()
            val chosen = cfg.dock.filter { it in visiblePkgs }.distinct().take(DOCK_SIZE).map { byPkg.getValue(it) }
            val chosenPkgs = chosen.map { it.packageName }.toSet()

            val folders = cfg.folders.mapNotNull { f ->
                val members = f.apps.filter { it in visiblePkgs && it !in chosenPkgs }.distinct().map { byPkg.getValue(it) }
                if (members.isEmpty()) null else GridItem.FolderItem(f, members)
            }
            val inFolders = folders.flatMap { it.apps }.map { it.packageName }.toSet()
            val loose = visible.filter { it.packageName !in chosenPkgs && it.packageName !in inFolders }

            val items = LinkedHashMap<String, GridItem>()
            (loose.map { GridItem.App(it) } + folders).forEach { items[it.key] = it }
            val grid = cfg.order.mapNotNull { items.remove(it) } + items.values
            // The tray always holds six: the first apps of the grid (not folders) move up to fill it.
            val fill = grid.filterIsInstance<GridItem.App>().take(DOCK_SIZE - chosen.size)
            val filled = fill.map { it.key }.toSet()
            return HomeLayout(chosen + fill.map { it.app }, grid.filter { it.key !in filled }, apps, loaded = true)
        }

        private fun List<Folder>.withoutApp(pkg: String) = map { it.copy(apps = it.apps - pkg) }
    }
}
