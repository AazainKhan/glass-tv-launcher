package com.example.myaddon

import dev.glasslauncher.addon.ActionInfo
import dev.glasslauncher.addon.GlassAddonService
import dev.glasslauncher.addon.TileState

/**
 * Control Center tile and remote-button action. Glass binds this per call and unbinds, so the process can die between calls:
 * keep state in storage, never in fields.
 */
class MyAddonService : GlassAddonService() {
    private val prefs get() = getSharedPreferences("my-addon", MODE_PRIVATE)

    // CHANGE ME: the tile's label (about 24 characters), its glyph key (see the guide) and its value line (about 18).
    override fun tiles() = listOf(
        TileState(id = "counter", label = "My Counter", icon = "add", on = prefs.getInt("count", 0) > 0, value = prefs.getInt("count", 0).toString()),
    )

    /** Select on the tile: do the work, return the tile's new state. */
    override fun onTap(id: String): TileState? {
        if (id == "counter") prefs.edit().putInt("count", prefs.getInt("count", 0) + 1).apply()
        return tile(id)
    }

    /** Offered in Settings > Remote Buttons > a button > Add-on actions. */
    override fun actions() = listOf(ActionInfo("increment", "Increment my counter"))

    /** A mapped button was pressed. The returned text (optional) is shown briefly by Glass. */
    override fun onAction(id: String): String? {
        if (id != "increment") return null
        val n = prefs.getInt("count", 0) + 1
        prefs.edit().putInt("count", n).apply()
        return "Counter: $n"
    }
}
