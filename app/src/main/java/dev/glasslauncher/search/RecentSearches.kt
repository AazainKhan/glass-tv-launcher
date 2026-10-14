package dev.glasslauncher.search

import android.content.Context

/** The searches the user made, newest first, kept on this device only ("Recently Searched"; clearable). Pure list logic, tested. */
object RecentSearches {
    const val MAX = 10
    private const val PREFS = "search"
    private const val KEY = "recent"

    fun add(list: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
    }

    fun remove(list: List<String>, query: String): List<String> = list.filterNot { it == query }

    fun read(context: Context): List<String> =
        context.getSharedPreferences(PREFS, 0).getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun write(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, list.joinToString("\n")).apply()
    }
}
