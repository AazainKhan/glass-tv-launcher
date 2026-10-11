package dev.glasslauncher.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.glasslauncher.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Control Center's Sleep Timer (P83 M5, quick-wins spec 1): an exact alarm that, at zero, pauses the active media
 * session, waits a second and puts the stick to sleep (root: KEYCODE_SLEEP; otherwise the accessibility lock action).
 * The end time is stored, so the pill shows the same countdown after Control Center reopens.
 */
object SleepTimer {
    private const val PREFS = "sleepTimer"
    private const val KEY_END = "endAt"
    private const val ACTION = "dev.glasslauncher.action.SLEEP_TIMER"

    /** Off, then the choices the tile steps through on Select. */
    val CHOICES = listOf(0, 15, 30, 60, 90)

    /** The choice after [choice] (0 = Off): Select steps Off, 15, 30, 60, 90, Off. */
    fun next(choice: Int): Int = CHOICES[(CHOICES.indexOf(choice).coerceAtLeast(0) + 1) % CHOICES.size]

    /** The choice the running timer was started with (0 when off). */
    fun choice(context: Context): Int = if (endAt(context) > System.currentTimeMillis()) context.getSharedPreferences(PREFS, 0).getInt("choice", 0) else 0

    /** "Off", or the time left rounded up to a whole minute: "28 min". */
    fun label(remainingMs: Long): String =
        if (remainingMs <= 0) "Off" else "${(remainingMs + 59_999) / 60_000} min"

    fun endAt(context: Context): Long = context.getSharedPreferences(PREFS, 0).getLong(KEY_END, 0L)

    fun remainingMs(context: Context, now: Long = System.currentTimeMillis()): Long = (endAt(context) - now).coerceAtLeast(0)

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, Receiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Starts (or, with 0, cancels) the timer. */
    fun set(context: Context, minutes: Int, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (minutes <= 0) {
            alarms.cancel(pending(context)); prefs.edit().putLong(KEY_END, 0L).apply(); return
        }
        val at = now + minutes * 60_000L
        prefs.edit().putLong(KEY_END, at).putInt("choice", minutes).apply()
        // Exact and allowed while idle: it fires with Glass in the background or the screensaver up.
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
    }

    /** Pause what plays, then sleep. */
    fun fire(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
        context.getSharedPreferences(PREFS, 0).edit().putLong(KEY_END, 0L).apply()
        runCatching { context.app.nowPlaying.state.value?.takeIf { it.playing }?.playPause() }
        scope.launch {
            delay(1_000)
            if (Root.known || Root.available()) Root.action(context, "Sleep timer: sleep", "input keyevent KEYCODE_SLEEP")
            else RemoteKeysService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val pending = goAsync()
            val scope = CoroutineScope(Dispatchers.Default)
            fire(context.applicationContext, scope)
            scope.launch { delay(4_000); pending.finish() }
        }
    }
}
