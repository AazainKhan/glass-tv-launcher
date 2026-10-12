package dev.glasslauncher.featured

/** Which shelf feed may stand for the focused app (the offline give-up path of the dim hold). */
object ShelfFeed {
    /**
     * Whether the feed in the state ([stateKey], loaded for another app's config) may stand for [wantedKey]. While the
     * wanted key's load is under way ([settledKey] is not [wantedKey]) the feed up stays, so the cached path has no gap
     * (no logo hero, no title over the old scene). Once that load has settled and the feed is still another key's, the
     * fetch failed (offline): the old feed is not this app's and must go.
     */
    fun usable(stateKey: String?, wantedKey: String?, settledKey: String?): Boolean =
        stateKey != null && (stateKey == wantedKey || settledKey != wantedKey)

    /**
     * Focus has already left [shelfApp] (the shelf catches up a beat later): its Cover Flow and slideshow must stop
     * stepping now. Only the stepping stops; the feed stays up so the Cover Flow leaves with its own exit.
     */
    fun focusLeft(focusedApp: String?, shelfApp: String?, followsFocus: Boolean): Boolean =
        followsFocus && focusedApp != null && shelfApp != null && focusedApp != shelfApp

    /** Whether the previous app's scene is held dimmed: focus is on [heroApp] but the scene on screen is [sceneOfApp]'s. */
    fun holds(heroApp: String?, sceneOfApp: String?, featuredBackground: Boolean, reduceMotion: Boolean): Boolean =
        heroApp != null && sceneOfApp != null && sceneOfApp != heroApp && featuredBackground && !reduceMotion
}
