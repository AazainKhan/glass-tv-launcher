package dev.glasslauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay

/** When to try a failed image again: a growing wait, a few times, then the fallback stays until the card is focused. */
object ArtRetry {
    const val MAX_ATTEMPTS = 3
    fun delayMs(attempt: Int): Long = 600L * (1 shl attempt.coerceIn(0, 4))
}

/** A designed stand-in for art that is missing: a tint (stable per [seed]) with the title on it, never an empty frame. */
@Composable
fun ArtFallback(title: String, modifier: Modifier = Modifier, seed: String = title, subtitle: String? = null) {
    val hue = (seed.hashCode().toLong().and(0x7fffffff) % 360).toFloat()
    val top = Color.hsv(hue, 0.42f, 0.46f)
    val bottom = Color.hsv((hue + 24f) % 360f, 0.5f, 0.28f)
    Box(modifier.background(Brush.verticalGradient(listOf(top, bottom))).testTag("art-fallback"), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
            androidx.compose.foundation.text.BasicText(
                title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = Type.secondary.copy(color = Color.White, textAlign = TextAlign.Center),
            )
            subtitle?.let {
                androidx.compose.foundation.text.BasicText(
                    it, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                    style = Type.caption.copy(color = Color.White.copy(alpha = 0.86f), textAlign = TextAlign.Center),
                )
            }
        }
    }
}

/**
 * An image that doesn't stay empty: a failed load is tried again with a growing wait ([ArtRetry]), and when
 * [focused] turns true while it is still missing it starts over (a slow or failed fetch is often fine a moment
 * later). While nothing has loaded, and after the last attempt, [fallback] is drawn (the art's title on a tint
 * by default), so a card is never an empty frame.
 */
@Composable
fun ReliableImage(
    url: String?,
    modifier: Modifier = Modifier,
    title: String = "",
    subtitle: String? = null,
    width: Int = 300,
    height: Int = 170,
    focused: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
    retryDelay: (Int) -> Long = ArtRetry::delayMs,
    /** Called each time a request is made, with how many have been made for this image (for tests). */
    onRequest: (Int) -> Unit = {},
    fallback: @Composable () -> Unit = { ArtFallback(title, Modifier.fillMaxSize(), seed = url ?: title, subtitle = subtitle) },
) {
    val context = LocalContext.current
    // The request number: each retry is a new request (they must differ, or AsyncImage would not ask again).
    var attempt by remember(url) { mutableIntStateOf(0) }
    var loaded by remember(url) { mutableStateOf(false) }
    var gaveUp by remember(url) { mutableStateOf(false) }
    // A run is the first request plus up to MAX_ATTEMPTS retries; focus on a card still missing its art starts a new one.
    var run by remember(url) { mutableIntStateOf(0) }
    val errors = remember(url) { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
    LaunchedEffect(focused) {
        if (focused && !loaded && gaveUp) { gaveUp = false; run++ }
    }
    // The network came back while this was still on its fallback: ask again, without waiting for a refocus.
    val networkEpoch = NetworkEpoch.value
    LaunchedEffect(networkEpoch) {
        if (!loaded && gaveUp) { gaveUp = false; run++ }
    }
    LaunchedEffect(url, run) {
        if (url == null) return@LaunchedEffect
        if (run > 0) attempt++ // the first request of this run
        var failures = 0
        while (true) {
            errors.receive() // this request failed (a request that succeeds never sends, and the loop waits)
            failures++
            if (failures > ArtRetry.MAX_ATTEMPTS) { gaveUp = true; return@LaunchedEffect }
            delay(retryDelay(failures - 1))
            attempt++
        }
    }
    Box(modifier.testTag("reliable-image")) {
        if (url == null || !loaded) fallback()
        if (url != null) {
            val request = remember(url, attempt) {
                onRequest(attempt)
                // Requests with the same fields are equal, and AsyncImage would not ask again: a retry carries its number.
                ImageRequest.Builder(context).data(url).size(width, height).crossfade(false)
                    .apply { if (attempt > 0) memoryCacheKey("$url#retry$attempt") }.build()
            }
            AsyncImage(
                model = request, contentDescription = null, contentScale = contentScale,
                onSuccess = { loaded = true; gaveUp = false },
                onError = { loaded = false; errors.trySend(Unit) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
