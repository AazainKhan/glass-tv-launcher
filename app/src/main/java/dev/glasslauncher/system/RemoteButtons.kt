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
    data class OpenApp(val pkg: String) : RemoteAction { override val key = "app:$pkg" }

    companion object {
        fun parse(key: String): RemoteAction = when {
            key.startsWith("app:") -> OpenApp(key.removePrefix("app:"))
            key == Nothing.key -> Nothing
            key == Home.key -> Home
            key == ControlCenter.key -> ControlCenter
            key == AppSwitcher.key -> AppSwitcher
            else -> Default
        }
    }
}

/** A remappable button on the Fire TV remote. [names] are the key names it can arrive as. */
data class RemoteButton(val id: String, val label: String, val names: Set<String>, val default: RemoteAction)

object RemoteButtons {
    /**
     * Fire OS handles the app and Recent Apps buttons inside the system (KeyMapManager), so no app ever
     * sees them. The optional root key layout (tools/magisk/glass-remote-keys, `scripts/remote-keys
     * install`) gives them spare gamepad codes instead, BUTTON_9..13, which reach RemoteKeysService.
     * Defaults match what Fire TV does with each. Settings can't be freed this way: Fire's key policy
     * catches it by scan code. Home, Back, volume, mute, power, Alexa and the TV button stay with the system.
     */
    val all = listOf(
        RemoteButton("app1", "Netflix Button", setOf("KEYCODE_BUTTON_9", "KEYCODE_APP_1"), RemoteAction.OpenApp("com.netflix.ninja")),
        RemoteButton("app2", "Prime Video Button", setOf("KEYCODE_BUTTON_10", "KEYCODE_APP_2"), RemoteAction.OpenApp("com.amazon.firebat")),
        RemoteButton("app3", "Disney+ Button", setOf("KEYCODE_BUTTON_11", "KEYCODE_APP_3"), RemoteAction.OpenApp("com.disney.disneyplus")),
        RemoteButton("app4", "Amazon Music Button", setOf("KEYCODE_BUTTON_12", "KEYCODE_APP_4"), RemoteAction.OpenApp("com.amazon.bueller.music")),
        RemoteButton("recents", "Recent Apps Button", setOf("KEYCODE_BUTTON_13", "KEYCODE_RECENTS", "KEYCODE_APP_SWITCH"), RemoteAction.AppSwitcher),
    )

    fun actionFor(keyName: String, config: Map<String, String>): RemoteAction? {
        val button = all.firstOrNull { keyName in it.names } ?: return null
        return action(button, config)
    }

    fun action(button: RemoteButton, config: Map<String, String>): RemoteAction =
        config[button.id]?.let(RemoteAction::parse)?.takeIf { it != RemoteAction.Default } ?: button.default

    /** True once the remapped key layout is live (after the module is installed and the TV restarted). */
    fun takeoverActive(): Boolean = runCatching {
        java.io.File(REMOTE_LAYOUT).readText().contains("BUTTON_9")
    }.getOrDefault(false)

    const val REMOTE_LAYOUT = "/system/usr/keylayout/Vendor_0171_Product_0427.kl"
    const val INSTALL_COMMAND = "scripts/remote-keys install"
}
