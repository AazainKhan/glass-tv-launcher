package dev.glasslauncher.data

import kotlinx.serialization.Serializable

@Serializable
data class LauncherConfig(
    val dock: List<String> = emptyList(),
    /** Grid order. Entries are "app:<package>" or "folder:<id>"; unknown entries are ignored. */
    val order: List<String> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** Package name -> absolute path of a user-chosen icon image. */
    val customIcons: Map<String, String> = emptyMap(),
    val iconPack: String? = null,
    val theme: ThemeMode = ThemeMode.Dark,
    val wallpaperDark: Wallpaper = Wallpaper(WallpaperKind.Preset, "aurora"),
    val wallpaperLight: Wallpaper = Wallpaper(WallpaperKind.Preset, "dawn"),
    val featured: FeaturedConfig = FeaturedConfig(),
    val screensaver: ScreensaverConfig = ScreensaverConfig(),
    val idleFadeMinutes: Int = 3,
    val clock24h: Boolean = false,
    val weather: WeatherConfig? = null,
    val showNowPlaying: Boolean = true,
    val homeGuard: Boolean = false,
    val seededDefaults: Boolean = false,
    val reduceMotion: Auto = Auto.Auto,
    val reduceTransparency: Boolean = false,
    val sounds: Boolean = true,
    /** Start the Aerial screensaver after this many idle minutes on Home; 0 = leave it to the system. */
    val aerialsOnIdleMinutes: Int = 0,
    val tipsSeen: Boolean = false,
    /** What fills the screen behind Home: featured artwork, the wallpaper, or Aerial video. */
    val background: BackgroundMode = BackgroundMode.Featured,
    /** Text Size, tvOS-style: 1.0 default, up to 1.3; tiles and gutters grow with it. */
    val textScale: Float = 1f,
    /** Packages seen before; anything not in here is new and gets the blue dot until opened. */
    val seenApps: Set<String> = emptySet(),
    /** Most recently opened first; the app switcher's fallback when usage access isn't granted. */
    val recentApps: List<String> = emptyList(),
    /** Remote button name (e.g. "KEYCODE_APP_1") -> action; see RemoteAction. Missing = Fire TV default. */
    val remoteButtons: Map<String, String> = emptyMap(),
)

@Serializable
enum class BackgroundMode { Featured, Wallpaper, Motion }

@Serializable
enum class ThemeMode { System, Light, Dark }

/** Auto follows the system accessibility setting. */
@Serializable
enum class Auto { Auto, On, Off }

@Serializable
enum class WallpaperKind { Preset, File, Url }

@Serializable
data class Wallpaper(val kind: WallpaperKind, val value: String)

@Serializable
data class Folder(val id: String, val name: String, val apps: List<String>)

@Serializable
enum class FeaturedSourceId { Off, Stremio, Tmdb, YouTube, Plex }

/** Featured Row: follow the focused top-row app (tvOS), always one source, or off. */
enum class FeaturedMode { FocusedApp, OneSource, Off }

@Serializable
data class FeaturedConfig(
    /** The one source, and the fallback in [FeaturedMode.FocusedApp]. (Off here is the pre-mode way of turning it off.) */
    val source: FeaturedSourceId = FeaturedSourceId.Stremio,
    val mode: FeaturedMode = FeaturedMode.OneSource,
    val stremioCatalog: String = "movie/top",
    val tmdbKey: String = "",
    /** TMDB watch provider: netflix, prime, appletv, disney, max, or trending. */
    val tmdbProvider: String = "netflix",
    val tmdbRegion: String = "US",
    val youtubeKey: String = "",
    val youtubeRegion: String = "US",
    val plexToken: String = "",
    val plexClientId: String = "",
)

@Serializable
data class ScreensaverConfig(
    val quality: AerialQuality = AerialQuality.Hd1080,
    val showLocation: Boolean = true,
    val showClock: Boolean = true,
)

@Serializable
enum class AerialQuality { Hd1080, Uhd4k }

@Serializable
data class WeatherConfig(val city: String, val latitude: Double, val longitude: Double, val fahrenheit: Boolean)

fun appKey(pkg: String) = "app:$pkg"
fun folderKey(id: String) = "folder:$id"
