package dev.glasslauncher.settings

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.MenuRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DeviceImage(val uri: Uri, val name: String)

/** Lets the user pick an image on the TV: the system file browser when one exists, plus a grid of local photos. */
@Composable
fun ImagePicker(onPicked: (Uri) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onPicked) }
    val canBrowse = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .resolveActivity(context.packageManager) != null
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (canBrowse) MenuRow("Browse Files…", { browse.launch(arrayOf("image/*")) })
        if (!granted) {
            MenuRow("Allow Access to Photos", { requestPermission.launch(permission) })
        } else {
        val images by produceState<List<DeviceImage>?>(null) { value = queryImages(context) }
        when {
            images == null -> Hint("Looking for images…")
            images!!.isEmpty() -> Hint("No images found. Copy some to Downloads or Pictures (for example with Downloader or a USB drive).")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().fillMaxSize(),
            ) {
                items(images!!, key = { it.uri.toString() }) { image ->
                    FocusTile(
                        label = image.name,
                        onClick = { onPicked(image.uri) },
                        focusedScale = 1.08f,
                        modifier = Modifier.aspectRatio(16f / 9f),
                    ) {
                        AsyncImage(model = image.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        }
    }
}

private suspend fun queryImages(context: android.content.Context): List<DeviceImage> = withContext(Dispatchers.IO) {
    val result = ArrayList<DeviceImage>()
    runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext() && result.size < 120) {
                result += DeviceImage(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id)), c.getString(name) ?: "Image")
            }
        }
    }
    result
}
