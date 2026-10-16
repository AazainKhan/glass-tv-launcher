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
    /** An action an add-on offers; the user's selecting it is the consent. See [dev.glasslauncher.addons.AddonActionRef]. */
    data class AddonAction(val pkg: String, val service: String, val id: String) : RemoteAction {
        override val key = dev.glasslauncher.addons.AddonActionRef(pkg, service, id).key
    }

    companion object {
        fun parse(key: String): RemoteAction = when {
            key.startsWith("addon:") -> dev.glasslauncher.addons.AddonActionId.decode(key)?.let { AddonAction(it.pkg, it.service, it.id) } ?: Default
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
     * Defaults match what Fire TV does with each, except Settings, which opens Control Center over any app
     * (tvOS-style) instead of Fire's quick menu. Fire's key policy catches Settings by its kernel key code (and also
     * swallows BUTTON_16), so the module rewrites the remote's kernel keymap to send 185, a code the key
     * layout doesn't name; it arrives as KEYCODE_UNKNOWN with scan code 185. Home, Back, volume, mute,
     * power, Alexa and the TV button stay with the system.
     */
    val all = listOf(
        RemoteButton("app1", "Button 1", setOf("KEYCODE_BUTTON_9", "KEYCODE_APP_1"), RemoteAction.OpenApp("com.netflix.ninja")),
        RemoteButton("app2", "Button 2", setOf("KEYCODE_BUTTON_10", "KEYCODE_APP_2"), RemoteAction.OpenApp("com.amazon.firebat")),
        // Key 746 (bottom-left, printed Amazon Music) is BUTTON_11 and key 747 (bottom-right, printed Disney+) is
        // BUTTON_12 on Vendor_0171_Product_0427: the defaults follow what each button is printed with.
        RemoteButton("app3", "Button 3", setOf("KEYCODE_BUTTON_11", "KEYCODE_APP_3"), RemoteAction.OpenApp("com.amazon.bueller.music")),
        RemoteButton("app4", "Button 4", setOf("KEYCODE_BUTTON_12", "KEYCODE_APP_4"), RemoteAction.OpenApp("com.disney.disneyplus")),
        RemoteButton("recents", "Recent Apps Button", setOf("KEYCODE_BUTTON_13", "KEYCODE_RECENTS", "KEYCODE_APP_SWITCH"), RemoteAction.AppSwitcher),
        RemoteButton("settings", "Settings Button", emptySet(), RemoteAction.ControlCenter, scanCodes = setOf(SETTINGS_SCAN_CODE)),
    )

    /**
     * The buttons of a game controller that can be given a Glass action (P174). Unlike the remote's, they default to nothing of
     * Glass's own ([RemoteAction.Default]): the app in front keeps every one until the user maps it.
     */
    val pad = listOf(
        "a" to "A", "b" to "B", "x" to "X", "y" to "Y", "l1" to "Left Bumper", "r1" to "Right Bumper",
        "l2" to "Left Trigger", "r2" to "Right Trigger", "start" to "Start", "select" to "Select", "mode" to "Guide",
        "thumbl" to "Left Stick Press", "thumbr" to "Right Stick Press",
    ).map { (id, label) -> RemoteButton("pad:$id", label, setOf("KEYCODE_BUTTON_${id.uppercase()}"), RemoteAction.Default) }

    /** A remote or controller button by its id. */
    fun find(id: String): RemoteButton? = all.firstOrNull { it.id == id } ?: pad.firstOrNull { it.id == id }

    /**
     * [deviceName] is the input device that sent the key. The gamepad codes BUTTON_9..13 are what the Fire remote's app buttons are
     * remapped to, so from any other named device (a game controller on a Google TV box) they are not remote buttons (P155); an
     * unknown device (null) is accepted as before.
     */
    fun actionFor(keyName: String, config: Map<String, String>, scanCode: Int = 0, deviceName: String? = null): RemoteAction? {
        val remote = all.firstOrNull { keyName in it.names || (keyName == "KEYCODE_UNKNOWN" && scanCode in it.scanCodes) }
        if (remote != null && !(deviceName != null && keyName.startsWith("KEYCODE_BUTTON_") && !RemotePairPolicy.isAmazonRemoteName(deviceName))) return action(remote, config).takeIf { it != RemoteAction.Default }
        // A controller button does something only once the user has mapped it; otherwise the app in front gets it.
        val pad = pad.firstOrNull { keyName in it.names } ?: return null
        return action(pad, config).takeIf { it != RemoteAction.Default }
    }

    /**
     * Whether this is a Fire TV, where the app buttons have Fire's own apps as defaults. On any other device those apps are not
     * assumed: an unmapped app button keeps its system behaviour ([RemoteAction.Default]). A variable so tests can pick.
     */
    var fireDefaults: () -> Boolean = { HomeSetup.isFireTv }

    /** What an unmapped [button] does here. */
    fun defaultFor(button: RemoteButton): RemoteAction =
        if (button.default is RemoteAction.OpenApp && !fireDefaults()) RemoteAction.Default else button.default

    fun action(button: RemoteButton, config: Map<String, String>): RemoteAction =
        config[button.id]?.let(RemoteAction::parse)?.takeIf { it != RemoteAction.Default } ?: defaultFor(button)

    /** True once the remapped key layout is live (after the module is installed and the TV restarted). */
    fun takeoverActive(): Boolean = runCatching {
        java.io.File(REMOTE_LAYOUT).readText().contains("BUTTON_9")
    }.getOrDefault(false)

    /** The code glass-keymap gives the Settings button (KEY_F15; unnamed in the remote's key layout). */
    const val SETTINGS_SCAN_CODE = 185
    const val REMOTE_LAYOUT = "/system/usr/keylayout/Vendor_0171_Product_0427.kl"
    const val INSTALL_COMMAND = "scripts/remote-keys install"
}
