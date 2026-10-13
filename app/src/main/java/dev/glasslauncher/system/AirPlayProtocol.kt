package dev.glasslauncher.system

import android.content.ComponentName
import android.content.Intent

/**
 * Control protocol v2 spoken with the patched PhairPlay (tools/phairplay/glass-control.patch):
 * encoders for the commands Glass sends and a decoder for the broadcasts PhairPlay sends back.
 * Keep the keys and extras in step with the patch's ControlReceiver.
 */
object AirPlayProtocol {
    const val VERSION = 2
    const val PERMISSION = "com.phairplay.permission.CONTROL"

    const val ACTION_ENABLE = "com.phairplay.action.ENABLE"
    const val ACTION_DISABLE = "com.phairplay.action.DISABLE"
    const val ACTION_QUERY = "com.phairplay.action.QUERY"
    const val ACTION_SET = "com.phairplay.action.SET"
    const val ACTION_TRANSPORT = "com.phairplay.action.TRANSPORT"
    const val ACTION_STATE = "com.phairplay.action.STATE"
    const val ACTION_SESSION = "com.phairplay.action.SESSION"
    const val ACTION_PAIRING_CODE = "com.phairplay.action.PAIRING_CODE"
    const val ACTION_NOW_PLAYING = "com.phairplay.action.NOW_PLAYING"
    const val ACTION_ERROR = "com.phairplay.action.ERROR"

    /** Every event PhairPlay can send, for registering one receiver. */
    val EVENT_ACTIONS = listOf(ACTION_STATE, ACTION_SESSION, ACTION_PAIRING_CODE, ACTION_NOW_PLAYING, ACTION_ERROR)

    enum class ValueType { BOOL, INT, STRING }

    /** A PhairPlay setting Glass can write; [wire] is the `key` extra. */
    enum class SettingKey(val wire: String, val type: ValueType) {
        DisplayName("display_name", ValueType.STRING),
        AirPlayEnabled("airplay_enabled", ValueType.BOOL),
        MiracastEnabled("miracast_enabled", ValueType.BOOL),
        CastEnabled("cast_enabled", ValueType.BOOL),
        PinAuth("pin_auth", ValueType.BOOL),
        StartOnBoot("start_on_boot", ValueType.BOOL),
        DebugOverlay("debug_overlay", ValueType.BOOL),
        HighResolution("high_resolution", ValueType.BOOL),
        MirrorAudio("mirror_audio", ValueType.BOOL);

        companion object {
            fun fromWire(wire: String?): SettingKey? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** Fallback for upstream's AppSettings.DISPLAY_NAME_MAX_LENGTH; STATE reports the real one (`display_name_max`). */
    const val DISPLAY_NAME_MAX = 63
    const val DEFAULT_PAIRING_TTL_MS = 120_000L

    /** PhairPlay's settings as it reports them; defaults match its `AppSettings`. */
    data class Settings(
        val displayName: String = "",
        val airPlayEnabled: Boolean = true,
        val miracastEnabled: Boolean = true,
        val castEnabled: Boolean = true,
        val pinAuth: Boolean = false,
        val startOnBoot: Boolean = false,
        val debugOverlay: Boolean = false,
        val highResolution: Boolean = false,
        val mirrorAudio: Boolean = true,
        val displayNameMax: Int = DISPLAY_NAME_MAX,
    )

    enum class SessionKind {
        Mirror, Video, Audio, Photo, Unknown;

        companion object {
            fun fromWire(wire: String?) = entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } ?: Unknown
        }
    }

    enum class Transport(val wire: String) { Play("play"), Pause("pause"), Stop("stop") }

    enum class ErrorCode {
        UnknownKey, BadValue, BadCommand, Other;

        companion object {
            fun fromWire(wire: String?) = when (wire) {
                "unknown_key" -> UnknownKey
                "bad_value" -> BadValue
                "bad_command" -> BadCommand
                else -> Other
            }
        }
    }

    sealed interface Event {
        /**
         * [protocol] is 1 for an old PhairPlay (no `protocol` extra, no settings); [settings] is null then.
         */
        data class State(val running: Boolean, val sender: String?, val protocol: Int, val settings: Settings?) : Event {
            val supportsControl get() = protocol >= VERSION
        }

        data class Session(val started: Boolean, val sender: String?, val kind: SessionKind) : Event

        /** [code] is null when dismissed; [ttlMs] is how long a shown code may stay up without a close event. */
        data class PairingCode(val code: String?, val ttlMs: Long = DEFAULT_PAIRING_TTL_MS) : Event

        data class NowPlaying(
            val active: Boolean,
            val title: String?,
            val artist: String?,
            val album: String?,
            val hasArtwork: Boolean,
            val playing: Boolean,
            val positionMs: Long?,
            val durationMs: Long?,
        ) : Event

