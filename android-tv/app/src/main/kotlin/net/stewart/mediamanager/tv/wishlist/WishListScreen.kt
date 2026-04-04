package net.stewart.mediamanager.tv.wishlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.MediaType
import net.stewart.mediamanager.grpc.TmdbResult
import net.stewart.mediamanager.grpc.WishItem
import net.stewart.mediamanager.grpc.WishLifecycleStage
import net.stewart.mediamanager.grpc.addWishRequest
import net.stewart.mediamanager.grpc.empty
import net.stewart.mediamanager.grpc.tmdbSearchRequest
import net.stewart.mediamanager.grpc.voteRequest
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.ui.components.TvOutlinedTextField

@Composable
fun WishListScreen(
    grpcClient: GrpcClient,
    onTitleClick: (Long) -> Unit,
    onBack: () -> Unit = {}
) {
    var wishes by remember { mutableStateOf<List<WishItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            try {
                wishes = grpcClient.withAuth {
                    grpcClient.wishListService().listWishes(empty { })
                }.wishesList
            } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (showSearch) {
        TmdbSearchPanel(
            grpcClient = grpcClient,
            onWishAdded = { showSearch = false; refresh() },
            onBack = { showSearch = false }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Wish List", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { showSearch = true }) { Text("Add Wish") }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            wishes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No wishes yet") }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(wishes) { wish ->
                    WishCard(wish, grpcClient, onTitleClick) { refresh() }
                }
            }
        }
    }
}

@Composable
private fun WishCard(
    wish: WishItem,
    grpcClient: GrpcClient,
    onTitleClick: (Long) -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    Card(
        onClick = { if (wish.hasTitleId()) onTitleClick(wish.titleId) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(wish.title, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (wish.hasReleaseYear()) Text("${wish.releaseYear}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        when (wish.lifecycleStage) {
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_READY_TO_WATCH -> "Ready to watch"
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_ORDERED -> "Ordered"
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_IN_HOUSE_PENDING_NAS -> "In house"
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_ON_NAS_PENDING_DESKTOP -> "On NAS"
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_NOT_FEASIBLE -> "Not feasible"
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_WONT_ORDER -> "Won't order"
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_NEEDS_ASSISTANCE -> "Needs help"
                            else -> "Wished"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (wish.lifecycleStage) {
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_READY_TO_WATCH -> Color(0xFF4CAF50)
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_ORDERED,
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_IN_HOUSE_PENDING_NAS,
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_ON_NAS_PENDING_DESKTOP -> Color(0xFF2196F3)
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_NOT_FEASIBLE,
                            WishLifecycleStage.WISH_LIFECYCLE_STAGE_WONT_ORDER -> Color(0xFFF44336)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (wish.votersList.isNotEmpty()) {
                    Text("Votes: ${wish.votersList.joinToString()}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        grpcClient.withAuth {
                            grpcClient.wishListService().voteOnWish(voteRequest {
                                wishId = wish.id
                                vote = !wish.userVoted
                            })
                        }
                        onChanged()
                    } catch (_: Exception) { }
                }
            }) {
                Text(if (wish.userVoted) "Unvote" else "Vote")
            }
        }
    }
}

@Composable
private fun TmdbSearchPanel(
    grpcClient: GrpcClient,
    onWishAdded: () -> Unit,
    onBack: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TmdbResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        Column(modifier = Modifier.weight(0.4f).padding(top = 8.dp), horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Text("Add Wish", style = MaterialTheme.typography.headlineSmall)
            }
            TvOutlinedTextField(
                value = query, onValueChange = { query = it },
                label = "Search TMDB...",
                modifier = Modifier.width(300.dp).padding(top = 12.dp)
            )
            Button(onClick = {
                if (query.isNotBlank()) {
                    scope.launch {
                        loading = true
                        try {
                            results = grpcClient.withAuth {
                                grpcClient.wishListService().searchTmdb(tmdbSearchRequest {
                                    this.query = query.trim()
                                    this.type = MediaType.MEDIA_TYPE_UNKNOWN
                                })
                            }.resultsList
                        } catch (_: Exception) { } finally { loading = false }
                    }
                }
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Search") }
        }

        Column(modifier = Modifier.weight(0.6f)) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(results) { result ->
                        Card(
                            onClick = {
                                if (!result.owned && !result.wished) {
                                    scope.launch {
                                        try {
                                            grpcClient.withAuth {
                                                grpcClient.wishListService().addWish(addWishRequest {
                                                    tmdbId = result.tmdbId
                                                    mediaType = result.mediaType
                                                    title = result.title
                                                    if (result.hasPosterUrl()) posterPath = result.posterUrl
                                                    if (result.hasReleaseYear()) releaseYear = result.releaseYear
                                                    if (result.hasPopularity()) popularity = result.popularity
                                                })
                                            }
                                            onWishAdded()
                                        } catch (_: Exception) { }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(result.title, style = MaterialTheme.typography.titleSmall)
                                    if (result.hasReleaseYear()) Text("${result.releaseYear}", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    when {
                                        result.owned -> "Owned"
                                        result.wished -> "Wished"
                                        else -> "Add"
                                    },
                                    color = when {
                                        result.owned -> Color(0xFF4CAF50)
                                        result.wished -> Color(0xFF2196F3)
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
