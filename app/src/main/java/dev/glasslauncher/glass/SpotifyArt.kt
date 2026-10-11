package dev.glasslauncher.glass

/**
 * Spotify's image ids carry their size in four hex digits after "ab67616d0000" (albums): b273 is 640 px (what its
 * TV rows publish), 82c1 the same art at 2000 px, 1e02 at 300 (checked on the stick, P79). Artist images
 * ("ab6761610000...") top out at 640 (e5eb) and have no larger code.
 */
object SpotifyArt {
    private const val HOST = "https://i.scdn.co/image/"
    private const val ALBUM_640 = "ab67616d0000b273"
    private const val ALBUM_2000 = "ab67616d000082c1"

    /** The 2000 px URL of a 640 px album cover URL, or null for anything else. */
    fun large(url: String): String? =
        if (url.startsWith(HOST + ALBUM_640)) url.replaceFirst(ALBUM_640, ALBUM_2000) else null
}
