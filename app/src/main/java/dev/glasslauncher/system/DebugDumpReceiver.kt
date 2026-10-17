package dev.glasslauncher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * For the device tests (`adb shell am broadcast -n dev.glasslauncher/.system.DebugDumpReceiver`): returns
 * the Control Center overlay's tree as the broadcast's result data, since uiautomator can't see
 * accessibility overlays. Guarded by android.permission.DUMP, so only adb (shell) or root can ask.
 */
class DebugDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A device notice for the device tests: `--es device "Xbox Wireless Controller" --es kind gamepad --ez connected true --ei battery 80`.
        intent.getStringExtra("device")?.let { name ->
            val kind = DeviceKind.entries.firstOrNull { it.name.equals(intent.getStringExtra("kind"), true) } ?: DeviceKind.Other
            DeviceToasts.fake(context, name, intent.getBooleanExtra("connected", true), kind, intent.getIntExtra("battery", -1).takeIf { it >= 0 })
            return
        }
        // With --ez home true, Glass's own window too while it's the one in front (an app in front, or a
        // Glass dialog, returns nothing and the test falls back to uiautomator): ~50 ms instead of 1-2 s.
        resultData = RemoteKeysService.instance?.controlCenter?.dumpXml()
            ?: if (intent.getBooleanExtra("home", false)) dev.glasslauncher.MainActivity.dumpXml() else null
            ?: ""
    }
}
