package dev.glasslauncher.search

import dev.glasslauncher.featured.normTitle

/**
 * The forgiving half of search matching (P171). [SearchIndex.score] tries the exact tiers first (starts with, a word starts with it, contains);
 * what is left is matched here: letters typed in order but not together ("mtrx" for The Matrix, in the way of fzf: letters at the start of a
 * word and runs of neighbours score higher), and a typo or a swapped pair of letters ("matirx", "intersteller"). Both give less than any exact
 * match, so a real hit always ranks above a guess. Diacritics, case and punctuation are folded by [normTitle]. Pure.
 */
object FuzzyMatch {
    /** Subsequence score, 0 for none, else 10..27: every typed letter found in order, close together, rewarding word starts and runs. */
    fun subsequence(query: String, title: String): Int {
        val q = normTitle(query).filter { !it.isWhitespace() }
        val t = normTitle(title)
        if (q.length < 3 || t.isEmpty()) return 0
        var at = -1; var first = -1; var bonus = 0; var prev = -2
        for (c in q) {
            val i = t.indexOf(c, at + 1)
            if (i < 0) return 0
            if (first < 0) first = i
            if (i == prev + 1) bonus += 3
            if (i == 0 || t[i - 1] == ' ') bonus += 4
            prev = i; at = i
        }
        if (at - first + 1 > q.length * 2 + 2) return 0
        return 10 + bonus.coerceAtMost(17)
    }

    /** Edit distance with a swap of two neighbours counting one (optimal string alignment). */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** Typo score, 0 for none, else 17 or 21: the query is within 1 edit (3 to 4 letters) or 2 (5 and more) of a word of the title or of its start. */
    fun typo(query: String, title: String): Int {
        val q = normTitle(query)
        val t = normTitle(title)
        if (q.length < 3 || t.isEmpty()) return 0
        val allowed = if (q.length <= 4) 1 else 2
        val candidates = if (' ' in q) listOf(t.take(q.length), t.take(q.length - 1), t.take(q.length + 1))
        else t.split(' ').flatMap { w -> listOf(w, w.take(q.length), w.take(q.length - 1), w.take(q.length + 1)) }
        val best = candidates.filter { it.length >= 2 }.minOfOrNull { distance(q, it) } ?: return 0
        return if (best in 1..allowed) 25 - 4 * best else 0
    }
}
