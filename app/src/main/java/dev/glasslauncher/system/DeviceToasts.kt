package dev.glasslauncher.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import dev.glasslauncher.R

/** What a device is, for its glyph. */
enum class DeviceKind { Headphones, Speaker, Gamepad, Remote, Keyboard, Other }

/**
 * A banner when a device connects or disconnects (P185): Bluetooth audio, remotes, gamepads, keyboards. Bluetooth devices come from
 * the public ACL broadcasts; input devices that are not Bluetooth (USB gamepads and keyboards) from InputManager's listener. Both are
 * public API, so it works on any Android device, in every install mode. The banner pipeline (one at a time, a few seconds, Do Not
 * Disturb holds it back) is [RemoteNotice]. The rules below are pure and unit-tested.
 */
object DeviceToasts {
    fun kindOf(btMajor: Int?, btDevice: Int?, name: String?): DeviceKind = when {
        RemotePairPolicy.isAmazonRemoteName(name) -> DeviceKind.Remote
        btMajor == BluetoothClass.Device.Major.AUDIO_VIDEO -> when (btDevice) {
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET, BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> DeviceKind.Headphones
            else -> DeviceKind.Speaker
        }
        btMajor == BluetoothClass.Device.Major.PERIPHERAL -> when {
            btDevice == 0x0508 || btDevice == 0x0504 -> DeviceKind.Gamepad                     // gamepad, joystick
            (btDevice ?: 0) and 0x0040 != 0 -> DeviceKind.Keyboard                              // keyboard bit of the peripheral minor
            else -> DeviceKind.Remote
        }
        else -> DeviceKind.Other
    }

    fun kindOfInput(sources: Int, name: String?): DeviceKind = when {
        RemotePairPolicy.isAmazonRemoteName(name) -> DeviceKind.Remote
        ControllerList.isController(sources, false, name) -> DeviceKind.Gamepad
        (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD -> DeviceKind.Keyboard
        else -> DeviceKind.Other
    }

    fun icon(kind: DeviceKind): Int = when (kind) {
        DeviceKind.Headphones -> R.drawable.ic_headphones
        DeviceKind.Speaker -> R.drawable.ic_speaker
        DeviceKind.Gamepad -> R.drawable.ic_sports_esports
        DeviceKind.Remote -> R.drawable.ic_settings_remote
        DeviceKind.Keyboard, DeviceKind.Other -> R.drawable.ic_bluetooth
    }

    /** "Connected", "Connected · 80%" with a battery level, "Disconnected". */
    fun detail(connected: Boolean, battery: Int?): String =
        if (!connected) "Disconnected" else battery?.takeIf { it in 0..100 }?.let { "Connected · $it%" } ?: "Connected"

    /** Absurd or nameless entries (a virtual input device, a Bluetooth address with no name) are not worth a banner. */
    fun worthShowing(name: String?, virtual: Boolean = false): Boolean =
        !virtual && !name.isNullOrBlank() && !name.matches(Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}"))

    /**
     * A test or virtual input device (an injected keypad, a uinput pad) is not something the user plugged in. Virtual flag, no vendor and
     * product id at all, or the open test vendor id 0x1209 that scripts/key's keypad uses.
     */
    fun isTestDevice(virtual: Boolean, vendorId: Int, productId: Int): Boolean =
        virtual || (vendorId == 0 && productId == 0) || vendorId == TEST_VENDOR

    private const val TEST_VENDOR = 0x1209

    /** One device raises at most one banner per [WINDOW_MS], whatever the state: a stack that reports twice, or a link that flaps. */
    class Gate(private val windowMs: Long = WINDOW_MS) {
        private val last = HashMap<String, Long>()
        @Suppress("UNUSED_PARAMETER")
        fun allow(name: String, connected: Boolean, now: Long): Boolean {
            val prev = last[name]
            if (prev != null && now - prev < windowMs) return false
            last[name] = now
            return true
        }
    }

    const val WINDOW_MS = 3_000L

    private var started = false
    private val gate = Gate()

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val bt = IntentFilter().apply { addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED) }
        androidx.core.content.ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
                val name = runCatching { d.name }.getOrNull()
                if (!worthShowing(name)) return
                val connected = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                val kind = kindOf(runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull(), runCatching { d.bluetoothClass?.deviceClass }.getOrNull(), name)
                val battery = if (connected) runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int }.getOrNull() else null
                offer(app, name!!, connected, kind, battery)
            }
        }, bt, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

        val input = app.getSystemService(InputManager::class.java) ?: return
        val main = Handler(Looper.getMainLooper())
        input.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            // A removed device can no longer be asked its name: remember the names by id while they are here.
            private val names = HashMap<Int, Pair<String, DeviceKind>>()
            init { InputDevice.getDeviceIds().forEach { id -> InputDevice.getDevice(id)?.let { remember(it) } } }
            // Test and virtual devices are never remembered, so neither their arrival nor their removal raises a banner.
            private fun remember(d: InputDevice) {
                if (isTestDevice(d.isVirtual, d.vendorId, d.productId)) { names.remove(d.id); return }
                d.name?.let { names[d.id] = it to kindOfInput(d.sources, it) }
            }
            /** Bluetooth devices are announced by the ACL broadcast; a bonded device of the same name is not announced twice. */
            @SuppressLint("MissingPermission")
            private fun isBluetooth(name: String) = runCatching {
                app.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().any { runCatching { it.name }.getOrNull() == name }
            }.getOrDefault(false)
            override fun onInputDeviceAdded(id: Int) {
                val d = InputDevice.getDevice(id) ?: return
                remember(d)
                val name = d.name
                if (!worthShowing(name) || isBluetooth(name!!)) return
                val kind = names[id]?.second ?: return
                if (kind == DeviceKind.Other) return
                offer(app, name, true, kind, null)
            }
            override fun onInputDeviceRemoved(id: Int) {
                val (name, kind) = names.remove(id) ?: return
                if (!worthShowing(name) || kind == DeviceKind.Other || isBluetooth(name)) return
                offer(app, name, false, kind, null)
            }
            override fun onInputDeviceChanged(id: Int) { InputDevice.getDevice(id)?.let { remember(it) } }
        }, main)
    }

    /** For the device tests (DebugDumpReceiver, adb only): a notice as if the device had just come or gone. */
    fun fake(app: Context, name: String, connected: Boolean, kind: DeviceKind, battery: Int?) = offer(app, name, connected, kind, battery)

    private fun offer(app: Context, name: String, connected: Boolean, kind: DeviceKind, battery: Int?) {
        if (!gate.allow(name, connected, android.os.SystemClock.elapsedRealtime())) return
        RemoteNotice.showDevice(app, name, detail(connected, battery), icon(kind))
    }
}
