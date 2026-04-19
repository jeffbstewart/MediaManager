package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.Chapter
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.SkipSegment
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.service.ListeningProgressService
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.ReadingProgressService

class PlaybackGrpcService : PlaybackServiceGrpcKt.PlaybackServiceCoroutineImplBase() {

    /** Verifies the user can access the title associated with a transcode. */
    private fun requireAccessToTranscode(transcodeId: Long) {
        val user = currentUser()
        val transcode = Transcode.findById(transcodeId) ?: return // no transcode = no data, safe
        val title = TitleEntity.findById(transcode.title_id) ?: return
        if (title.hidden || !user.canSeeRating(title.content_rating)) {
            throw StatusException(Status.NOT_FOUND.withDescription("Transcode not found"))
        }
    }

    override suspend fun getProgress(request: TranscodeIdRequest): PlaybackProgress {
        val user = currentUser()
        requireAccessToTranscode(request.transcodeId)
        val progress = PlaybackProgressService.getProgressForUser(user.id!!, request.transcodeId)
            ?: return playbackProgress { transcodeId = request.transcodeId }
        return progress.toProto()
    }

    override suspend fun reportProgress(request: ReportProgressRequest): Empty {
        val user = currentUser()
        requireAccessToTranscode(request.transcodeId)
        PlaybackProgressService.recordProgressForUser(
            user.id!!,
            request.transcodeId,
            request.position.seconds,
            if (request.hasDuration()) request.duration.seconds else null
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun clearProgress(request: TranscodeIdRequest): Empty {
        val user = currentUser()
        requireAccessToTranscode(request.transcodeId)
        val progress = PlaybackProgressService.getProgressForUser(user.id!!, request.transcodeId)
        progress?.delete()
        return Empty.getDefaultInstance()
    }

    // --- Audio listening progress ---

    override suspend fun getListeningProgress(request: ListeningProgressRequest): ListeningProgress {
        val user = currentUser()
        val trackId = resolveListeningTrackId(request.hasTrackId(), request.trackId,
            request.hasMediaItemId(), request.mediaItemId)
        requireAccessToTrack(trackId)
        val progress = ListeningProgressService.get(user.id!!, trackId)
            ?: return listeningProgress { this.trackId = trackId }
        return progress.toProto()
    }

    override suspend fun reportListeningProgress(request: ReportListeningProgressRequest): Empty {
        val user = currentUser()
        val trackId = resolveListeningTrackId(request.hasTrackId(), request.trackId,
            request.hasMediaItemId(), request.mediaItemId)
        requireAccessToTrack(trackId)
        ListeningProgressService.save(
            user.id!!,
            trackId,
            request.position.seconds.toInt(),
            if (request.hasDuration()) request.duration.seconds.toInt() else null
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun clearListeningProgress(request: ListeningProgressRequest): Empty {
        val user = currentUser()
        val trackId = resolveListeningTrackId(request.hasTrackId(), request.trackId,
            request.hasMediaItemId(), request.mediaItemId)
        requireAccessToTrack(trackId)
        ListeningProgressService.delete(user.id!!, trackId)
        return Empty.getDefaultInstance()
    }

    // --- Ebook reading progress ---

    override suspend fun getReadingProgress(request: ReadingProgressRequest): ReadingProgress {
        val user = currentUser()
        requireAccessToMediaItem(request.mediaItemId)
        val progress = ReadingProgressService.get(user.id!!, request.mediaItemId)
            ?: return readingProgress {
                mediaItemId = request.mediaItemId
                locator = ""
            }
        return progress.toProto()
    }

    override suspend fun reportReadingProgress(request: ReportReadingProgressRequest): Empty {
        val user = currentUser()
        requireAccessToMediaItem(request.mediaItemId)
        val fraction = if (request.hasFraction()) request.fraction else 0.0
        ReadingProgressService.save(user.id!!, request.mediaItemId, request.locator, fraction)
        return Empty.getDefaultInstance()
    }

    override suspend fun clearReadingProgress(request: ReadingProgressRequest): Empty {
        val user = currentUser()
        requireAccessToMediaItem(request.mediaItemId)
        ReadingProgressService.delete(user.id!!, request.mediaItemId)
        return Empty.getDefaultInstance()
    }

    // Audio listening progress keys off track_id. Audiobook clients could
    // eventually pass media_item_id; no bridge exists yet, so we reject it
    // with UNIMPLEMENTED rather than silently dropping writes.
    private fun resolveListeningTrackId(
        hasTrackId: Boolean, trackId: Long,
        hasMediaItemId: Boolean, mediaItemId: Long
    ): Long {
        if (hasTrackId && trackId > 0) return trackId
        if (hasMediaItemId && mediaItemId > 0) {
            throw StatusException(
                Status.UNIMPLEMENTED.withDescription(
                    "Audiobook listening progress via media_item_id not yet supported"
                )
            )
        }
        throw StatusException(Status.INVALID_ARGUMENT.withDescription("track_id required"))
    }

    private fun requireAccessToTrack(trackId: Long) {
        val user = currentUser()
        val track = Track.findById(trackId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Track not found"))
        val album = TitleEntity.findById(track.title_id)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Album not found"))
        if (album.hidden || !user.canSeeRating(album.content_rating)) {
            throw StatusException(Status.NOT_FOUND.withDescription("Track not found"))
        }
    }

    private fun requireAccessToMediaItem(mediaItemId: Long) {
        val user = currentUser()
        MediaItem.findById(mediaItemId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Book not found"))
        // media_item ↔ title is many-to-many via media_item_title. A book
        // edition typically has exactly one title; if any of them is
        // blocked by the user's rating ceiling, deny.
        val titleIds = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
            .filter { it.media_item_id == mediaItemId }
            .map { it.title_id }
        if (titleIds.isEmpty()) {
            throw StatusException(Status.NOT_FOUND.withDescription("Book not found"))
        }
        val titles = TitleEntity.findAll().filter { it.id in titleIds }
        if (titles.any { it.hidden || !user.canSeeRating(it.content_rating) }) {
            throw StatusException(Status.NOT_FOUND.withDescription("Book not found"))
        }
    }

    override suspend fun getChapters(request: TranscodeIdRequest): ChaptersResponse {
        requireAccessToTranscode(request.transcodeId)
        val transcode = Transcode.findById(request.transcodeId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Transcode not found"))

        val chapters = Chapter.findAll().filter { it.transcode_id == transcode.id }
        val skipSegments = SkipSegment.findAll().filter { it.transcode_id == transcode.id }

        return chaptersResponse {
            this.chapters.addAll(chapters.sortedBy { it.start_seconds }.map { ch ->
                chapter {
                    title = ch.title ?: ""
                    start = ch.start_seconds.toPlaybackOffset()
                    end = ch.end_seconds.toPlaybackOffset()
                }
            })
            this.skipSegments.addAll(skipSegments.map { ss ->
                skipSegment {
                    segmentType = when (ss.segment_type.lowercase()) {
                        "intro" -> SkipSegmentType.SKIP_SEGMENT_TYPE_INTRO
                        "credits" -> SkipSegmentType.SKIP_SEGMENT_TYPE_CREDITS
                        "recap" -> SkipSegmentType.SKIP_SEGMENT_TYPE_RECAP
                        else -> SkipSegmentType.SKIP_SEGMENT_TYPE_UNKNOWN
                    }
                    start = ss.start_seconds.toPlaybackOffset()
                    end = ss.end_seconds.toPlaybackOffset()
                }
            })
        }
    }
}
