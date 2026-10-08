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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

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

    suspend fun current(http: OkHttpClient, cfg: WeatherConfig): WeatherReading? = withContext(Dispatchers.IO) {
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
                WeatherReading(condition(code, day), temp)
            }
        }.getOrNull()
    }

    private fun condition(code: Int, day: Boolean) = when (code) {
        0 -> if (day) WeatherCondition.ClearDay else WeatherCondition.ClearNight
        1, 2 -> if (day) WeatherCondition.PartlyDay else WeatherCondition.PartlyNight
        3 -> WeatherCondition.Cloudy
        45, 48 -> WeatherCondition.Fog
        in 51..67, in 80..82 -> WeatherCondition.Rain
        in 71..77, 85, 86 -> WeatherCondition.Snow
        in 95..99 -> WeatherCondition.Storm
        else -> WeatherCondition.Cloudy
    }
}

/** Open-Meteo's WMO codes, folded into the conditions that have an icon. */
enum class WeatherCondition(@androidx.annotation.DrawableRes val icon: Int, val label: String) {
    ClearDay(dev.glasslauncher.R.drawable.ic_weather_clear_day, "Clear"),
    ClearNight(dev.glasslauncher.R.drawable.ic_weather_clear_night, "Clear"),
    PartlyDay(dev.glasslauncher.R.drawable.ic_weather_partly_day, "Partly Cloudy"),
    PartlyNight(dev.glasslauncher.R.drawable.ic_weather_partly_night, "Partly Cloudy"),
    Cloudy(dev.glasslauncher.R.drawable.ic_weather_cloudy, "Cloudy"),
    Fog(dev.glasslauncher.R.drawable.ic_weather_fog, "Fog"),
    Rain(dev.glasslauncher.R.drawable.ic_weather_rain, "Rain"),
    Snow(dev.glasslauncher.R.drawable.ic_weather_snow, "Snow"),
    Storm(dev.glasslauncher.R.drawable.ic_weather_storm, "Thunderstorms"),
}

data class WeatherReading(val condition: WeatherCondition, val temperature: Int)

/**
 * One reading for the whole launcher: the status pill and Control Center used to fetch separately (each
 * on its own 30-minute loop), so they could show different temperatures. Refreshed every 30 minutes while
 * anything shows it, every 2 minutes after a failure, and right away when the city or units change.
 */
class WeatherRepository(private val http: OkHttpClient, private val scope: kotlinx.coroutines.CoroutineScope) {
    private val _reading = kotlinx.coroutines.flow.MutableStateFlow<WeatherReading?>(null)
    val reading: kotlinx.coroutines.flow.StateFlow<WeatherReading?> = _reading
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
    val reading by repo.reading.collectAsState()
    val r = reading ?: return
    // A clean glyph (SF Symbols style) tinted like the text, then the temperature.
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        // One description for the pair, the same wherever it's read ("Clear, 10°").
        modifier = Modifier.testTag("weather").clearAndSetSemantics { contentDescription = "${r.condition.label}, ${r.temperature}°" },
    ) {
        val size = with(androidx.compose.ui.platform.LocalDensity.current) { (style.fontSize * 1.05f).toDp() }
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(r.condition.icon), r.condition.label,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
            modifier = Modifier.size(size),
        )
        Text("${r.temperature}°", style = style, color = color, modifier = Modifier.padding(start = 7.dp))
    }
}
