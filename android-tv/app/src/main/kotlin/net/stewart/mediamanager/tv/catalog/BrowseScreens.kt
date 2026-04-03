package net.stewart.mediamanager.tv.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.tv.material3.Card
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.stewart.mediamanager.grpc.Title
import net.stewart.mediamanager.grpc.collectionIdRequest
import net.stewart.mediamanager.grpc.genreIdRequest
import net.stewart.mediamanager.grpc.tagIdRequest
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.ui.components.PosterCard

// ── Collection Detail ────────────────────────────────────────────────

@Composable
fun CollectionDetailScreen(
    tmdbCollectionId: Int,
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onTitleClick: (Long) -> Unit,
    onBack: () -> Unit = {}
) {
    var collectionDetail by remember { mutableStateOf<net.stewart.mediamanager.grpc.CollectionDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val baseUrl = authManager.httpBaseUrl ?: ""

    LaunchedEffect(tmdbCollectionId) {
        try {
            collectionDetail = grpcClient.withAuth {
                grpcClient.catalogService().getCollectionDetail(
                    collectionIdRequest { this.tmdbCollectionId = tmdbCollectionId })
            }
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.tv.material3.OutlinedButton(onClick = onBack) { Text("Back") }
            Text(collectionDetail?.name ?: "Collection", style = MaterialTheme.typography.headlineSmall)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val items = collectionDetail?.itemsList ?: emptyList()
                items(items.size) { index ->
                    val item = items[index]
                    val isOwned = item.owned
                    Card(
                        onClick = { if (isOwned && item.hasTitleId()) onTitleClick(item.titleId) },
                        modifier = Modifier
                            .size(width = 150.dp, height = 225.dp)
                            .alpha(if (isOwned) 1f else 0.6f)
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (item.hasTitleId()) {
                                net.stewart.mediamanager.tv.image.CachedImage(
                                    ref = net.stewart.mediamanager.tv.image.posterRef(item.titleId),
                                    contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (item.posterUrl.isNotEmpty()) {
                                // Unowned items — use collection poster via TMDB
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(item.name, style = MaterialTheme.typography.bodySmall,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(8.dp))
                                }
                            } else {
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(item.name, style = MaterialTheme.typography.bodySmall,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(8.dp))
                                }
                            }
                            if (!isOwned) {
                                Text(
                                    text = "Not owned",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .background(androidx.compose.ui.graphics.Color(0xCC000000))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tag Detail ───────────────────────────────────────────────────────

@Composable
fun TagDetailScreen(
    tagId: Long,
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onTitleClick: (Long) -> Unit,
    onBack: () -> Unit = {}
) {
    var name by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf<List<Title>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val baseUrl = authManager.httpBaseUrl ?: ""

    LaunchedEffect(tagId) {
        try {
            val detail = grpcClient.withAuth { grpcClient.catalogService().getTagDetail(tagIdRequest { this.tagId = tagId }) }
            name = detail.name
            titles = detail.titlesList
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    TitleGridBrowse(name.ifEmpty { "Tag" }, titles, loading, error, baseUrl, onTitleClick, onBack)
}

// ── Genre Detail ─────────────────────────────────────────────────────

@Composable
fun GenreDetailScreen(
    genreId: Long,
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onTitleClick: (Long) -> Unit,
    onBack: () -> Unit = {}
) {
    var name by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf<List<Title>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val baseUrl = authManager.httpBaseUrl ?: ""

    LaunchedEffect(genreId) {
        try {
            val detail = grpcClient.withAuth { grpcClient.catalogService().getGenreDetail(genreIdRequest { this.genreId = genreId }) }
            name = detail.name
            titles = detail.titlesList
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    TitleGridBrowse(name.ifEmpty { "Genre" }, titles, loading, error, baseUrl, onTitleClick, onBack)
}

// ── Shared grid for Tag/Genre (both return a list of Title) ──────────

@Composable
private fun TitleGridBrowse(
    heading: String,
    titles: List<Title>,
    loading: Boolean,
    error: String?,
    baseUrl: String,
    onTitleClick: (Long) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.tv.material3.OutlinedButton(onClick = onBack) { Text("Back") }
            Text(heading, style = MaterialTheme.typography.headlineSmall)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(titles) { title ->
                    PosterCard(title = title, baseUrl = baseUrl, onClick = { onTitleClick(title.id) })
                }
            }
        }
    }
}

@Composable
private fun BrowseGrid(
    title: String,
    loading: Boolean,
    error: String?,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error, color = MaterialTheme.colorScheme.error) }
            else -> content()
        }
    }
}
