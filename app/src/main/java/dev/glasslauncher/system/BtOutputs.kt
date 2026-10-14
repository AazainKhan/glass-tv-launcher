package dev.glasslauncher.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.glasslauncher.sonos.BtAction
import dev.glasslauncher.sonos.BtOutput
import dev.glasslauncher.sonos.BtRoles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The paired Bluetooth audio devices for the output picker; pairing itself stays in the system's Bluetooth settings. */
object BtOutputs {
    /** The picker's reader; screenshot tests replace it, nothing else does. */
    @Volatile var reader: (Context) -> List<BtOutput> = ::readSystem

    fun read(context: Context): List<BtOutput> = reader(context)

    /** Paired audio output devices, with which one is the system's A2DP sink right now. Empty when Bluetooth is off or unreadable. */
    @SuppressLint("MissingPermission")
    private fun readSystem(context: Context): List<BtOutput> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { runCatching { it.isEnabled }.getOrDefault(false) } ?: return emptyList()
        val live = context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }.map { it.address }.toSet()
        return runCatching { adapter.bondedDevices }.getOrNull().orEmpty().mapNotNull { d ->
            val cls = runCatching { d.bluetoothClass?.deviceClass }.getOrNull() ?: return@mapNotNull null
            val role = BtRoles.of(cls) ?: return@mapNotNull null
            BtOutput(d.address, runCatching { d.name }.getOrNull().orEmpty(), role, d.address in live, battery(d))
        }
    }

    private fun battery(d: BluetoothDevice): Int? =
        runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int }.getOrNull()?.takeIf { it in 0..100 }
}

/**
 * Switches the A2DP sink with the system's own Bluetooth stack (no capture: the system routes media to the connected sink). The
 * connect and disconnect calls are hidden and need BLUETOOTH_PRIVILEGED, which Glass has as a system app (Glass Pro / Glass OS);
 * a plain APK gets [canRoute] false and the picker opens the Bluetooth settings instead.
 */
object BtRoute {
    private const val PERMISSION = "android.permission.BLUETOOTH_PRIVILEGED"
    private const val PROXY_TIMEOUT_MS = 5_000L

    fun canRoute(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when the stack accepted the call; the connection finishes a moment later and the picker re-reads the sink. */
    @SuppressLint("MissingPermission")
    suspend fun run(context: Context, action: BtAction): Boolean {
        if (action == BtAction.OpenSettings) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val proxy = a2dp(context, adapter) ?: return false
        return try {
            when (action) {
                is BtAction.Connect -> {
                    for (old in action.replacing) call(proxy, "disconnect", adapter.getRemoteDevice(old.address))
                    call(proxy, "connect", adapter.getRemoteDevice(action.device.address))
                }
                is BtAction.Disconnect -> call(proxy, "disconnect", adapter.getRemoteDevice(action.device.address))
                BtAction.OpenSettings -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
        }
    }

    private fun call(proxy: BluetoothProfile, name: String, device: BluetoothDevice): Boolean =
        runCatching { BluetoothA2dp::class.java.getMethod(name, BluetoothDevice::class.java).invoke(proxy, device) as? Boolean }.getOrNull() ?: false

    private suspend fun a2dp(context: Context, adapter: BluetoothAdapter): BluetoothProfile? = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
        suspendCancellableCoroutine<BluetoothProfile?> { cont ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) { if (cont.isActive) cont.resume(p) }
                override fun onServiceDisconnected(profile: Int) = Unit
            }
            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP) && cont.isActive) cont.resume(null)
        }
    }
}
