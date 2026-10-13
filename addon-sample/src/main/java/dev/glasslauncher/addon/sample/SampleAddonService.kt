package dev.glasslauncher.addon.sample

import dev.glasslauncher.addon.GlassAddonService
import dev.glasslauncher.addon.TileState

/** Reference add-on: a counter that grows on Select, and a read-only value tile. */
class SampleAddonService : GlassAddonService() {
    // Glass unbinds after every call, so the process can die between taps: the count lives in storage.
    private val prefs get() = getSharedPreferences("sample", MODE_PRIVATE)

    private fun counter() = prefs.getInt("count", 0).let { TileState("counter", "Counter", "add", on = it > 0, value = it.toString()) }
    private fun apiTile() = TileState("api", "API Level", "settings", value = "Glass API ${dev.glasslauncher.addon.AddonApi.LEVEL}")

    override fun tiles() = listOf(counter(), apiTile())

    override fun onTap(id: String): TileState? {
        if (id == "counter") prefs.edit().putInt("count", prefs.getInt("count", 0) + 1).apply()
        return tile(id)
    }
}
