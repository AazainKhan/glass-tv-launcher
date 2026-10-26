package dev.glasslauncher.ui

/**
 * Releases the renderer's leftover GPU textures. Every scene Home shows (a Top Shelf slide, an app's hero) is
 * baked to GPU bitmaps, and the renderer keeps each one it has drawn in its cache after the scene changes:
 * browsing titles grew Glass's graphics memory from 22 to 108 MB (perf-gate PSS 171 MB, budget 120). A
 * foreground app is never asked to trim, so Glass asks once Home has settled. The next frame re-uploads what
 * is on screen (one frame), which is why this waits for a pause rather than running on every change.
 * Uses the platform's own trim entry point (WindowManagerGlobal.trimMemory, not public; reflection, no-op if
 * it's ever unavailable).
 */
object GpuCaches {
    private val target: Pair<Any, java.lang.reflect.Method>? by lazy {
        runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            wmg.getMethod("getInstance").invoke(null)!! to wmg.getMethod("trimMemory", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    fun trim() = trimAt(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE)

    private fun trimAt(level: Int) {
        val (instance, method) = target ?: return
        runCatching { method.invoke(instance, level) }
    }

    /**
     * P200: while an app is in front Glass still counts as a foreground process (its services and overlay), so the
     * system never trims it and its renderer keeps ~35 MB of GPU memory. When the system is short of memory and Home is
     * hidden, Glass releases its GPU context itself (TRIM_MEMORY_COMPLETE, ~24 MB). Never on a timer: a release costs
     * the next return to Home a ~250 ms first frame, and smooth returns come first (a short trip releases nothing).
     */
    @Volatile private var hidden = false

    fun homeHidden() { hidden = true }
    fun homeShown() { hidden = false }

    /**
     * P247: Home has been behind an app long enough (see GlassApp's grace timer) — release the renderer's textures
     * now. This is not a memory emergency, but Home is off screen, so the release's ~250 ms first frame is paid on
     * the way back instead of by a frame anyone watches. Short trips never reach here.
     */
    fun releaseWhileHidden() {
        if (hidden) trimAt(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    /** RUNNING_LOW/CRITICAL or a background level while Home is hidden: release the GPU context now. */
    fun onPressure(level: Int) {
        val pressure = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (pressure && hidden) trimAt(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