        data class Error(val code: ErrorCode, val action: String?, val key: String?, val command: String?) : Event
    }

    fun encodeToggle(target: ComponentName, on: Boolean): Intent = command(target, if (on) ACTION_ENABLE else ACTION_DISABLE)

    fun encodeQuery(target: ComponentName): Intent = command(target, ACTION_QUERY)

    fun encodeSet(target: ComponentName, key: SettingKey, value: Boolean): Intent =
        encodeSet(target, key, ValueType.BOOL) { it.putExtra("bool", value) }

    fun encodeSet(target: ComponentName, key: SettingKey, value: Int): Intent =
        encodeSet(target, key, ValueType.INT) { it.putExtra("int", value) }

    fun encodeSet(target: ComponentName, key: SettingKey, value: String, nameMax: Int = DISPLAY_NAME_MAX): Intent {
        require(key != SettingKey.DisplayName || value.trim().length <= nameMax) { "name over $nameMax characters" }
        return encodeSet(target, key, ValueType.STRING) { it.putExtra("string", value) }
    }

    fun encodeTransport(target: ComponentName, transport: Transport): Intent =
        command(target, ACTION_TRANSPORT).putExtra("command", transport.wire)

    private fun encodeSet(target: ComponentName, key: SettingKey, type: ValueType, put: (Intent) -> Unit): Intent {
        require(key.type == type) { "${key.wire} takes ${key.type}, not $type" }
        return command(target, ACTION_SET).putExtra("key", key.wire).also(put)
    }

    private fun command(target: ComponentName, action: String) =
        Intent(action).setComponent(target).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    /** The event in [intent], or null for anything that is not one of ours. */
    fun decode(intent: Intent): Event? = when (intent.action) {
        ACTION_STATE -> {
            val protocol = if (intent.hasExtra("protocol")) intent.getIntExtra("protocol", 1) else 1
            Event.State(
                running = intent.getBooleanExtra("running", false),
                sender = intent.getStringExtra("sender"),
                protocol = protocol,
                settings = if (protocol >= VERSION) decodeSettings(intent) else null,
            )
        }
        ACTION_SESSION -> Event.Session(
            started = intent.getStringExtra("state") == "started",
            sender = intent.getStringExtra("sender"),
            kind = SessionKind.fromWire(intent.getStringExtra("kind")),
        )
        ACTION_PAIRING_CODE -> Event.PairingCode(
            intent.getStringExtra("code")?.takeIf { intent.getBooleanExtra("shown", true) && it.isNotEmpty() },
            intent.getLongExtra("ttl_ms", DEFAULT_PAIRING_TTL_MS).takeIf { it > 0 } ?: DEFAULT_PAIRING_TTL_MS,
        )
        ACTION_NOW_PLAYING -> Event.NowPlaying(
            active = intent.getBooleanExtra("active", false),
            title = intent.getStringExtra("title"),
            artist = intent.getStringExtra("artist"),
            album = intent.getStringExtra("album"),
            hasArtwork = intent.getBooleanExtra("has_artwork", false),
            playing = intent.getBooleanExtra("playing", false),
            positionMs = intent.getLongExtra("position_ms", -1L).takeIf { it >= 0 },
            durationMs = intent.getLongExtra("duration_ms", -1L).takeIf { it >= 0 },
        )
        ACTION_ERROR -> Event.Error(
            code = ErrorCode.fromWire(intent.getStringExtra("code")),
            action = intent.getStringExtra("action"),
            key = intent.getStringExtra("key"),
            command = intent.getStringExtra("command"),
        )
        else -> null
    }

    private fun decodeSettings(i: Intent): Settings {
        val d = Settings()
        fun bool(k: SettingKey, default: Boolean) = if (i.hasExtra(k.wire)) i.getBooleanExtra(k.wire, default) else default
        return Settings(
            displayName = i.getStringExtra(SettingKey.DisplayName.wire) ?: d.displayName,
            airPlayEnabled = bool(SettingKey.AirPlayEnabled, d.airPlayEnabled),
            miracastEnabled = bool(SettingKey.MiracastEnabled, d.miracastEnabled),
            castEnabled = bool(SettingKey.CastEnabled, d.castEnabled),
            pinAuth = bool(SettingKey.PinAuth, d.pinAuth),
            startOnBoot = bool(SettingKey.StartOnBoot, d.startOnBoot),
            debugOverlay = bool(SettingKey.DebugOverlay, d.debugOverlay),
            highResolution = bool(SettingKey.HighResolution, d.highResolution),
            mirrorAudio = bool(SettingKey.MirrorAudio, d.mirrorAudio),
            displayNameMax = i.getIntExtra("display_name_max", d.displayNameMax).takeIf { it > 0 } ?: d.displayNameMax,
        )
    }
}
