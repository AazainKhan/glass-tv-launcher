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
        // A device's page for the device tests: `--es open_device gamepad:Xbox` (or remote:Name) opens Settings on it.
        intent.getStringExtra("open_device")?.let { spec ->
            val kind = if (spec.startsWith("remote:")) dev.glasslauncher.home.PageKind.Remote else dev.glasslauncher.home.PageKind.Gamepad
            dev.glasslauncher.settings.SettingsEntry.page = dev.glasslauncher.settings.SettingsEntry.device(kind, spec.substringAfter(':'))
            context.startActivity(Intent(context, dev.glasslauncher.MainActivity::class.java).setAction(dev.glasslauncher.MainActivity.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            return
        }
        // A plain notice, to see whether the banner works at all (after a service restart, say): `--es notice "text"`; the result says
        // which path took it (Banner, Toast, Suppressed). `--ez crash_notice true` runs the crash-recovery path as if the last exit were a crash.
        intent.getStringExtra("notice")?.let { text ->
            resultData = RemoteNotice.show(context, text).name
            return
        }
        if (intent.getBooleanExtra("crash_notice", false)) {
            val now = System.currentTimeMillis()
            CrashRecovery.record(context, android.app.ApplicationExitInfo.REASON_CRASH, now, now)
            CrashRecovery.showPending(context)
            resultData = "crash notice run"
            return
        }
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
