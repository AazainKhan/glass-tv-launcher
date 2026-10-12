package dev.glasslauncher.featured

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
    coverArt: (@Composable (String?, Boolean) -> ImageBitmap?)? = null,
    /** At rest on Home (P79b): the same carousel as a picture: not focusable, no keys, no label or title. */
    preview: Boolean = false,
    /** The covers' own opacity on top of their pose (read in the layer phase): Home holds them back while the preview morphs (P86). */
    coversAlpha: () -> Float = { 1f },
    /** The app's glyph beside the title (P86). */
    labelIcon: ImageBitmap? = null,
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
    // Decoded at the size they are shown (P79b memory): the resting preview's covers are REST_SCALE of the flow's, and
    // each of the two keeps its own pool, dropped when that carousel leaves (the flow closed, Spotify unfocused).
    val pool = if (preview) CoverArtCache.Rest else CoverArtCache.Flow
    val artPx = if (preview) (coverPx * REST_SCALE).roundToInt() else coverPx.roundToInt()
    if (coverArt == null) androidx.compose.runtime.DisposableEffect(pool) { onDispose { pool.release() } }
    val art: @Composable (String?, Boolean) -> ImageBitmap? = coverArt ?: { url, _ -> rememberCoverArt(url, pool, artPx) }
    Box(
        modifier
            .fillMaxSize()
            .then(if (preview) Modifier else Modifier
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
            })
            .testTag(if (preview) "cover-preview" else "cover-flow"),
    ) {
        if (!preview) androidx.compose.foundation.layout.Row(
            Modifier.align(Alignment.TopCenter).padding(top = 22.dp).testTag("cover-flow-label"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            // The app's own glyph at the title's height, beside it.
            labelIcon?.let { Image(it, null, modifier = Modifier.size(26.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))) }
            Text(label)
        }
        // The covers: nearest the centre last, so each is drawn over the one outside it.
        // The window follows the target index (so it changes only when a key is pressed); each cover's own
        // pose is read from the animated position in the layer phase, so a glide never recomposes the row.
        // (The preview doesn't glide, so it needs only the covers that show.)
        val reach = CoverFlowGeometry.VISIBLE_SIDE + if (preview) 0 else 2
        val from = (current - reach).coerceAtLeast(0)
        val to = (current + reach).coerceAtMost(last)
        for (i in from..to) {
            androidx.compose.runtime.key(items[i].id) {
                Cover(
                    index = i,
                    position = { position.value },
                    stackOrder = -kotlin.math.abs(i - current).toFloat(),
                    coverPx = coverPx,
                    art = art(items[i].image, i == current),
                    item = items[i],
                    coversAlpha = coversAlpha,
                    tag = if (preview) "preview-cover:$i" else "cover:$i",
                    modifier = Modifier.align(Alignment.Center).offset(y = (-24).dp),
                )
            }
        }
        // Title and subtitle of the centre cover, under it.
        if (!preview) Crossfade(shown, animationSpec = tween(220), label = "cover-title", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)) { it ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                Text(it.title, Modifier.testTag("cover-title"), heading = true)
                it.subtitle?.let { sub -> Text(sub, Modifier.padding(top = 4.dp).testTag("cover-subtitle")) }
            }
        }
    }
}

