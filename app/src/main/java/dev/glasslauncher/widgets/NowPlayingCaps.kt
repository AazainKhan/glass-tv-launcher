package dev.glasslauncher.widgets

/**
 * What a media session lets Now Playing do beyond play/pause/skip: seek and like. Everything a session reports is
 * untrusted (any app can publish anything), so this file is pure: plain numbers and strings in, clamped and
 * cleaned values out. The framework objects are mapped to these inputs in one place (NowPlayingSource.publish).
 */
sealed interface Like {
    /** The liked state to draw; null for [None]. */
    val liked: Boolean?

    /** The session offers nothing we can use: the button is hidden. */
    data object None : Like { override val liked: Boolean? = null }

    /** setRating(heart). */
    data class Heart(override val liked: Boolean) : Like

    /** setRating(thumb up/down): liking is a thumb up, un-liking clears the rating. */
    data class ThumbUpDown(override val liked: Boolean) : Like

    /** A custom action the app exposes for liking (Spotify, YouTube Music, SoundCloud); [id] is sent back verbatim. */
    data class Custom(val id: String, override val liked: Boolean) : Like
}

object NowPlayingCaps {
    // android.media.session.PlaybackState / android.media.Rating values, restated so this runs in plain JVM tests.
    const val ACTION_SET_RATING = 128L
    const val ACTION_SEEK_TO = 256L
    const val RATING_NONE = 0
    const val RATING_HEART = 1
    const val RATING_THUMB_UP_DOWN = 2

    /** Longest duration believed: 48 h. Anything past that (or <= 0, or NaN-ish) is a live stream or a broken session. */
    const val MAX_DURATION_MS = 48L * 3_600_000

    /** The scrubber exists only when the session says it can seek AND the item has a real length. */
    fun canSeek(actions: Long, durationMs: Long) = actions and ACTION_SEEK_TO != 0L && durationMs > 0

    fun duration(raw: Long): Long = if (raw in 1..MAX_DURATION_MS) raw else 0L

    fun position(raw: Long, durationMs: Long): Long =
        if (durationMs > 0) raw.coerceIn(0, durationMs) else raw.coerceAtLeast(0)

    /** A playback speed worth extrapolating with; paused sessions report 0, which is irrelevant while not playing. */
    fun speed(raw: Float): Float = if (raw.isNaN() || raw <= 0f || raw > 8f) 1f else raw

    /** [at] is on the elapsedRealtime clock, so a time in the future is a lie; 0 means unset. */
    fun updatedAt(raw: Long, now: Long): Long = if (raw <= 0) now else minOf(raw, now)

    /** Text for a title or artist: control characters out, trimmed, at most [max] chars; null when nothing is left. */
    fun text(raw: CharSequence?, max: Int = 200): String? {
        val s = raw?.toString() ?: return null
        val clean = buildString(minOf(s.length, max + 1)) { for (ch in s) { append(if (Character.isISOControl(ch)) ' ' else ch); if (length > max) break } }
        return clean.trim().take(max).takeIf { it.isNotEmpty() }
    }

    /**
     * Which like control to offer. Order: a heart rating, then thumbs up/down, then a custom action; none if the
     * session has neither. A rating needs the session's SET_RATING action as well as its rating type.
     *
     * @param userLiked the metadata's user rating: true liked (heart set, thumb up), false rated-not-liked or unrated, null unknown.
     * @param custom the session's custom actions as (action id, display name) pairs.
     */
    fun like(actions: Long, ratingType: Int, userLiked: Boolean?, custom: List<Pair<String, CharSequence?>>): Like {
        if (actions and ACTION_SET_RATING != 0L) {
            when (ratingType) {
                RATING_HEART -> return Like.Heart(userLiked == true)
                RATING_THUMB_UP_DOWN -> return Like.ThumbUpDown(userLiked == true)
            }
        }
        for ((id, name) in custom) {
            val tokens = tokens(id) + tokens(name?.toString())
            // The id goes back to the app verbatim, so an oversized or control-laden one is skipped, never "cleaned".
            if (isLikeAction(tokens) && id.length <= 200 && id.none { Character.isISOControl(it) }) return Like.Custom(id, likedByLabel(tokens))
        }
        return Like.None
    }

