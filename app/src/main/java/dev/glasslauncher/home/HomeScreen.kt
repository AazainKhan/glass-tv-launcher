package dev.glasslauncher.home

import android.content.ActivityNotFoundException
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.apps.AppRepository
import dev.glasslauncher.apps.TileArt
import kotlinx.coroutines.flow.Flow

private val TileShape = RoundedCornerShape(14.dp)

@Composable
fun HomeScreen(
    repository: AppRepository,
    tileArt: TileArt,
    homePresses: Flow<Unit>,
) {
    val context = LocalContext.current
    val apps by remember { repository.apps() }.collectAsState(initial = emptyList())
    val gridState = rememberLazyGridState()
    val firstTile = remember { FocusRequester() }

    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) runCatching { firstTile.requestFocus() }
    }
    LaunchedEffect(Unit) {
        homePresses.collect {
            gridState.animateScrollToItem(0)
            runCatching { firstTile.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2333), Color(0xFF0B0E14)))),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = gridState,
            contentPadding = PaddingValues(start = 58.dp, end = 58.dp, top = 220.dp, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("app-grid")
                .focusRestorer(firstTile),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                AppTile(
                    app = app,
                    tileArt = tileArt,
                    modifier = if (index == 0) Modifier.focusRequester(firstTile) else Modifier,
                    onClick = {
                        val intent = repository.launchIntent(app) ?: return@AppTile
                        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
                    },
                )
            }
        }
    }
}

@Composable
private fun AppTile(
    app: AppEntry,
    tileArt: TileArt,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "label")
    val art by produceState<ImageBitmap?>(null, app.packageName) { value = tileArt.load(app) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(TileShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0x22FFFFFF),
                focusedContainerColor = Color(0x33FFFFFF),
            ),
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused }
                .testTag("app:${app.packageName}")
                .semantics { contentDescription = app.label },
        ) {
            art?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 14.dp)
                .graphicsLayer { alpha = labelAlpha },
        )
    }
}