@Composable
private fun Cover(index: Int, position: () -> Float, stackOrder: Float, coverPx: Float, art: ImageBitmap?, item: FeaturedItem, coversAlpha: () -> Float, tag: String, modifier: Modifier) {
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
                alpha = pose.alpha * coversAlpha()
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

/** The cover with its reflection baked in, loaded and baked once per image and size, and kept in [pool]. */
@Composable
private fun rememberCoverArt(url: String?, pool: CoverArtCache.Pool, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    // Asked again when the network returns: a failed load is not final.
    val network = dev.glasslauncher.ui.NetworkEpoch.value
    // The full flow's cover shows the resting preview's bitmap of the same cover until its own is baked (P92): no
    // fallback tile and no wait at the hand-over, and the sharp one replaces it as it arrives.
    val stand: (String) -> ImageBitmap? = { u -> pool.get(u) ?: if (pool === CoverArtCache.Flow) CoverArtCache.Rest.get(u) else null }
    val art by produceState(url?.let { stand(it) }, url, network) {
        if (url == null) return@produceState
        pool.get(url)?.let { value = it; return@produceState }
        stand(url)?.let { value = it }
        value = withContext(Dispatchers.Default) {
            // Not kept in Coil's memory cache either: the baked cover is the only copy (the disk cache has the file).
            val request = ImageRequest.Builder(context).data(url).size(sizePx).allowHardware(false)
                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build()
            // A failed fetch is asked again with a growing wait (a few times in this run).
            var result: SuccessResult? = null
            for (attempt in 0..dev.glasslauncher.ui.ArtRetry.MAX_ATTEMPTS) {
                result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                if (result != null) break
                if (attempt < dev.glasslauncher.ui.ArtRetry.MAX_ATTEMPTS) kotlinx.coroutines.delay(dev.glasslauncher.ui.ArtRetry.delayMs(attempt))
            }
            val source = result?.image?.toBitmap() ?: return@withContext null
            val squared = CoverReflection.squared(source)
            CoverReflection.bake(squared).asImageBitmap().also {
                pool.put(url, it)
                // The source (and a cropped copy of it) is only the baking input now.
                if (squared !== source) squared.recycle()
            }
        }
    }
    return art
}

internal object CoverArtCache {
    /** Baked covers (with their reflection) of one carousel, within [maxBytes]; [release] drops them all. */
    class Pool(private val maxBytes: Int) {
        private val cache = object : LruCache<String, ImageBitmap>(maxBytes) {
            override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
        }
        fun get(url: String): ImageBitmap? = cache.get(url)
        fun put(url: String, art: ImageBitmap) { cache.put(url, art) }
        val bytes: Int get() = cache.size()

        /**
         * The carousel is gone: drop every cover now, and free their pixels a moment later (not straight away: a
         * frame already handed to the render thread may still draw one).
         */
        fun release() {
            val gone = cache.snapshot().values.toList()
            cache.evictAll()
            if (gone.isEmpty()) return
            Handler(Looper.getMainLooper()).postDelayed({
                gone.forEach { runCatching { val b = it.asAndroidBitmap(); if (!b.isRecycled) b.recycle() } }
            }, 1500)
        }
    }

    /** The resting preview's covers (~0.6 MB each at 1080p) and the full-screen flow's (~1.6 MB each). */
    val Rest = Pool(10 * 1024 * 1024)
    val Flow = Pool(20 * 1024 * 1024)
}

/**
 * Spotify's resting Top Shelf (P79b): not a stretched backdrop but the Cover Flow itself, covers at their own size,
 * smaller and lifted clear of the tray. As Up opens the full-screen flow ([expand] 0 -> 1) it scales and settles into
 * exactly the pose of [ExpandedShelf]'s flow and fades as that one fades in, so it is one carousel gaining focus.
 * Nothing is baked or drawn per frame beyond the cover layers the flow already has.
 */
@Composable
fun CoverPreview(
    feed: FeaturedFeed, index: Int, expand: () -> Float, restCentreY: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier,
    /** The focused cover's own title and subtitle above the covers, in small type (P106), the same text the full flow shows under it. */
    caption: Boolean = false,
) {
    val density = LocalDensity.current
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val shiftPx = with(density) { (restCentreY.toPx() - (screenH.dp.toPx() / 2f)) }
    val focus = remember { FocusRequester() }
    Box(modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val p = expand().coerceIn(0f, 1f)
            val s = REST_SCALE + (1f - REST_SCALE) * p
            scaleX = s; scaleY = s
            translationY = shiftPx * (1f - p)
            // One morph (P86): these covers grow and move into the full-screen pose and stay until they are exactly
            // there; then the expanded flow's covers take over in the same frame (same bitmaps, same pose), so there
            // is no frame with two carousels showing. Down runs it back.
            alpha = if (p >= HANDOVER) 0f else 1f
        },
    ) {
        CoverFlow(items = feed.items, index = index, onIndex = {}, onOpen = {}, onExit = {}, label = "", focusRequester = focus, preview = true)
    }
    if (caption && feed.items.isNotEmpty()) {
        val inset = dev.glasslauncher.ui.LocalMetrics.current.inset
        val item = feed.items[index.coerceIn(0, feed.items.lastIndex)]
        Crossfade(
            item, animationSpec = tween(220), label = "cover-preview-caption",
            modifier = Modifier
                .padding(start = inset)
                .offset(y = restCentreY - (COVER_DP * REST_SCALE / 2f).dp - 56.dp)
                .graphicsLayer { alpha = (1f - expand().coerceIn(0f, 1f) * 3f).coerceIn(0f, 1f) }
                .testTag("cover-preview-caption"),
        ) { it ->
            Column(Modifier.widthIn(max = 380.dp)) {
                androidx.compose.foundation.text.BasicText(
                    it.title,
                    style = Type.label.copy(color = Color.White.copy(alpha = 0.9f)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("cover-preview-title"),
                )
                it.subtitle?.let { sub ->
                    androidx.compose.foundation.text.BasicText(
                        sub,
                        style = Type.caption.copy(color = Color.White.copy(alpha = 0.68f)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp).testTag("cover-preview-subtitle"),
                    )
                }
            }
        }
    }
    }
}

/** The expand progress at which the preview's covers hand over to the full flow's (they are in the same pose). */
const val HANDOVER = 0.995f

/** How much smaller the resting carousel is than the full-screen one. */
private const val REST_SCALE = 0.62f
