package dev.glasslauncher.system

import dev.glasslauncher.system.AirPlayProtocol.Event
import dev.glasslauncher.system.AirPlayProtocol.SessionKind
import dev.glasslauncher.system.AirPlayProtocol.Settings

/** Everything PhairPlay sends is untrusted text: this makes it safe to draw. */
object AirPlayText {
    private fun bad(c: Int): Boolean =
        Character.isISOControl(c) || c in 0x200B..0x200F || c in 0x202A..0x202E || c in 0x2060..0x206F ||
            c == 0xFEFF || c == 0x061C || c == 0x180E || c in 0xFFF9..0xFFFB || c in 0xE000..0xF8FF ||
            Character.getType(c) in setOf(Character.UNASSIGNED.toInt(), Character.SURROGATE.toInt(), Character.FORMAT.toInt())

    /** Printable text only (no controls, bidi marks, zero-width or private-use characters), spaces collapsed, at most [max] characters. */
    fun clean(raw: String?, max: Int): String? {
        if (raw == null) return null
        val out = StringBuilder()
        var space = false
        raw.codePoints().forEach { c ->
            when {
                Character.isWhitespace(c) || Character.isSpaceChar(c) -> space = out.isNotEmpty()
                bad(c) -> Unit
                else -> { if (space) out.append(' '); space = false; out.appendCodePoint(c) }
            }
        }
        val s = out.toString()
        val capped = if (s.codePointCount(0, s.length) > max) s.substring(0, s.offsetByCodePoints(0, max)).trimEnd() else s
        return capped.ifEmpty { null }
    }

    /** The pairing code is digits only, at most [CODE_MAX]; anything else is no code. */
    fun code(raw: String?): String? = raw?.filter { it in '0'..'9' }?.take(CODE_MAX)?.takeIf { it.isNotEmpty() }

    const val CODE_MAX = 8
    const val SENDER_MAX = 64
    const val TITLE_MAX = 120
    const val NAME_CAP = 63
}

enum class AirPlayAvailability {
    /** PhairPlay isn't installed (or doesn't share Glass's key). */
    Absent,
    /** Installed, but no STATE has come back yet. */
    Waiting,
    /** An old PhairPlay (protocol 1): only the plain on/off toggle. */
    Old,
    Ready,
}

/** What PhairPlay's NOW_PLAYING says, cleaned. Unknown position and duration stay null. */
data class AirPlayTrack(
    val title: String?,
    val artist: String?,
    val album: String?,
    val playing: Boolean,
    val positionMs: Long?,
    val durationMs: Long?,
)

/** The latest of everything PhairPlay has said, cleaned for drawing; the screens read this and keep no copy of their own. */
data class AirPlayUi(
    val availability: AirPlayAvailability = AirPlayAvailability.Absent,
    val running: Boolean = false,
    val sender: String? = null,
    val settings: Settings? = null,
    val pairingCode: String? = null,
    val sessionKind: SessionKind? = null,
    val track: AirPlayTrack? = null,
    /** Counts ERROR events, so a screen can tell the user once per new one. */
    val errors: Int = 0,
) {
    /** True while a Now Playing card makes sense: an audio session with something to show. */
    val audioPlaying get() = track != null
}

/**
 * Folds PhairPlay's events into one [AirPlayUi]. No Android in here: the clock is passed in, so expiry is testable.
 * Not thread-safe; the hub feeds it from the main thread.
 */
class AirPlayReducer(private val now: () -> Long) {
    private var installed = false
    private var state: Event.State? = null
    private var np: Event.NowPlaying? = null
    private var localPlaying: Boolean? = null
    private var errors = 0
    private val pairing = AirPlayCardLife()
    private val session = AirPlaySessionLife()

    fun setInstalled(value: Boolean): AirPlayUi {
        if (!value) { state = null; np = null; localPlaying = null; pairing.dismiss(); session.onState(Event.State(false, null, 1, null)) }
        installed = value
        return snapshot()
    }

    fun onEvent(e: Event): AirPlayUi {
        when (e) {
            is Event.State -> {
                state = e
                pairing.onState(e)
                session.onState(e)
                if (!e.running) clearTrack()
            }
            is Event.PairingCode -> pairing.onEvent(e, now())
            is Event.Session -> {
                session.onEvent(e)
                if (session.current == null) clearTrack()
            }
            is Event.NowPlaying -> {
                if (e.active) {
                    if (np?.title != e.title) localPlaying = null
                    np = e
                } else clearTrack()
            }
            is Event.Error -> errors++
        }
        return snapshot()
    }

