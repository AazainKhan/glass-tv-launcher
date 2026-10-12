package dev.glasslauncher.featured

/** Which shelf feed and scene may stand for the focused app (the offline give-up path of the dim hold). */
object ShelfFeed {
    /**
     * Whether the feed loaded under [stateKey] is the one [wantedKey] asks for. A failed fetch leaves the previous
     * app's feed in the repository state (so the shelf isn't blanked), but it is not this app's: it must not show,
     * and its Cover Flow must not autoplay. With [followsFocus], the feed is also dropped the moment focus leaves
     * [shelfApp], not a beat later when the shelf catches up.
     */
    fun owns(stateKey: String?, wantedKey: String?, focusedApp: String?, shelfApp: String?, followsFocus: Boolean): Boolean {
        if (stateKey == null || stateKey != wantedKey) return false
        return !followsFocus || focusedApp == null || focusedApp == shelfApp
    }

    /** Whether the previous app's scene is held dimmed: focus is on [heroApp], but the scene on screen is [sceneOfApp]'s. */
    fun holds(heroApp: String?, sceneOfApp: String?, featuredBackground: Boolean, reduceMotion: Boolean): Boolean =
        heroApp != null && sceneOfApp != null && sceneOfApp != heroApp && featuredBackground && !reduceMotion
}
