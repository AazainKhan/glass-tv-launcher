package dev.glasslauncher.search

import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.featured.FeaturedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the search screen shows for the text typed so far (P161). Apps and the titles Glass already knows answer at once, on every key;
 * Cinemeta is asked [debounceMs] after the last key (a title typed letter by letter is one request), and its movies and shows join the list
 * when they arrive. A newer query cancels the older one, so a slow answer never replaces a fresh list. With no text there are no results
 * ([empty] says so: the screen shows its suggestions then).
 */
class SearchModel(
    private val scope: CoroutineScope,
    private val apps: () -> List<AppEntry>,
    private val titles: suspend () -> List<FeaturedItem>,
    private val remote: suspend (String) -> List<FeaturedItem>,
    private val debounceMs: Long = 300,
) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _results = MutableStateFlow<List<SearchHit>>(emptyList())
    val results: StateFlow<List<SearchHit>> = _results
    /** True while Cinemeta's answer for the current text is on its way. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private var job: Job? = null

    val empty: Boolean get() = _query.value.isBlank()

    fun setQuery(text: String) {
        _query.value = text
        job?.cancel()
        if (text.isBlank()) { _results.value = emptyList(); _busy.value = false; return }
        job = scope.launch {
            val local = SearchIndex.apps(text, apps())
            val known = runCatching { SearchIndex.titles(text, titles()) }.getOrDefault(emptyList())
            _results.value = SearchIndex.merge(local, known, emptyList())
            _busy.value = true
            try {
                delay(debounceMs)
                val found = runCatching { remote(text) }.getOrDefault(emptyList())
                _results.value = SearchIndex.merge(local, known, SearchIndex.remote(text, found))
            } finally { _busy.value = false }
        }
    }
}
