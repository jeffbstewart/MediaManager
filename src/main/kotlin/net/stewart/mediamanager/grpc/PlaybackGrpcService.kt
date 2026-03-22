package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.Chapter
import net.stewart.mediamanager.entity.SkipSegment
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.service.PlaybackProgressService

class PlaybackGrpcService : PlaybackServiceGrpcKt.PlaybackServiceCoroutineImplBase() {

    override suspend fun getProgress(request: TranscodeIdRequest): PlaybackProgress {
        val user = currentUser()
        val progress = PlaybackProgressService.getProgressForUser(user.id!!, request.transcodeId)
            ?: return playbackProgress { transcodeId = request.transcodeId }
        return progress.toProto()
    }

    override suspend fun reportProgress(request: ReportProgressRequest): Empty {
        val user = currentUser()
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
        val progress = PlaybackProgressService.getProgressForUser(user.id!!, request.transcodeId)
        progress?.delete()
        return Empty.getDefaultInstance()
    }

    override suspend fun getChapters(request: TranscodeIdRequest): ChaptersResponse {
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
