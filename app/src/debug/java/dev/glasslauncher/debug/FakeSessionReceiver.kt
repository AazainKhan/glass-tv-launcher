package dev.glasslauncher.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log

/**
 * Debug only (P145): publishes a fake media session so Now Playing's seek scrubber and like heart can be checked on the
 * emulator without a music app. Drive it by explicit component (a manifest receiver gets no implicit broadcasts):
 *
 *   adb shell am broadcast -n dev.glasslauncher/.debug.FakeSessionReceiver --es cmd start --ez seek true --es like heart|custom|none \
 *       --ei pos 62000 --ei dur 214000 --ez playing true
 *   ... --es cmd stop
 *
 * The session logs onSeekTo / onSetRating / onCustomAction under the tag FakeSession (with the uptime), so a held key's
 * coalescing shows in logcat. Liking from Glass flips the state and republishes, as a real app does.
 */
class FakeSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: "start"
        if (cmd == "stop") FakeSession.stop() else FakeSession.start(
            context.applicationContext,
            seek = intent.getBooleanExtra("seek", true),
            like = intent.getStringExtra("like") ?: "none",
            pos = intent.getIntExtra("pos", 62_000).toLong(),
            dur = intent.getIntExtra("dur", 214_000).toLong(),
            playing = intent.getBooleanExtra("playing", true),
            art = intent.getStringExtra("art") ?: "none",
            artDelay = intent.getIntExtra("delay", 2_000).toLong(),
            track = intent.getStringExtra("track") ?: "Fake Song",
        )
    }
}

private object FakeSession {
    private const val TAG = "FakeSession"
    private var session: MediaSession? = null
    private var seek = true
    private var like = "none"
    private var liked = false
    private var pos = 0L
    private var dur = 0L
    private var playing = true
    private var art = "none"
    private var track = "Fake Song"

    fun start(context: Context, seek: Boolean, like: String, pos: Long, dur: Long, playing: Boolean, art: String = "none", artDelay: Long = 2_000, track: String = "Fake Song") {
        this.art = art; this.track = track; FakeArtProvider.delayMs = artDelay
        this.seek = seek; this.like = like; this.pos = pos; this.dur = dur; this.playing = playing; liked = false
        val s = session ?: MediaSession(context, "GlassFakeSession").also { session = it }
        s.setCallback(object : MediaSession.Callback() {
            override fun onSeekTo(position: Long) { Log.i(TAG, "onSeekTo $position t=${SystemClock.uptimeMillis()}"); this@FakeSession.pos = position; publish() }
            override fun onPlay() { this@FakeSession.playing = true; publish() }
            override fun onPause() { this@FakeSession.playing = false; publish() }
            override fun onSetRating(rating: Rating) {
                Log.i(TAG, "onSetRating style=${rating.ratingStyle} rated=${rating.isRated} heart=${rating.hasHeart()} up=${if (rating.ratingStyle == Rating.RATING_THUMB_UP_DOWN) rating.isThumbUp else null}")
                liked = rating.isRated && (if (rating.ratingStyle == Rating.RATING_HEART) rating.hasHeart() else rating.isThumbUp)
                publish()
            }
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                Log.i(TAG, "onCustomAction $action"); liked = !liked; publish()
            }
        })
        s.setRatingType(if (like == "heart") Rating.RATING_HEART else Rating.RATING_NONE)
        s.setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, track).putString(MediaMetadata.METADATA_KEY_ARTIST, "Fake Artist")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur).also { addArt(it) }.also { if (like == "heart") it.putRating(MediaMetadata.METADATA_KEY_USER_RATING, Rating.newHeartRating(liked)) }.build(),
        )
        s.isActive = true
        publish()
    }

    private fun publish() {
        val s = session ?: return
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (seek) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (like == "heart") actions = actions or PlaybackState.ACTION_SET_RATING
        val b = PlaybackState.Builder()
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, 1f, SystemClock.elapsedRealtime())
        if (like == "custom") b.addCustomAction(if (liked) "com.fake.action.unlike" else "com.fake.action.like", if (liked) "Remove from Liked Songs" else "Add to Liked Songs", android.R.drawable.btn_star)
        s.setPlaybackState(b.setActions(actions).build())
        if (like == "heart") s.setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, track).putString(MediaMetadata.METADATA_KEY_ARTIST, "Fake Artist")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur).also { addArt(it) }.putRating(MediaMetadata.METADATA_KEY_USER_RATING, Rating.newHeartRating(liked)).build(),
        )
    }

    /** art=uri: a cover by content URI only; art=logo: Spotify's way (a green logo as DISPLAY_ICON plus the cover URI); art=bitmap: the cover as a bitmap. */
    private fun addArt(b: MediaMetadata.Builder) {
        val uri = "content://dev.glasslauncher.debug.art/" + track.replace(' ', '-')
        val logo = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.rgb(30, 215, 96)) }
        when (art) {
            "uri" -> b.putString(MediaMetadata.METADATA_KEY_ART_URI, uri)
            "logo" -> { b.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, logo); b.putString(MediaMetadata.METADATA_KEY_ART_URI, uri) }
            "logo-only" -> b.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, logo)
        }
    }

    fun stop() { session?.release(); session = null }
}
