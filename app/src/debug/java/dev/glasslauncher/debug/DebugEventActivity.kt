package dev.glasslauncher.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.glasslauncher.MainActivity
import dev.glasslauncher.app
import dev.glasslauncher.data.ConfigStore
import dev.glasslauncher.data.LauncherConfig
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Debug builds only. Handles `glassdev://event?name=<event>&payload=<json>`, the URL shape
 * agent-device's `trigger-app-event` sends, then returns to Home:
 *
 * - `config`: merges the payload's keys into [LauncherConfig], e.g. `{"idleFadeMinutes":0}`.
 * - `home`: just returns to Home, resetting focus.
 *
 * Results are logged under the `GlassDebug` tag.
 */
class DebugEventActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val name = uri?.getQueryParameter("name")
        val payload = uri?.getQueryParameter("payload")?.takeIf { it.isNotBlank() }
        lifecycleScope.launch {
            runCatching {
                when (name) {
                    "config" -> mergeConfig(ConfigStore.json.parseToJsonElement(requireNotNull(payload) { "config needs a payload" }).jsonObject)
                    "home" -> Unit
                    else -> error("unknown event: $name")
                }
            }.onSuccess { Log.i(TAG, "event $name ok") }
                .onFailure { Log.e(TAG, "event $name failed: ${it.message}") }
            startActivity(
                Intent(this@DebugEventActivity, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
        }
    }

    private suspend fun mergeConfig(patch: JsonObject) {
        val store = app.config
        val current = ConfigStore.json.encodeToJsonElement(LauncherConfig.serializer(), store.config.value).jsonObject
        store.import(JsonObject(current + patch).toString())
    }

    private companion object { const val TAG = "GlassDebug" }
}
