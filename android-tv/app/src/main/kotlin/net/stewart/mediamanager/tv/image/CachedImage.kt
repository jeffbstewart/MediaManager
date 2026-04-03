package net.stewart.mediamanager.tv.image

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.MaterialTheme
import net.stewart.mediamanager.grpc.ImageRef

/**
 * Composable that loads images via the gRPC bidi streaming image service.
 * Shows cached image instantly if available, fetches over gRPC if not.
 */
@Composable
fun CachedImage(
    ref: ImageRef,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val imageProvider = LocalImageProvider.current

    // Synchronous memory cache lookup for instant first frame
    var bitmap by remember(ref) { mutableStateOf(imageProvider.getCachedBitmap(ref)) }

    LaunchedEffect(ref) {
        val loaded = imageProvider.image(ref)
        if (loaded != null) bitmap = loaded
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
