package net.stewart.mediamanager.tv.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.stewart.mediamanager.grpc.Episode
import net.stewart.mediamanager.grpc.listEpisodesRequest
import net.stewart.mediamanager.tv.grpc.GrpcClient

@Composable
fun EpisodesScreen(
    titleId: Long,
    seasonNumber: Int,
    grpcClient: GrpcClient,
    onEpisodeClick: (transcodeId: Long) -> Unit,
    onBack: () -> Unit = {}
) {
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(titleId, seasonNumber) {
        try {
            val response = grpcClient.withAuth {
                grpcClient.catalogService().listEpisodes(listEpisodesRequest {
                    this.titleId = titleId
                    this.seasonNumber = seasonNumber
                })
            }
            episodes = response.episodesList
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.tv.material3.OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Season $seasonNumber", style = MaterialTheme.typography.headlineSmall)
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(episodes) { episode ->
                        EpisodeCard(episode, onEpisodeClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: Episode, onClick: (Long) -> Unit) {
    Card(
        onClick = { if (episode.playable && episode.hasTranscodeId()) onClick(episode.transcodeId) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "E${episode.episodeNumber}" +
                        if (episode.hasName()) " - ${episode.name}" else "",
                    style = MaterialTheme.typography.titleMedium
                )
                if (!episode.playable) {
                    Text("Not available", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Resume progress bar
            if (episode.resumePosition.seconds > 0 && episode.hasDuration() && episode.duration.seconds > 0) {
                val progress = (episode.resumePosition.seconds / episode.duration.seconds).toFloat()
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
