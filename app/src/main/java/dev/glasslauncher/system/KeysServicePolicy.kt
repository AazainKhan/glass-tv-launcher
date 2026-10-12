package dev.glasslauncher.system

/**
 * How Glass keeps its remote-keys accessibility service (remote app buttons, Recent Apps, instant Back) on, per install
 * way (P93). Pure, so the decision is unit-tested; [KeysService] gathers the inputs and carries the action out.
 *
 *  - APK, no root: Glass can't enable a service itself. It asks once, with a Home card that opens Fire's Accessibility
 *    settings, and never calls su.
 *  - Glass Pro (rooted) or Glass OS: it keeps the service enabled while the setting is on.
 *  - Any install with WRITE_SECURE_SETTINGS granted (adb): the same, through the Settings provider (no su).
 */
enum class WriteMode { SecureSettings, Root, None }

object KeysServicePolicy {
    /** Prefers the Settings provider (no su) when WRITE_SECURE_SETTINGS is granted; root otherwise; else none. */
    fun mode(hasSecureWrite: Boolean, hasRoot: Boolean): WriteMode = when {
        hasSecureWrite -> WriteMode.SecureSettings
        hasRoot -> WriteMode.Root
        else -> WriteMode.None
    }

    /**
     * A real boot, not a process restart: the system's boot counter changed since the last check. A missing counter
     * (-1) is never a boot, so a killed Glass process can't undo a manual off.
     */
    fun isBoot(bootCount: Int, lastBootCount: Int?): Boolean = bootCount >= 0 && lastBootCount != null && bootCount != lastBootCount

    sealed interface Action {
        data object Nothing : Action
        /** The setting is on and the service is: remember this version as the last one it was seen on. */
        data object RecordOn : Action
        data object Enable : Action
        /** Found off without an update since it was last seen on: the user turned it off; follow them. */
        data object UserTurnedOff : Action
        data object ShowCard : Action
    }

    class State(
        val settingOn: Boolean,
        val serviceOn: Boolean,
        val mode: WriteMode,
        val versionCode: Long,
        /** The Glass versionCode the service was last seen on under, or null (never seen, or the setting was re-enabled). */
        val lastOnVersion: Long?,
        /** The first check of this process: Glass has just started (boot, or a restart after an update). */
        val atBoot: Boolean,
        /** The Home card has already been shown since the setting was last turned on. */
        val cardShown: Boolean,
    )

    fun decide(s: State): Action = when {
        !s.settingOn -> Action.Nothing                                   // the user said off: don't fight it
        s.serviceOn -> Action.RecordOn
        s.mode == WriteMode.None -> if (s.cardShown) Action.Nothing else Action.ShowCard
        // Off, and Glass may turn it on: after an update or at boot (or never seen on) that is the system dropping it;
        // otherwise (same version, seen on before) someone switched it off.
        s.atBoot || s.lastOnVersion == null || s.lastOnVersion != s.versionCode -> Action.Enable
        else -> Action.UserTurnedOff
    }
}