    private val positive = setOf("like", "liked", "favorite", "favorited", "favourite", "heart", "love", "loved", "thumbsup", "thumbup")
    // "like" is inside "dislike" and "unlike": tokens are whole words, so only an exact "dislike" is excluded.
    private val negative = setOf("dislike", "disliked", "thumbsdown", "thumbdown", "hate", "ban")
    private val undo = setOf("unlike", "unfavorite", "unfavourite", "unheart", "unlove", "unsave")

    private fun isLikeAction(t: Set<String>) = t.none { it in negative } && t.any { it in positive || it in undo }

    /**
     * The label tells which way the action toggles: "Unlike" and "Remove from Liked Songs" mean it is liked now;
     * "Like" and "Add to Liked Songs" mean it is not; a bare "Liked" is a state label, so liked.
     */
    private fun likedByLabel(t: Set<String>): Boolean = when {
        t.any { it in undo } || "remove" in t -> true
        "add" in t || "save" in t -> false
        else -> "liked" in t || "favorited" in t || "loved" in t
    }

    /** Lower-case words of an id or label, split on punctuation and camelCase, plus the glued form ("thumbs up" -> thumbsup). */
    internal fun tokens(raw: String?): Set<String> {
        if (raw == null) return emptySet()
        val s = raw.take(200)
        val spaced = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase(java.util.Locale.ROOT)
        val words = spaced.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val out = HashSet<String>()
        for (w in words) { out += w; if (w.length > 3 && w.endsWith("s") && w != "thumbs") out += w.dropLast(1) }
        for (i in 0 until words.size - 1) out += words[i] + words[i + 1]
        return out
    }
}

/**
 * Turns D-pad Left/Right presses into session seeks without flooding the app. Targets accumulate from the last
 * target (not the stale position the session still reports), are clamped to 0..duration, the first press goes
 * out at once, later ones at most one per [minGapMs], and the newest target is never lost ([flush] sends it).
 * A track change drops everything pending.
 */
class SeekCoalescer(private val minGapMs: Long = 200, private val settleMs: Long = 1_500) {
    private var owner: String? = null
    private var duration = 0L
    private var target = 0L
    private var hasTarget = false
    private var dirty = false
    private var lastSent = -1_000_000L
    private var lastPress = -1_000_000L

    /** Where the bar should be drawn while the user is still seeking (the session catches up later); null when idle. */
    fun shown(now: Long): Long? = target.takeIf { hasTarget && now - lastPress <= settleMs }

    /** A press: returns the position to seek to now, or null if it waits for [flush]. */
    fun press(key: String, positionNow: Long, durationMs: Long, deltaMs: Long, now: Long): Long? {
        if (durationMs <= 0) return null
        if (key != owner) { owner = key; hasTarget = false; dirty = false }
        if (!hasTarget || now - lastPress > settleMs) { target = positionNow; hasTarget = true }
        duration = durationMs
        target = (target + deltaMs).coerceIn(0, durationMs)
        lastPress = now
        dirty = true
        return flush(key, now)
    }

    /** The pending target if one waits and the gap since the last send has passed. */
    fun flush(key: String, now: Long): Long? {
        if (!dirty || key != owner || now - lastSent < minGapMs) return null
        dirty = false
        lastSent = now
        return target
    }

    /** How long until [flush] may send; null when nothing is pending. */
    fun delayMs(now: Long): Long? = if (dirty) (lastSent + minGapMs - now).coerceAtLeast(0) else null

    fun reset() { owner = null; hasTarget = false; dirty = false }
}
