package net.stewart.mediamanager.tv.live

import android.net.Uri
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.Camera
import net.stewart.mediamanager.grpc.TvChannel
import net.stewart.mediamanager.grpc.empty
import net.stewart.mediamanager.grpc.warmUpStreamRequest
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient

// ── Cameras ──────────────────────────────────────────────────────────

@Composable
fun CamerasScreen(
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onBack: () -> Unit = {}
) {
    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var playingCamera by remember { mutableStateOf<Camera?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            cameras = grpcClient.withAuth {
                grpcClient.liveService().listCameras(empty { })
            }.camerasList
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    if (playingCamera != null) {
        LiveStreamPlayer(
            streamUrl = "${authManager.httpBaseUrl}${playingCamera!!.streamUrl}",
            token = authManager.accessToken?.let { String(it, Charsets.UTF_8) } ?: "",
            title = playingCamera!!.name,
            onBack = { playingCamera = null }
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
            Text("Cameras", style = MaterialTheme.typography.headlineSmall)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            cameras.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No cameras configured") }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cameras) { camera ->
                    Card(onClick = {
                        scope.launch {
                            try {
                                grpcClient.withAuth {
                                    grpcClient.liveService().warmUpStream(
                                        warmUpStreamRequest { path = camera.streamUrl })
                                }
                            } catch (_: Exception) { }
                            playingCamera = camera
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(camera.name, style = MaterialTheme.typography.titleMedium)
                            if (camera.hasLocation()) {
                                Text(camera.location, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Live TV ──────────────────────────────────────────────────────────

@Composable
fun LiveTvScreen(
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onBack: () -> Unit = {}
) {
    var channels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var playingChannel by remember { mutableStateOf<TvChannel?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            channels = grpcClient.withAuth {
                grpcClient.liveService().listTvChannels(empty { })
            }.channelsList
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    if (playingChannel != null) {
        LiveStreamPlayer(
            streamUrl = "${authManager.httpBaseUrl}${playingChannel!!.streamUrl}",
            token = authManager.accessToken?.let { String(it, Charsets.UTF_8) } ?: "",
            title = "${playingChannel!!.number} ${playingChannel!!.name}",
            onBack = { playingChannel = null }
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
            Text("Live TV", style = MaterialTheme.typography.headlineSmall)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No channels available") }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(channels) { channel ->
                    Card(onClick = {
                        scope.launch {
                            try {
                                grpcClient.withAuth {
                                    grpcClient.liveService().warmUpStream(
                                        warmUpStreamRequest { path = channel.streamUrl })
                                }
                            } catch (_: Exception) { }
                            playingChannel = channel
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(channel.number, style = MaterialTheme.typography.titleMedium)
                                Text(channel.name, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(channel.quality.name.removePrefix("QUALITY_"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ── Shared HLS live player ───────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LiveStreamPlayer(
    streamUrl: String,
    token: String,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val dataSourceFactory = remember {
        DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val hlsSource = HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)))
            setMediaSource(hlsSource)
            prepare()
            play()
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            OutlinedButton(onClick = onBack) { Text("Exit") }
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = Color.White, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
