package net.stewart.mediamanager.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import net.stewart.mediamanager.grpc.Carousel
import net.stewart.mediamanager.grpc.HomeFeedResponse
import net.stewart.mediamanager.grpc.Title
import net.stewart.mediamanager.grpc.empty
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.ui.components.PosterCard
import kotlin.math.absoluteValue

private val AVATAR_COLORS = listOf(
    Color(0xFF1565C0), Color(0xFF00838F), Color(0xFF2E7D32),
    Color(0xFF8E24AA), Color(0xFFC62828), Color(0xFFEF6C00),
)

@Composable
fun HomeScreen(
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onSwitchAccount: () -> Unit = {}
) {
    var feed by remember { mutableStateOf<HomeFeedResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            feed = grpcClient.catalogService().homeFeed(empty { })
        } catch (e: Exception) {
            error = e.message ?: "Failed to load home feed"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── Profile bar ──
        ProfileBar(
            username = authManager.activeUsername ?: "",
            onProfileClick = onSwitchAccount
        )

        // ── Content ──
        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Could not load home feed",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            else -> {
                val carousels = feed?.carouselsList ?: emptyList()
                if (carousels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No content yet", style = MaterialTheme.typography.headlineSmall)
                    }
                } else {
                    HomeFeedContent(
                        carousels = carousels,
                        baseUrl = authManager.httpBaseUrl ?: ""
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileBar(username: String, onProfileClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Media Manager",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Search placeholder
            OutlinedButton(onClick = { /* TODO: navigate to search */ }) {
                Text("Search...")
            }

            // Profile / switch account
            OutlinedButton(onClick = onProfileClick) {
                val color = AVATAR_COLORS[username.hashCode().absoluteValue % AVATAR_COLORS.size]
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = username.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    text = username,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeFeedContent(carousels: List<Carousel>, baseUrl: String) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items(carousels) { carousel ->
            CarouselRow(
                title = carousel.name,
                items = carousel.itemsList,
                baseUrl = baseUrl
            )
        }
    }
}

@Composable
private fun CarouselRow(
    title: String,
    items: List<Title>,
    baseUrl: String
) {
    Column(modifier = Modifier.padding(start = 48.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items) { title ->
                PosterCard(
                    title = title,
                    baseUrl = baseUrl,
                    onClick = { /* TODO: navigate to detail */ }
                )
            }
        }
    }
}
