package dev.glasslauncher.system

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Root access (Magisk's su), for the Root section of Settings and the Control Center root buttons.
 * Everything here is opt-in, reversible, and logged to `files/root-actions.log` (Settings › Root ›
 * Log). On devices without su all of it stays hidden.
 */
object Root {
    data class Result(val code: Int, val out: String) { val ok get() = code == 0 }

    @Volatile private var granted: Boolean? = null

    /** Whether su exists and grants root to Glass. Cached after the first check (~100 ms). */
    suspend fun available(): Boolean {
        granted?.let { return it }
        val exists = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su").any { File(it).exists() }
        val ok = exists && withContext(Dispatchers.IO) { withTimeoutOrNull(4_000) { exec("id").out.contains("uid=0") } } == true
        granted = ok
        return ok
    }

    /** Last known result of [available] without checking (false until checked). */
    val known: Boolean get() = granted == true

    /** Runs [script] as root; returns its exit code and combined output. */
    suspend fun run(script: String): Result = withContext(Dispatchers.IO) { exec(script) }

    private fun exec(script: String): Result = runCatching {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroy(); Result(124, out) } else Result(p.exitValue(), out.trim())
    }.getOrElse { Result(127, it.message ?: "su failed") }

    /** Runs a root action and records it (what, and whether it worked) in the log. */
    suspend fun action(context: Context, what: String, script: String): Result {
        val r = run(script)
        log(context, "$what: ${if (r.ok) "ok" else "failed (${r.code}) ${r.out.take(200)}"}")
        return r
    }

    fun log(context: Context, line: String) {
        runCatching {
            val f = logFile(context)
            if (f.length() > 256 * 1024) f.writeText(f.readLines().takeLast(500).joinToString("\n") + "\n")
            f.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}  $line\n")
        }
    }

    fun readLog(context: Context): List<String> = runCatching { logFile(context).readLines().takeLast(60).reversed() }.getOrDefault(emptyList())

    private fun logFile(context: Context) = File(context.filesDir, "root-actions.log")
}
