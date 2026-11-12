package net.stewart.mediamanager.tv.search

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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.SearchResponse
import net.stewart.mediamanager.grpc.SearchResult
import net.stewart.mediamanager.grpc.SearchResultType
import net.stewart.mediamanager.grpc.searchRequest
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.ui.components.TvOutlinedTextField

@Composable
fun SearchScreen(
    grpcClient: GrpcClient,
    onTitleClick: (Long) -> Unit,
    onActorClick: (Int) -> Unit,
    onCollectionClick: (Int) -> Unit,
    onTagClick: (Long) -> Unit,
    onGenreClick: (Long) -> Unit,
    onBack: () -> Unit = {}
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    var searchedQuery by rememberSaveable { mutableStateOf("") }
    var response by remember { mutableStateOf<SearchResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Re-fetch results when returning from navigation (query is saved, response is not)
    LaunchedEffect(searchedQuery) {
        if (searchedQuery.isNotBlank()) {
            loading = true; error = null
            try {
                response = grpcClient.withAuth { grpcClient.catalogService().search(searchRequest { this.query = searchedQuery }) }
            } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Left: search input
        Column(
            modifier = Modifier.weight(0.4f).padding(top = 8.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                androidx.tv.material3.OutlinedButton(onClick = onBack) { Text("Back") }
                Text("Search", style = MaterialTheme.typography.headlineSmall)
            }
            TvOutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = "Search titles, actors...",
                modifier = Modifier.width(300.dp).padding(top = 12.dp)
            )
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        searchedQuery = inputText.trim()
                    }
                },
                modifier = Modifier.padding(top = 8.dp)
            ) { Text("Go") }
        }

        // Right: results
        Column(modifier = Modifier.weight(0.6f)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                response != null -> {
                    val results = response!!.resultsList
                    if (results.isEmpty()) {
                        Text("No results", style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 16.dp))
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(results) { result ->
                                SearchResultCard(result, onTitleClick, onActorClick, onCollectionClick, onTagClick, onGenreClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SearchResult,
    onTitleClick: (Long) -> Unit,
    onActorClick: (Int) -> Unit,
    onCollectionClick: (Int) -> Unit,
    onTagClick: (Long) -> Unit,
    onGenreClick: (Long) -> Unit
) {
    Card(
        onClick = {
            when (result.resultType) {
                SearchResultType.SEARCH_RESULT_TYPE_MOVIE,
                SearchResultType.SEARCH_RESULT_TYPE_SERIES ->
                    if (result.hasTitleId()) onTitleClick(result.titleId)
                SearchResultType.SEARCH_RESULT_TYPE_ACTOR ->
                    if (result.hasTmdbPersonId()) onActorClick(result.tmdbPersonId)
                SearchResultType.SEARCH_RESULT_TYPE_COLLECTION ->
                    if (result.hasTmdbCollectionId()) onCollectionClick(result.tmdbCollectionId)
                SearchResultType.SEARCH_RESULT_TYPE_TAG ->
                    if (result.hasItemId()) onTagClick(result.itemId)
                SearchResultType.SEARCH_RESULT_TYPE_GENRE ->
                    if (result.hasItemId()) onGenreClick(result.itemId)
                else -> {}
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(result.name, style = MaterialTheme.typography.titleSmall)
                if (result.hasYear()) {
                    Text("${result.year}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                when (result.resultType) {
                    SearchResultType.SEARCH_RESULT_TYPE_MOVIE -> "Movie"
                    SearchResultType.SEARCH_RESULT_TYPE_SERIES -> "TV"
                    SearchResultType.SEARCH_RESULT_TYPE_ACTOR -> "Actor"
                    SearchResultType.SEARCH_RESULT_TYPE_COLLECTION -> "Collection"
                    SearchResultType.SEARCH_RESULT_TYPE_TAG -> "Tag"
                    SearchResultType.SEARCH_RESULT_TYPE_GENRE -> "Genre"
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
