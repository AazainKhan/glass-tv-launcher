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

    fun trim() {
        val (instance, method) = target ?: return
        runCatching { method.invoke(instance, android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) }
    }
}
