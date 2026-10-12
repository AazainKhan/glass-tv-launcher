package dev.glasslauncher.featured

/** Which shelf feed and scene may stand for the focused app (the offline give-up path of the dim hold). */
object ShelfFeed {
    /**
     * Whether the feed loaded under [stateKey] is the one [wantedKey] asks for. A failed fetch leaves the previous
     * app's feed in the repository state (so the shelf isn't blanked), but it is not this app's: it must not stand in
     * for the new app's shelf, scene or hero.
     */
    fun owns(stateKey: String?, wantedKey: String?): Boolean = stateKey != null && stateKey == wantedKey

    /**
     * Focus has already left [shelfApp] (the shelf catches up a beat later): its Cover Flow and slideshow must stop
     * stepping now. Only the stepping stops; the feed stays up so the Cover Flow leaves with its own exit.
     */
    fun focusLeft(focusedApp: String?, shelfApp: String?, followsFocus: Boolean): Boolean =
        followsFocus && focusedApp != null && shelfApp != null && focusedApp != shelfApp

    /** Whether the previous app's scene is held dimmed: focus is on [heroApp], but the scene on screen is [sceneOfApp]'s. */
    fun holds(heroApp: String?, sceneOfApp: String?, featuredBackground: Boolean, reduceMotion: Boolean): Boolean =
        heroApp != null && sceneOfApp != null && sceneOfApp != heroApp && featuredBackground && !reduceMotion
}
