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
        resultData = RemoteKeysService.instance?.controlCenter?.dumpXml().orEmpty()
    }
}
