package net.stewart.mediamanager.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.stewart.mediamanager.grpc.Title
import net.stewart.mediamanager.tv.image.CachedImage
import net.stewart.mediamanager.tv.image.posterRef

@Composable
fun PosterCard(
    title: Title,
    baseUrl: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .size(width = 150.dp, height = 225.dp)
            .alpha(if (title.playable) 1f else 0.5f)
    ) {
        Box(Modifier.fillMaxSize()) {
            CachedImage(
                ref = posterRef(title.id),
                contentDescription = title.name,
                modifier = Modifier.fillMaxSize()
            )

            // Non-playable badge
            if (!title.playable) {
                Text(
                    text = "Not Playable",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF9800),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
