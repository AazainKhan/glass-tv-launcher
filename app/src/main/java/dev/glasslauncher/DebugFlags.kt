package dev.glasslauncher

/** Temporary perf bisect switches: adb shell setprop debug.glass.flags <bits>. */
object DebugFlags {
    val value: Int by lazy {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.glass.flags")).inputStream.bufferedReader().readText().trim().toInt()
        }.getOrDefault(0)
    }
    fun off(bit: Int) = value and bit != 0
    const val WALLPAPER = 1
    const val GLASS = 2
    const val SHELF = 4
    const val TILE_FX = 8
    const val LAYER = 16
    const val LABELS = 32
}
