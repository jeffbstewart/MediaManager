package net.stewart.mediamanager.tv.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.stewart.mediamanager.grpc.ActorDetail
import net.stewart.mediamanager.grpc.actorIdRequest
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.image.CachedImage
import net.stewart.mediamanager.tv.image.headshotRef
import net.stewart.mediamanager.tv.ui.components.PosterCard

@Composable
fun ActorScreen(
    tmdbPersonId: Int,
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onTitleClick: (Long) -> Unit,
    onBack: () -> Unit = {}
) {
    var actor by remember { mutableStateOf<ActorDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val baseUrl = authManager.httpBaseUrl ?: ""

    LaunchedEffect(tmdbPersonId) {
        try {
            actor = grpcClient.withAuth { grpcClient.catalogService().getActorDetail(actorIdRequest { this.tmdbPersonId = tmdbPersonId }) }
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
        actor != null -> {
            val a = actor!!
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 48.dp, vertical = 24.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Headshot
                    CachedImage(
                        ref = headshotRef(tmdbPersonId),
                        contentDescription = a.name,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.tv.material3.OutlinedButton(onClick = onBack) { Text("Back") }
                            Text(a.name, style = MaterialTheme.typography.headlineLarge)
                        }
                        if (a.hasPlaceOfBirth()) {
                            Text(
                                a.placeOfBirth, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (a.hasBiography()) {
                            Text(
                                a.biography, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp),
                                maxLines = 6,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (a.ownedTitlesList.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("In Your Collection", style = MaterialTheme.typography.titleLarge)
                    LazyRow(
                        contentPadding = PaddingValues(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(a.ownedTitlesList) { credit ->
                            PosterCard(
                                title = credit.title,
                                baseUrl = baseUrl,
                                onClick = { onTitleClick(credit.title.id) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
