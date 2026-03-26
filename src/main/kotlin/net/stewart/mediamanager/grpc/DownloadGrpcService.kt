package net.stewart.mediamanager.grpc

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.TranscoderAgent
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile

class DownloadGrpcService : DownloadServiceGrpcKt.DownloadServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(DownloadGrpcService::class.java)
    private val CHUNK_SIZE = 1024 * 1024  // 1MB per chunk

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

    override fun downloadFile(request: DownloadFileRequest): Flow<DownloadChunk> = flow {
        val user = currentUser()
        val tc = Transcode.findById(request.transcodeId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Transcode not found"))
        if (!tc.for_mobile_available) {
            throw StatusException(Status.NOT_FOUND.withDescription("ForMobile not available"))
        }
        val title = TitleEntity.findById(tc.title_id)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Title not found"))
        if (!user.canSeeRating(title.content_rating)) {
            throw StatusException(Status.PERMISSION_DENIED.withDescription("Content restricted"))
        }
        val nasRoot = TranscoderAgent.getNasRoot()
            ?: throw StatusException(Status.UNAVAILABLE.withDescription("NAS not configured"))
        val file = TranscoderAgent.getForMobilePath(nasRoot, tc.file_path!!)
        if (!file.exists()) {
            throw StatusException(Status.NOT_FOUND.withDescription("ForMobile file not found"))
        }

        val totalSize = file.length()
        val startOffset = request.offset.coerceIn(0, totalSize)
        val maxBytes = if (request.length > 0) request.length else (totalSize - startOffset)
        val endOffset = (startOffset + maxBytes).coerceAtMost(totalSize)
        val qualityLabel = tc.media_format.toProtoQuality().name.lowercase().removePrefix("quality_")

        log.info("Download starting: transcode={} file={} offset={} size={}",
            request.transcodeId, file.name, startOffset, totalSize)

        val raf = RandomAccessFile(file, "r")
        try {
            raf.seek(startOffset)
            val buffer = ByteArray(CHUNK_SIZE)
            var position = startOffset
            var totalBytesStreamed = 0L

            while (position < endOffset) {
                val remaining = (endOffset - position).toInt().coerceAtMost(CHUNK_SIZE)
                val bytesRead = raf.read(buffer, 0, remaining)
                if (bytesRead <= 0) break

                val isLast = (position + bytesRead) >= endOffset
                emit(downloadChunk {
                    data = ByteString.copyFrom(buffer, 0, bytesRead)
                    offset = position
                    this.totalSize = totalSize
                    this.isLast = isLast
                })

                position += bytesRead
                totalBytesStreamed += bytesRead
                MetricsRegistry.countDownloadBytes(qualityLabel, bytesRead.toLong())
            }

            MetricsRegistry.countDownloadFile("complete")
            log.info("Download complete: transcode={} bytes={}", request.transcodeId, totalBytesStreamed)
        } catch (e: Exception) {
            MetricsRegistry.countDownloadFile("error")
            log.warn("Download failed: transcode={} error={}", request.transcodeId, e.message)
            throw e
        } finally {
            raf.close()
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
