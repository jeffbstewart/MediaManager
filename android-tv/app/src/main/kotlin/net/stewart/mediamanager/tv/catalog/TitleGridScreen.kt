package net.stewart.mediamanager.tv.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import net.stewart.mediamanager.grpc.MediaType
import net.stewart.mediamanager.grpc.Title
import net.stewart.mediamanager.grpc.listTitlesRequest
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.ui.components.PosterCard

private val SORT_OPTIONS = listOf("popularity", "name", "year", "recent")

@Composable
fun TitleGridScreen(
    mediaType: MediaType,
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onTitleClick: (Long) -> Unit,
    onBack: () -> Unit = {}
) {
    var titles by remember { mutableStateOf<List<Title>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortIndex by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(1) }
    val baseUrl = authManager.httpBaseUrl ?: ""

    LaunchedEffect(sortIndex, page) {
        loading = true
        error = null
        try {
            val response = grpcClient.withAuth {
                grpcClient.catalogService().listTitles(listTitlesRequest {
                    this.page = page
                    this.limit = 50
                    this.sort = SORT_OPTIONS[sortIndex]
                    this.type = mediaType
                    this.playableOnly = true
                })
            }
            titles = response.titlesList
            totalPages = response.pagination.totalPages
        } catch (e: Exception) {
            error = e.message ?: "Failed to load titles"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header with sort controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Text(
                    text = if (mediaType == MediaType.MEDIA_TYPE_MOVIE) "Movies" else "TV Shows",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SORT_OPTIONS.forEachIndexed { i, label ->
                    OutlinedButton(onClick = { sortIndex = i; page = 1 }) {
                        Text(
                            text = label.replaceFirstChar { it.uppercase() },
                            color = if (i == sortIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(titles) { title ->
                        PosterCard(
                            title = title,
                            baseUrl = baseUrl,
                            onClick = { title.id.let(onTitleClick) }
                        )
                    }
                }
            }
        }
    }
}
