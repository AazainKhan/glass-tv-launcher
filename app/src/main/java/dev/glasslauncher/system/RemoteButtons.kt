package dev.glasslauncher.system

/** What a remote button can do. Stored in LauncherConfig.remoteButtons as [key]. */
sealed interface RemoteAction {
    val key: String

    /** The button's own Fire TV behaviour (RemoteButton.default). */
    data object Default : RemoteAction { override val key = "default" }
    data object Nothing : RemoteAction { override val key = "none" }
    data object Home : RemoteAction { override val key = "glass:home" }
    data object ControlCenter : RemoteAction { override val key = "glass:control_center" }
    data object AppSwitcher : RemoteAction { override val key = "glass:switcher" }
    data object TvSettings : RemoteAction { override val key = "glass:tv_settings" }
    data class OpenApp(val pkg: String) : RemoteAction { override val key = "app:$pkg" }

    companion object {
        fun parse(key: String): RemoteAction = when {
            key.startsWith("app:") -> OpenApp(key.removePrefix("app:"))
            key == Nothing.key -> Nothing
            key == Home.key -> Home
            key == ControlCenter.key -> ControlCenter
            key == AppSwitcher.key -> AppSwitcher
            key == TvSettings.key -> TvSettings
            else -> Default
        }
    }
}

/**
 * A remappable button on the Fire TV remote. [names] are the key names it can arrive as; [scanCodes]
 * match keys the key layout doesn't name (they arrive as KEYCODE_UNKNOWN).
 */
data class RemoteButton(
    val id: String,
    val label: String,
    val names: Set<String>,
    val default: RemoteAction,
    val scanCodes: Set<Int> = emptySet(),
)

object RemoteButtons {
    /**
     * Fire OS handles the app and Recent Apps buttons inside the system (KeyMapManager), so no app ever
     * sees them. The optional root key layout (tools/magisk/glass-remote-keys, `scripts/remote-keys
     * install`) gives them spare gamepad codes instead, BUTTON_9..13, which reach RemoteKeysService.
     * Defaults match what Fire TV does with each, except Settings, which opens the full TV Settings page
     * instead of Fire's quick menu. Fire's key policy catches Settings by its kernel key code (and also
     * swallows BUTTON_16), so the module rewrites the remote's kernel keymap to send 185, a code the key
     * layout doesn't name; it arrives as KEYCODE_UNKNOWN with scan code 185. Home, Back, volume, mute,
     * power, Alexa and the TV button stay with the system.
     */
    val all = listOf(
        RemoteButton("app1", "Button 1", setOf("KEYCODE_BUTTON_9", "KEYCODE_APP_1"), RemoteAction.OpenApp("com.netflix.ninja")),
        RemoteButton("app2", "Button 2", setOf("KEYCODE_BUTTON_10", "KEYCODE_APP_2"), RemoteAction.OpenApp("com.amazon.firebat")),
        RemoteButton("app3", "Button 3", setOf("KEYCODE_BUTTON_11", "KEYCODE_APP_3"), RemoteAction.OpenApp("com.disney.disneyplus")),
        RemoteButton("app4", "Button 4", setOf("KEYCODE_BUTTON_12", "KEYCODE_APP_4"), RemoteAction.OpenApp("com.amazon.bueller.music")),
        RemoteButton("recents", "Recent Apps Button", setOf("KEYCODE_BUTTON_13", "KEYCODE_RECENTS", "KEYCODE_APP_SWITCH"), RemoteAction.AppSwitcher),
        RemoteButton("settings", "Settings Button", emptySet(), RemoteAction.TvSettings, scanCodes = setOf(SETTINGS_SCAN_CODE)),
    )

    fun actionFor(keyName: String, config: Map<String, String>, scanCode: Int = 0): RemoteAction? {
        val button = all.firstOrNull { keyName in it.names || (keyName == "KEYCODE_UNKNOWN" && scanCode in it.scanCodes) } ?: return null
        return action(button, config)
    }

    fun action(button: RemoteButton, config: Map<String, String>): RemoteAction =
        config[button.id]?.let(RemoteAction::parse)?.takeIf { it != RemoteAction.Default } ?: button.default

    /** True once the remapped key layout is live (after the module is installed and the TV restarted). */
    fun takeoverActive(): Boolean = runCatching {
        java.io.File(REMOTE_LAYOUT).readText().contains("BUTTON_9")
    }.getOrDefault(false)

    /** The code glass-keymap gives the Settings button (KEY_F15; unnamed in the remote's key layout). */
    const val SETTINGS_SCAN_CODE = 185
    const val REMOTE_LAYOUT = "/system/usr/keylayout/Vendor_0171_Product_0427.kl"
    const val INSTALL_COMMAND = "scripts/remote-keys install"
}
