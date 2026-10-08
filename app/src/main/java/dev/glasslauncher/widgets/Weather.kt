package dev.glasslauncher.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text
import dev.glasslauncher.app
import dev.glasslauncher.data.WeatherConfig
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.roundToInt

/** Open-Meteo: free, no API key. */
object Weather {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun geocode(http: OkHttpClient, query: String, fahrenheit: Boolean): WeatherConfig? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", query).addQueryParameter("count", "1").build()
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val first = json.parseToJsonElement(r.body.string()).jsonObject["results"]!!.jsonArray.first().jsonObject
                val name = listOfNotNull(
                    first["name"]?.jsonPrimitive?.content,
                    first["admin1"]?.jsonPrimitive?.content,
                ).joinToString(", ")
                WeatherConfig(name, first["latitude"]!!.jsonPrimitive.double, first["longitude"]!!.jsonPrimitive.double, fahrenheit)
            }
        }.getOrNull()
    }

    suspend fun current(http: OkHttpClient, cfg: WeatherConfig): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", cfg.latitude.toString())
                .addQueryParameter("longitude", cfg.longitude.toString())
                .addQueryParameter("current", "temperature_2m,weather_code,is_day")
                .addQueryParameter("temperature_unit", if (cfg.fahrenheit) "fahrenheit" else "celsius")
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val current = json.parseToJsonElement(r.body.string()).jsonObject["current"]!!.jsonObject
                val temp = current["temperature_2m"]!!.jsonPrimitive.double.roundToInt()
                val code = current["weather_code"]!!.jsonPrimitive.int
                val day = current["is_day"]?.jsonPrimitive?.int != 0
                "${symbol(code, day)}  $temp°"
            }
        }.getOrNull()
    }

    private fun symbol(code: Int, day: Boolean) = when (code) {
        0 -> if (day) "☀" else "☾"
        1, 2 -> if (day) "⛅" else "☁"
        3 -> "☁"
        45, 48 -> "≋"
        in 51..67, in 80..82 -> "☂"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "⚡"
        else -> "☁"
    }
}

/**
 * One reading for the whole launcher: the status pill and Control Center used to fetch separately (each
 * on its own 30-minute loop), so they could show different temperatures. Refreshed every 30 minutes while
 * anything shows it, every 2 minutes after a failure, and right away when the city or units change.
 */
class WeatherRepository(private val http: OkHttpClient, private val scope: kotlinx.coroutines.CoroutineScope) {
    private val _reading = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val reading: kotlinx.coroutines.flow.StateFlow<String?> = _reading
    private var cfg: WeatherConfig? = null
    private var job: kotlinx.coroutines.Job? = null

    fun follow(next: WeatherConfig) {
        if (next == cfg && job?.isActive == true) return
        cfg = next
        _reading.value = null
        job?.cancel()
        job = scope.launch {
            while (true) {
                Weather.current(http, next)?.let { _reading.value = it }
                delay(if (_reading.value == null) 120_000 else 30 * 60_000L)
            }
        }
    }
}

@Composable
fun WeatherLabel(cfg: WeatherConfig, color: androidx.compose.ui.graphics.Color = LocalPalette.current.primary, style: androidx.compose.ui.text.TextStyle = Type.body.copy(fontSize = Type.body.fontSize * 0.86f)) {
    val repo = LocalContext.current.app.weather
    androidx.compose.runtime.LaunchedEffect(cfg) { repo.follow(cfg) }
    val text by repo.reading.collectAsState()
    text?.let { Text(it, style = style, color = color, modifier = Modifier.testTag("weather")) }
}
