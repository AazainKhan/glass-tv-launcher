package dev.glasslauncher.system

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Delivery point of Glass's always-on remote scan (P100): the scan is registered with a PendingIntent, so the system
 * wakes Glass for a matching advert even after the process was killed, with nothing running in between.
 */
class RemoteScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) { Log.w(RemotePairer.TAG, "scan (pending intent) failed: $error"); return }
        val results: List<ScanResult> = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) ?: return
        if (results.isEmpty()) return
        val pairer = RemotePairer.obtain(context)
        pairer.onResults(results)
    }
}
