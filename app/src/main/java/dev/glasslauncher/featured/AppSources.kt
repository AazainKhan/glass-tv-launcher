package dev.glasslauncher.featured

import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.FeaturedMode
import dev.glasslauncher.data.FeaturedSourceId

/**
 * Which featured source belongs to which app, for the "Focused app" mode (tvOS: the top shelf shows the
 * focused top-row app's content). Streaming services without their own API come from TMDB's
 * per-service lists, so they need a TMDB key; anything unmapped or not set up uses the default source.
 */
object AppSources {
    private val bySource: Map<String, (FeaturedConfig) -> FeaturedConfig> = buildMap {
        listOf("com.stremio.one").forEach { put(it) { c -> c.copy(source = FeaturedSourceId.Stremio) } }
        listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube.tvunplugged", "io.gh.reisxd.tizentube.cobalt")
            .forEach { put(it) { c -> c.copy(source = FeaturedSourceId.YouTube) } }
        listOf("com.plexapp.android").forEach { put(it) { c -> c.copy(source = FeaturedSourceId.Plex) } }
        fun tmdb(provider: String, vararg pkgs: String) = pkgs.forEach { put(it) { c -> c.copy(source = FeaturedSourceId.Tmdb, tmdbProvider = provider) } }
        tmdb("netflix", "com.netflix.ninja", "com.netflix.mediaclient")
        tmdb("prime", "com.amazon.avod", "com.amazon.amazonvideo.livingroom", "com.amazon.firebat")
        tmdb("disney", "com.disney.disneyplus")
        tmdb("appletv", "com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv")
        tmdb("max", "com.wbd.stream", "com.hbo.hbonow", "com.hbo.max.android.tv")
        tmdb("hulu", "com.hulu.livingroomplus", "com.hulu.plus")
    }

    /** Whether [cfg]'s source can load (has the key or sign-in it needs). */
    fun usable(cfg: FeaturedConfig): Boolean = when (cfg.source) {
        FeaturedSourceId.Off -> false
        FeaturedSourceId.Stremio -> true
        FeaturedSourceId.Tmdb -> cfg.tmdbKey.isNotBlank()
        FeaturedSourceId.YouTube -> cfg.youtubeKey.isNotBlank()
        FeaturedSourceId.Plex -> cfg.plexToken.isNotBlank()
        FeaturedSourceId.ContinueWatching, FeaturedSourceId.TvApp -> true
    }

    /** True when [pkg] has a source of its own that's set up. */
    fun hasOwn(pkg: String, cfg: FeaturedConfig): Boolean = bySource[pkg]?.invoke(cfg)?.let(::usable) == true

    /** What the shelf shows, given the mode and the focused top-row app; null means the shelf is off. */
    fun effective(cfg: FeaturedConfig, focusedPkg: String?, appsWithRows: Set<String> = emptySet()): FeaturedConfig? {
        if (cfg.mode == FeaturedMode.Off || cfg.source == FeaturedSourceId.Off) return null
        if (cfg.mode == FeaturedMode.FocusedApp && focusedPkg != null) {
            // The app's own TV rows come first, as on tvOS (needs Glass as a system app).
            if (focusedPkg in appsWithRows) return cfg.copy(source = FeaturedSourceId.TvApp, appPackage = focusedPkg)
            bySource[focusedPkg]?.invoke(cfg)?.takeIf(::usable)?.let { return it }
        }
        return cfg
    }
}
