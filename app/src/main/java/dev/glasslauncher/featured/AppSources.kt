package dev.glasslauncher.featured

import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.FeaturedMode
import dev.glasslauncher.data.FeaturedSourceId

/**
 * Which featured source belongs to which app, for the "Focused app" mode (tvOS: the top shelf shows the
 * focused top-row app's content). Streaming services without their own API come from JustWatch's
 * popular titles per service; anything unmapped or not set up uses the default source.
 */
object AppSources {
    private val bySource: Map<String, (FeaturedConfig) -> FeaturedConfig> = buildMap {
        listOf("com.stremio.one").forEach { put(it) { c -> c.copy(source = FeaturedSourceId.Stremio) } }
        listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube.tvunplugged", "io.gh.reisxd.tizentube.cobalt")
            .forEach { put(it) { c -> c.copy(source = FeaturedSourceId.YouTube) } }
        listOf("com.plexapp.android").forEach { put(it) { c -> c.copy(source = FeaturedSourceId.Plex) } }
        // Streaming services: JustWatch's popular titles for the service, with links into the app (no key).
        JustWatch.services.forEach { s -> s.packages.forEach { put(it) { c -> c.copy(source = FeaturedSourceId.JustWatch, justWatchPackage = s.code) } } }
    }

    /** Whether [cfg]'s source can load (has the key or sign-in it needs). */
    fun usable(cfg: FeaturedConfig): Boolean = when (cfg.source) {
        FeaturedSourceId.Off -> false
        FeaturedSourceId.Stremio -> true
        FeaturedSourceId.Tmdb -> cfg.tmdbKey.isNotBlank()
        FeaturedSourceId.YouTube -> cfg.youtubeKey.isNotBlank()
        FeaturedSourceId.Plex -> cfg.plexToken.isNotBlank()
        FeaturedSourceId.ContinueWatching, FeaturedSourceId.TvApp, FeaturedSourceId.JustWatch -> true
    }

    /** True when [pkg] has a source of its own that's set up. */
    fun hasOwn(pkg: String, cfg: FeaturedConfig): Boolean = bySource[pkg]?.invoke(cfg)?.let(::usable) == true

    /** What the shelf's titles come from, given the mode and the focused top-row app; null means no titles. */
    fun effective(cfg: FeaturedConfig, focusedPkg: String?, appsWithRows: Set<String> = emptySet()): FeaturedConfig? {
        if (cfg.mode == FeaturedMode.Off || cfg.source == FeaturedSourceId.Off) return null
        if (cfg.mode == FeaturedMode.FocusedApp && focusedPkg != null) {
            // The app's own TV rows come first, as on tvOS (needs Glass as a system app).
            if (focusedPkg in appsWithRows) return cfg.copy(source = FeaturedSourceId.TvApp, appPackage = focusedPkg)
            bySource[focusedPkg]?.invoke(cfg)?.takeIf(::usable)?.let { return it }
            // Nothing of its own: the shelf keeps the app's hero (its logo or screen), as on tvOS.
            return null
        }
        return cfg
    }
}
