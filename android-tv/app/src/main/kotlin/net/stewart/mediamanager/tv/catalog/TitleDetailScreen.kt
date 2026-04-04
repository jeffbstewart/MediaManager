package net.stewart.mediamanager.tv.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.stewart.mediamanager.grpc.CastMember
import net.stewart.mediamanager.grpc.MediaType
import net.stewart.mediamanager.grpc.TitleDetail
import net.stewart.mediamanager.grpc.titleIdRequest
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.image.CachedImage
import net.stewart.mediamanager.tv.image.backdropRef
import net.stewart.mediamanager.tv.image.headshotRef
import net.stewart.mediamanager.tv.image.posterRef

@Composable
fun TitleDetailScreen(
    titleId: Long,
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onActorClick: (Int) -> Unit,
    onGenreClick: (Long) -> Unit,
    onTagClick: (Long) -> Unit,
    onSeasonClick: (Long) -> Unit,
    onCollectionClick: (Int) -> Unit,
    onPlay: (Long) -> Unit = {},
    onBack: () -> Unit = {}
) {
    var detail by remember { mutableStateOf<TitleDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(titleId) {
        try {
            detail = grpcClient.withAuth { grpcClient.catalogService().getTitleDetail(titleIdRequest { this.titleId = titleId }) }
        } catch (e: Exception) {
            error = e.message ?: "Failed to load title"
        } finally {
            loading = false
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        detail != null -> TitleDetailContent(
            detail!!, onActorClick, onGenreClick, onTagClick, onSeasonClick, onCollectionClick, onPlay, onBack
        )
    }
}

@Composable
private fun TitleDetailContent(
    detail: TitleDetail,
    onActorClick: (Int) -> Unit,
    onGenreClick: (Long) -> Unit,
    onTagClick: (Long) -> Unit,
    onSeasonClick: (Long) -> Unit,
    onCollectionClick: (Int) -> Unit,
    onPlay: (Long) -> Unit,
    onBack: () -> Unit
) {
    val title = detail.title

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Hero image — backdrop, falling back to poster for personal videos
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
            val heroRef = if (title.backdropUrl.isNotEmpty()) backdropRef(title.id)
                else posterRef(title.id)
            CachedImage(
                ref = heroRef,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            // Gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xE6121212)),
                            startY = 100f
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 48.dp, bottom = 16.dp)
            ) {
                Text(title.name, style = MaterialTheme.typography.headlineLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (title.hasYear()) Text("${title.year}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        title.contentRating.name.removePrefix("CONTENT_RATING_").replace("_", "-"),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        title.quality.name.removePrefix("QUALITY_"),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.tv.material3.OutlinedButton(onClick = onBack) { Text("Back") }
            if (title.playable && title.hasTranscodeId()) {
                Button(onClick = { onPlay(title.transcodeId) }) { Text("Play") }
            } else {
                Text(
                    "Not available yet",
                    color = Color(0xFFFF9800),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
            if (title.mediaType == MediaType.MEDIA_TYPE_TV) {
                Button(onClick = { onSeasonClick(title.id) }) { Text("Seasons") }
            }
            if (title.hasTmdbCollectionId()) {
                Button(onClick = { onCollectionClick(title.tmdbCollectionId) }) { Text("Collection") }
            }
        }

        // Description
        if (title.hasDescription()) {
            Text(
                title.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp)
            )
        }

        // Genres
        if (detail.genresList.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                items(detail.genresList) { genre ->
                    androidx.tv.material3.OutlinedButton(onClick = { onGenreClick(genre.id) }) {
                        Text(genre.name)
                    }
                }
            }
        }

        // Tags
        if (detail.tagsList.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                items(detail.tagsList) { tag ->
                    androidx.tv.material3.OutlinedButton(onClick = { onTagClick(tag.id) }) {
                        Text(tag.name)
                    }
                }
            }
        }

        // Cast
        if (detail.castList.isNotEmpty()) {
            Text(
                "Cast",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 48.dp, top = 16.dp, bottom = 8.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = detail.castList, key = { it.tmdbPersonId }) { member ->
                    CastCard(member, onClick = { onActorClick(member.tmdbPersonId) })
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CastCard(member: CastMember, onClick: () -> Unit) {
    // Use TV Card for D-pad focus, but with transparent colors to avoid circle-in-square
    androidx.tv.material3.Card(
        onClick = onClick,
        modifier = Modifier.width(100.dp),
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp)
        ) {
            CachedImage(
                ref = headshotRef(member.tmdbPersonId),
                contentDescription = member.name,
                modifier = Modifier.size(80.dp).clip(CircleShape)
            )
            Text(
                member.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (member.hasCharacterName()) {
                Text(
                    member.characterName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
