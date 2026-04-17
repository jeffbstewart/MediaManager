package net.stewart.mediamanager.tv.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.Episode
import net.stewart.mediamanager.grpc.SkipSegment
import net.stewart.mediamanager.grpc.SkipSegmentType
import net.stewart.mediamanager.grpc.listEpisodesRequest
import net.stewart.mediamanager.grpc.playbackOffset
import net.stewart.mediamanager.grpc.reportProgressRequest
import net.stewart.mediamanager.grpc.transcodeIdRequest
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.log.TvLog

/**
 * @param titleId Required for TV shows to find the next episode. Pass 0 for movies.
 * @param seasonNumber Current episode's season (0 for movies).
 * @param episodeNumber Current episode's number (0 for movies).
 * @param onPlayNext Called with the next episode's transcodeId to chain playback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    transcodeId: Long,
    authManager: AuthManager,
    grpcClient: GrpcClient,
    titleId: Long = 0,
    seasonNumber: Int = 0,
    episodeNumber: Int = 0,
    onPlayNext: ((Long) -> Unit)? = null,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val baseUrl = authManager.httpBaseUrl ?: ""
    val token = authManager.accessToken?.let { String(it, Charsets.UTF_8) } ?: ""

    var skipSegments by remember { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var activeSkip by remember { mutableStateOf<SkipSegment?>(null) }
    var nextEpisode by remember { mutableStateOf<Episode?>(null) }
    var showNextEpisode by remember { mutableStateOf(false) }
    var playbackEnded by remember { mutableStateOf(false) }

    val dataSourceFactory = remember {
        DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
    }

    val mediaItem = remember(transcodeId) {
        val videoUri = "$baseUrl/stream/$transcodeId"
        val subtitleUri = "$baseUrl/stream/$transcodeId/subs.vtt"
        MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUri))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage("en")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
            .build()
    }

    val exoPlayer = remember(transcodeId) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(mediaItem)
                prepare()
            }
    }

    // Listen for playback end + emit lifecycle log events
    DisposableEffect(exoPlayer) {
        val playbackAttrs = mapOf(
            "transcode_id" to transcodeId.toString(),
            "title_id" to titleId.toString(),
            "season" to seasonNumber.toString(),
            "episode" to episodeNumber.toString()
        )
        TvLog.info("playback", "playback started", playbackAttrs)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    playbackEnded = true
                    TvLog.info("playback", "playback ended", playbackAttrs)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                TvLog.info(
                    "playback",
                    if (isPlaying) "playback resumed" else "playback paused",
                    playbackAttrs
                )
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                TvLog.error("playback", "playback error", error, playbackAttrs)
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Load skip segments via gRPC
    LaunchedEffect(transcodeId) {
        try {
            val response = grpcClient.withAuth {
                grpcClient.playbackService().getChapters(
                    transcodeIdRequest { this.transcodeId = transcodeId }
                )
            }
            skipSegments = response.skipSegmentsList
        } catch (_: Exception) { }
    }

    // Find next episode via gRPC (for TV shows)
    LaunchedEffect(titleId, seasonNumber, episodeNumber) {
        if (titleId == 0L || seasonNumber == 0) return@LaunchedEffect
        try {
            val response = grpcClient.withAuth {
                grpcClient.catalogService().listEpisodes(listEpisodesRequest {
                    this.titleId = titleId
                    this.seasonNumber = seasonNumber
                })
            }
            nextEpisode = response.episodesList
                .firstOrNull { it.episodeNumber > episodeNumber && it.playable && it.hasTranscodeId() }
            // TODO: if no next in this season, could check next season
        } catch (_: Exception) { }
    }

    // Resume from saved progress
    LaunchedEffect(transcodeId) {
        try {
            val progress = grpcClient.withAuth {
                grpcClient.playbackService().getProgress(
                    transcodeIdRequest { this.transcodeId = transcodeId }
                )
            }
            if (progress.position.seconds > 0) {
                exoPlayer.seekTo((progress.position.seconds * 1000).toLong())
            }
        } catch (_: Exception) { }
        exoPlayer.play()
    }

    // Report progress every 10 seconds
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            delay(10_000)
            if (exoPlayer.isPlaying) {
                reportProgress(grpcClient, transcodeId, exoPlayer)
            }
        }
    }

    // Track skip segments + show "Next Episode" near end
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            delay(500)
            val posSec = exoPlayer.currentPosition / 1000.0
            val durSec = exoPlayer.duration / 1000.0
            activeSkip = skipSegments.firstOrNull { posSec >= it.start.seconds && posSec < it.end.seconds }
            // Show "Next Episode" in the last 30 seconds
            showNextEpisode = nextEpisode != null && durSec > 0 && (durSec - posSec) < 30
        }
    }

    // Auto-advance on playback end
    LaunchedEffect(playbackEnded) {
        if (playbackEnded && nextEpisode != null && onPlayNext != null) {
            reportProgress(grpcClient, transcodeId, exoPlayer)
            onPlayNext(nextEpisode!!.transcodeId)
        }
    }

    // Report final position and release
    DisposableEffect(exoPlayer) {
        onDispose {
            val posMs = exoPlayer.currentPosition
            val durMs = exoPlayer.duration
            scope.launch {
                try {
                    grpcClient.withAuth {
                        grpcClient.playbackService().reportProgress(reportProgressRequest {
                            this.transcodeId = transcodeId
                            this.position = playbackOffset { seconds = posMs / 1000.0 }
                            if (durMs > 0) this.duration = playbackOffset { seconds = durMs / 1000.0 }
                        })
                    }
                } catch (_: Exception) { }
            }
            exoPlayer.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Skip segment overlay
        activeSkip?.let { segment ->
            val label = when (segment.segmentType) {
                SkipSegmentType.SKIP_SEGMENT_TYPE_INTRO -> "Skip Intro"
                SkipSegmentType.SKIP_SEGMENT_TYPE_CREDITS -> "Skip Credits"
                SkipSegmentType.SKIP_SEGMENT_TYPE_RECAP -> "Skip Recap"
                else -> "Skip"
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 96.dp)
            ) {
                Button(onClick = {
                    exoPlayer.seekTo((segment.end.seconds * 1000).toLong())
                }) {
                    Text(label)
                }
            }
        }

        // Next Episode button (appears in last 30 seconds)
        if (showNextEpisode && nextEpisode != null && onPlayNext != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 48.dp)
            ) {
                val ep = nextEpisode!!
                Button(onClick = {
                    scope.launch { reportProgress(grpcClient, transcodeId, exoPlayer) }
                    onPlayNext(ep.transcodeId)
                }) {
                    Text("Next: E${ep.episodeNumber}" +
                        if (ep.hasName()) " - ${ep.name}" else "")
                }
            }
        }

        // Exit button
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text("Exit")
        }
    }
}

private suspend fun reportProgress(grpcClient: GrpcClient, transcodeId: Long, player: ExoPlayer) {
    val posMs = player.currentPosition
    val durMs = player.duration
    try {
        grpcClient.withAuth {
            grpcClient.playbackService().reportProgress(reportProgressRequest {
                this.transcodeId = transcodeId
                this.position = playbackOffset { seconds = posMs / 1000.0 }
                if (durMs > 0) this.duration = playbackOffset { seconds = durMs / 1000.0 }
            })
        }
    } catch (_: Exception) { }
}
