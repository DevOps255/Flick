package com.flick.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import android.util.LruCache
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.flick.app.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * The picture of a file: a thumbnail for photos and videos, the launcher icon for an app,
 * and a plain icon for everything else. Thumbnails are loaded and cached by Coil.
 * With a null [size] the picture takes the room its parent gives it.
 */
@Composable
fun FileThumb(uri: String, mime: String, packageName: String?, modifier: Modifier = Modifier, size: Dp? = 48.dp) {
    Box(
        modifier.then(if (size != null) Modifier.size(size) else Modifier).clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // The icon keeps its own size inside the tile. Stretched to the whole tile it looks blurred.
            packageName != null -> AppIcon(packageName, Modifier.fillMaxSize(0.62f))
            mime.startsWith("image/") || mime.startsWith("video/") -> {
                val context = LocalContext.current
                // Decoding a photo at full resolution for a small tile is what made scrolling jerky.
                val request = remember(uri) { ImageRequest.Builder(context).data(uri).size(THUMBNAIL_PIXELS).crossfade(false).build() }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            mime.startsWith("audio/") -> Icon(Icons.Outlined.MusicNote, null, tint = Accent, modifier = Modifier.fillMaxSize(0.5f))
            mime == APK_MIME -> Icon(Icons.Outlined.Android, null, tint = Accent, modifier = Modifier.fillMaxSize(0.5f))
            else -> Icon(Icons.Outlined.Description, null, tint = Accent, modifier = Modifier.fillMaxSize(0.5f))
        }
    }
}

/** The launcher icon of an installed app, read off the main thread and kept in memory while the list scrolls. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = iconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PIXELS, ICON_PIXELS).asImageBitmap() }.getOrNull()
            }?.also { iconCache.put(packageName, it) }
        }
    }
    icon?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High, modifier = modifier) }
}

private val iconCache = LruCache<String, ImageBitmap>(300)
private const val ICON_PIXELS = 192
private const val THUMBNAIL_PIXELS = 256
