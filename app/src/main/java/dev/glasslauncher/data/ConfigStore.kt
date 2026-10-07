package dev.glasslauncher.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("launcher")

class ConfigStore(private val context: Context, scope: CoroutineScope) {

    private val key = stringPreferencesKey("config")

    private val flow = context.dataStore.data.map { prefs -> decode(prefs[key]) }

    // Read once synchronously so the first frame already has the user's layout and wallpaper.
    val config: StateFlow<LauncherConfig> = flow.stateIn(
        scope,
        SharingStarted.Eagerly,
        runBlocking { flow.first() },
    )

    suspend fun update(transform: (LauncherConfig) -> LauncherConfig) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(LauncherConfig.serializer(), transform(decode(prefs[key])))
        }
    }

    fun export(): String = json.encodeToString(LauncherConfig.serializer(), config.value)

    suspend fun import(text: String) {
        val parsed = json.decodeFromString(LauncherConfig.serializer(), text)
        update { parsed }
    }

    private fun decode(text: String?): LauncherConfig =
        text?.let { runCatching { json.decodeFromString(LauncherConfig.serializer(), it) }.getOrNull() }
            ?: LauncherConfig()

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}
