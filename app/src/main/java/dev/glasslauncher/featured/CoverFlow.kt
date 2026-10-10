package dev.glasslauncher.featured

import android.graphics.Bitmap
import android.util.LruCache
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.glasslauncher.ui.LocalUiPrefs
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A cover's width in dp: the centre cover, about 560 px of a 1080p screen. */
private const val COVER_DP = 280

/**
 * The Cover Flow (Spotify's Top Shelf): square covers in a row, the centre one flat and the neighbours turned and
 * stacked ([CoverFlowGeometry]). One animated position drives every cover, so any key press, even mid-glide,
 * continues from where each cover is. Left and Right move through the covers, Select opens the album or playlist
 * (its deep link), Down leaves the shelf. Reflections are baked into the cover bitmaps once ([CoverReflection]).
 */
@Composable
fun CoverFlow(
    items: List<FeaturedItem>,
    index: Int,
    onIndex: (Int) -> Unit,
    onOpen: (FeaturedItem) -> Unit,
    onExit: () -> Unit,
    label: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    /** Where Up goes (Home's status pill, for Control Center): the flow fills the screen, so nothing is above it to find. */
    upTo: FocusRequester? = null,
    /** The cover picture, with its reflection, for an item's image; the default loads it (previews and tests give their own). */
    coverArt: @Composable (String?, Boolean) -> ImageBitmap? = { url, centre -> rememberCoverArt(url, centre) },
) {
    if (items.isEmpty()) return
    val reduceMotion = LocalUiPrefs.current.reduceMotion
    val last = items.lastIndex
    val current = index.coerceIn(0, last)
    // The index the next key steps from: kept here (not just the caller's state) so two quick presses before the
    // next recomposition are two steps, not one.
    var target by remember { androidx.compose.runtime.mutableIntStateOf(current) }
    LaunchedEffect(current) { target = current }
    val position = remember { Animatable(current.toFloat()) }
    LaunchedEffect(current, reduceMotion) {
        if (reduceMotion) position.snapTo(current.toFloat())
        else position.animateTo(current.toFloat(), spring(dampingRatio = 0.9f, stiffness = 380f))
    }
    val density = LocalDensity.current
    val coverPx = with(density) { COVER_DP.dp.toPx() }
    val shown = items[current]
    Box(
        modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusProperties { if (upTo != null) up = upTo }
            .focusable()
            .semantics { contentDescription = listOfNotNull(shown.title, shown.subtitle).joinToString(", ") }
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (k.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (target > 0) { target--; onIndex(target) }; true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (target < last) { target++; onIndex(target) }; true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { onExit(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> { onOpen(shown); true }
                    else -> false
                }
            }
            .testTag("cover-flow"),
    ) {
        Text(label, Modifier.align(Alignment.TopCenter).padding(top = 22.dp).testTag("cover-flow-label"))
        // The covers: nearest the centre last, so each is drawn over the one outside it.
        // The window follows the target index (so it changes only when a key is pressed); each cover's own
        // pose is read from the animated position in the layer phase, so a glide never recomposes the row.
        val reach = CoverFlowGeometry.VISIBLE_SIDE + 2
        val from = (current - reach).coerceAtLeast(0)
        val to = (current + reach).coerceAtMost(last)
        for (i in from..to) {
            androidx.compose.runtime.key(items[i].id) {
                Cover(
                    index = i,
                    position = { position.value },
                    stackOrder = -kotlin.math.abs(i - current).toFloat(),
                    coverPx = coverPx,
                    art = coverArt(items[i].image, i == current),
                    item = items[i],
                    tag = "cover:$i",
                    modifier = Modifier.align(Alignment.Center).offset(y = (-24).dp),
                )
            }
        }
        // Title and subtitle of the centre cover, under it.
        Crossfade(shown, animationSpec = tween(220), label = "cover-title", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)) { it ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                Text(it.title, Modifier.testTag("cover-title"), heading = true)
                it.subtitle?.let { sub -> Text(sub, Modifier.padding(top = 4.dp).testTag("cover-subtitle")) }
            }
        }
    }
}

@Composable
private fun Cover(index: Int, position: () -> Float, stackOrder: Float, coverPx: Float, art: ImageBitmap?, item: FeaturedItem, tag: String, modifier: Modifier) {
    val density = LocalDensity.current
    Box(
        modifier
            .size(COVER_DP.dp, (COVER_DP * (1f + CoverReflection.HEIGHT)).roundToInt().dp)
            // Nearer the target is on top; a cover crossing another mid-glide keeps this order (no flicker).
            .zIndex(stackOrder)
            .graphicsLayer {
                val pose = CoverFlowGeometry.pose(index - position())
                translationX = pose.x * coverPx
                rotationY = pose.rotationY
                scaleX = pose.scale
                scaleY = pose.scale
                alpha = pose.alpha
                cameraDistance = 14f * density.density
                // The reflection belongs under the cover: scale and turn about the cover's own centre.
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f / (1f + CoverReflection.HEIGHT))
            }
            // After the layer, so the tag's bounds are where the cover is drawn.
            .testTag(tag),
    ) {
        val bitmap = art
        if (bitmap != null) {
            Image(bitmap, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        } else {
            // Until the art is there (and if it never comes): the title on a tint, never a plain dark square (P63).
            dev.glasslauncher.ui.ArtFallback(item.title, Modifier.size(COVER_DP.dp), seed = item.image ?: item.title, subtitle = item.subtitle)
        }
    }
}

@Composable
private fun Text(text: String, modifier: Modifier = Modifier, heading: Boolean = false) {
    androidx.compose.foundation.text.BasicText(
        text,
        modifier = modifier,
        style = (if (heading) Type.heading else Type.secondary).copy(color = Color.White, textAlign = TextAlign.Center),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The cover with its reflection baked in, loaded and baked once per image and kept for a few at a time. */
@Composable
private fun rememberCoverArt(url: String?, centre: Boolean): ImageBitmap? {
    val context = LocalContext.current
    // Asked again when the cover becomes the centre one, and when the network returns: a failed load is not final.
    val network = dev.glasslauncher.ui.NetworkEpoch.value
    val art by produceState(url?.let { CoverArtCache.get(it) }, url, centre, network) {
        if (url == null) return@produceState
        CoverArtCache.get(url)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.Default) {
            val request = ImageRequest.Builder(context).data(url).size(CoverArtCache.SIZE).allowHardware(false).build()
            // A failed fetch is asked again with a growing wait (a few times in this run).
            var result: SuccessResult? = null
            for (attempt in 0..dev.glasslauncher.ui.ArtRetry.MAX_ATTEMPTS) {
                result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                if (result != null) break
                if (attempt < dev.glasslauncher.ui.ArtRetry.MAX_ATTEMPTS) kotlinx.coroutines.delay(dev.glasslauncher.ui.ArtRetry.delayMs(attempt))
            }
            val source = result?.image?.toBitmap() ?: return@withContext null
            // Not recycled: Coil may hold this very bitmap for other users of the URL.
            CoverReflection.bake(CoverReflection.squared(source)).asImageBitmap().also { CoverArtCache.put(url, it) }
        }
    }
    return art
}

private object CoverArtCache {
    const val SIZE = 560
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    fun get(url: String): ImageBitmap? = cache.get(url)
    fun put(url: String, art: ImageBitmap) { cache.put(url, art) }
}