    /** Back on the pairing card. */
    fun dismissPairing(): AirPlayUi { pairing.dismiss(); return snapshot() }

    /** Glass sent play or pause: PhairPlay can't say which it is, so the icon follows the last press until the track changes. */
    fun onTransport(play: Boolean): AirPlayUi { localPlaying = play; return snapshot() }

    /** Drops what has expired; call when the card's TTL runs out. */
    fun tick(): AirPlayUi = snapshot()

    /** Milliseconds until the pairing card expires, for a timer; null when none is up. */
    fun pairingRemaining(): Long? = pairing.remaining(now())

    private fun clearTrack() { np = null; localPlaying = null }

    fun snapshot(): AirPlayUi {
        val st = state
        val availability = when {
            !installed -> AirPlayAvailability.Absent
            st == null -> AirPlayAvailability.Waiting
            !st.supportsControl -> AirPlayAvailability.Old
            else -> AirPlayAvailability.Ready
        }
        val settings = st?.settings?.let { it.copy(displayName = AirPlayText.clean(it.displayName, AirPlayText.NAME_CAP).orEmpty(), displayNameMax = it.displayNameMax.coerceIn(1, AirPlayText.NAME_CAP)) }
        val track = np?.takeIf { installed && st?.running != false }?.let {
            AirPlayTrack(
                title = AirPlayText.clean(it.title, AirPlayText.TITLE_MAX),
                artist = AirPlayText.clean(it.artist, AirPlayText.TITLE_MAX),
                album = AirPlayText.clean(it.album, AirPlayText.TITLE_MAX),
                playing = localPlaying ?: it.playing,
                positionMs = it.positionMs,
                durationMs = it.durationMs?.takeIf { d -> d > 0 },
            )
        }
        return AirPlayUi(
            availability = availability,
            running = st?.running == true,
            sender = AirPlayText.clean(st?.sender, AirPlayText.SENDER_MAX),
            settings = settings,
            pairingCode = pairing.code.takeIf { pairing.visible(now()) }?.let(AirPlayText::code),
            sessionKind = session.current?.kind,
            track = track,
            errors = errors,
        )
    }
}

/** Rows of Settings › AirPlay, from what PhairPlay last said. Pure, so the page's contract is testable. */
data class AirPlayPageModel(
    val kind: Kind,
    val on: Boolean,
    val sender: String?,
    val name: String,
    val nameMax: Int,
    val requireCode: Boolean,
    val highQuality: Boolean,
    val mirrorAudio: Boolean,
) {
    enum class Kind {
        /** "AirPlay isn't set up", with the install hint. */
        NotSetUp,
        /** Installed, waiting for its first STATE. */
        Waiting,
        /** An old PhairPlay: the on/off row and the hint to update it. */
        ToggleOnly,
        Full,
    }

    companion object {
        const val HINT_NOT_SET_UP = "AirPlay isn't set up. Run scripts/phairplay install on your computer."
        const val HINT_OLD = "This PhairPlay is out of date. Run scripts/phairplay install on your computer to get the settings."

        fun of(ui: AirPlayUi): AirPlayPageModel {
            val s = ui.settings
            return AirPlayPageModel(
                kind = when (ui.availability) {
                    AirPlayAvailability.Absent -> Kind.NotSetUp
                    AirPlayAvailability.Waiting -> Kind.Waiting
                    AirPlayAvailability.Old -> Kind.ToggleOnly
                    AirPlayAvailability.Ready -> Kind.Full
                },
                on = ui.running,
                sender = ui.sender,
                name = s?.displayName.orEmpty(),
                nameMax = s?.displayNameMax ?: AirPlayProtocol.DISPLAY_NAME_MAX,
                requireCode = s?.pinAuth == true,
                highQuality = s?.highResolution == true,
                mirrorAudio = s?.mirrorAudio != false,
            )
        }

        /** The name row's value: PhairPlay falls back to the TV's own name when it is blank. */
        fun nameValue(name: String) = name.ifBlank { "TV Name" }
        fun qualityValue(high: Boolean) = if (high) "High 1440p" else "Standard 1080p"
    }
}
