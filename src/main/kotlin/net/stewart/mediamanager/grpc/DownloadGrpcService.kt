package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.service.TranscoderAgent
import java.io.File

class DownloadGrpcService : DownloadServiceGrpcKt.DownloadServiceCoroutineImplBase() {

    override suspend fun listAvailable(request: Empty): DownloadAvailableResponse {
        val user = currentUser()
        val transcodes = Transcode.findAll().filter { it.for_mobile_available }
        val titleIds = transcodes.map { it.title_id }.toSet()
        val titles = TitleEntity.findAll().filter { it.id in titleIds }.associateBy { it.id }

        return downloadAvailableResponse {
            downloads.addAll(transcodes.mapNotNull { tc ->
                val title = titles[tc.title_id] ?: return@mapNotNull null
                if (title.hidden || !user.canSeeRating(title.content_rating)) return@mapNotNull null
                downloadAvailableItem {
                    transcodeId = tc.id!!
                    titleId = title.id!!
                    titleName = title.name
                    mediaType = title.media_type.toProtoMediaType()
                    title.posterUrl(PosterSize.FULL)?.let { posterUrl = it }
                    contentRating = title.content_rating.toProtoContentRating()
                    title.release_year?.let { year = it }
                    quality = tc.media_format.toProtoQuality()
                }
            })
        }
    }

    override suspend fun getManifest(request: ManifestRequest): DownloadManifest {
        return buildManifest(request.transcodeId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Transcode not found"))
    }

    override suspend fun batchManifest(request: BatchManifestRequest): BatchManifestResponse {
        return batchManifestResponse {
            manifests.addAll(request.transcodeIdsList.mapNotNull { buildManifest(it) })
        }
    }

    private fun buildManifest(transcodeId: Long): DownloadManifest? {
        val user = currentUser()
        val tc = Transcode.findById(transcodeId) ?: return null
        if (!tc.for_mobile_available) return null
        val title = TitleEntity.findById(tc.title_id) ?: return null
        if (title.hidden || !user.canSeeRating(title.content_rating)) return null
        val nasRoot = TranscoderAgent.getNasRoot() ?: return null
        val file = TranscoderAgent.getForMobilePath(nasRoot, tc.file_path ?: return null)
        if (!file.exists()) return null

        return downloadManifest {
            this.transcodeId = tc.id!!
            titleId = title.id!!
            titleName = title.name
            fileSizeBytes = file.length()
            quality = tc.media_format.toProtoQuality()
            hasSubtitles = hasSubtitleFile(tc, nasRoot)
            hasThumbnails = TranscoderAgent.findAuxFile(nasRoot, tc.file_path!!, ".thumbnails.vtt") != null
            hasChapters = TranscoderAgent.findAuxFile(nasRoot, tc.file_path!!, ".chapters.json") != null
        }
    }
}
