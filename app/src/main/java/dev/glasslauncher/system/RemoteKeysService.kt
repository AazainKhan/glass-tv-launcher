package dev.glasslauncher.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.glasslauncher.MainActivity
import dev.glasslauncher.app

/**
 * Remote buttons (Settings › Remote Buttons): runs the action picked for each button in
 * RemoteButtons, e.g. an app button opens any app, Recent Apps opens Glass's app switcher. Every other
 * key passes straight through. Only key events are read, never screen content.
 */
class RemoteKeysService : AccessibilityService() {

    override fun onServiceConnected() {
        // Some builds drop the XML flag; asking again at runtime turns key filtering on.
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        Log.i(TAG, "connected, flags=${serviceInfo.flags} caps=${serviceInfo.capabilities}")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val name = KeyEvent.keyCodeToString(event.keyCode)
        if (dev.glasslauncher.BuildConfig.DEBUG && event.action == KeyEvent.ACTION_DOWN) Log.i(TAG, "$name scan=${event.scanCode}")
        val action = RemoteButtons.actionFor(name, app.config.config.value.remoteButtons) ?: return false
        // Act on release so a held button doesn't repeat, and swallow both halves of the press.
        if (event.action == KeyEvent.ACTION_UP) run(action)
        return true
    }

    private fun run(action: RemoteAction) {
        when (action) {
            is RemoteAction.OpenApp -> {
                val pm = packageManager
                // Not installed (e.g. Amazon Music after a debloat): its Appstore page, as Fire TV does.
                val intent = pm.getLeanbackLaunchIntentForPackage(action.pkg) ?: pm.getLaunchIntentForPackage(action.pkg)
                    ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("amzn://apps/android?p=${action.pkg}"))
                runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            RemoteAction.ControlCenter -> home(MainActivity.ACTION_CONTROL_CENTER)
            RemoteAction.AppSwitcher -> home(MainActivity.ACTION_APP_SWITCHER)
            RemoteAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            RemoteAction.Nothing, RemoteAction.Default -> Unit
        }
    }

    private fun home(action: String) {
        startActivity(
            Intent(this, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit
    override fun onInterrupt() = Unit

    companion object {
        const val TAG = "GlassRemote"
    }
}
