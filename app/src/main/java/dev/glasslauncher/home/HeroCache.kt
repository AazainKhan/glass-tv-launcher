package dev.glasslauncher.home

/**
 * The last few app heroes Home baked (most recently used kept), so moving back and forth along the tray
 * never bakes the same hero twice. Each is a full-screen backdrop (several MB), so it stays small and
 * empties when the system asks for memory or Glass goes to the background.
 */
class HeroCache<V>(private val max: Int) {
    private val map = object : LinkedHashMap<String, V>(max + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }

    val size: Int get() = synchronized(map) { map.size }
    operator fun get(key: String): V? = synchronized(map) { map[key] }
    operator fun set(key: String, value: V) { synchronized(map) { map[key] = value } }
    fun trim() = synchronized(map) { map.clear() }

    companion object {
        /** Home's app heroes. Two: the one showing and the one just left. */
        val app = HeroCache<dev.glasslauncher.glass.Backdrop>(max = 2)
    }
}
