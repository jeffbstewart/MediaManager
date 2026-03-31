package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.DiscoveredFileStatus
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.MatchMethod
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.TranscodeStatus
import java.time.LocalDateTime

/**
 * Links discovered NAS files to catalog titles, creating Transcode and Episode records.
 * Extracted from the deleted TranscodeUnmatchedView for use by Armeria and gRPC services.
 */
object DiscoveredFileLinkService {

    /**
     * Links a discovered file (and all sibling TV episodes with the same parsed show name)
     * to a title. Creates Transcode records and Episode records as needed.
     * Returns the number of files linked.
     */
    fun linkToTitle(file: DiscoveredFile, title: Title): Int {
        val fresh = DiscoveredFile.findById(file.id!!) ?: return 0

        val filesToLink = if (fresh.media_type == MediaType.TV.name && !fresh.parsed_title.isNullOrBlank()) {
            val showName = fresh.parsed_title!!.lowercase()
            DiscoveredFile.findAll().filter {
                it.match_status == DiscoveredFileStatus.UNMATCHED.name &&
                    it.media_type == MediaType.TV.name &&
                    it.parsed_title?.lowercase() == showName
            }
        } else {
            listOf(fresh)
        }

        val now = LocalDateTime.now()
        for (df in filesToLink) {
            var episodeId: Long? = null
            if (df.parsed_season != null && df.parsed_episode != null) {
                val existing = Episode.findAll().firstOrNull {
                    it.title_id == title.id && it.season_number == df.parsed_season &&
                        it.episode_number == df.parsed_episode
                }
                episodeId = if (existing != null) {
                    existing.id
                } else {
                    val ep = Episode(
                        title_id = title.id!!,
                        season_number = df.parsed_season!!,
                        episode_number = df.parsed_episode!!,
                        name = df.parsed_episode_title
                    )
                    ep.save()
                    ep.id
                }
            }

            Transcode(
                title_id = title.id!!,
                episode_id = episodeId,
                file_path = df.file_path,
                file_size_bytes = df.file_size_bytes,
                status = TranscodeStatus.COMPLETE.name,
                media_format = df.media_format,
                match_method = MatchMethod.MANUAL.name,
                created_at = now,
                updated_at = now
            ).save()

            df.match_status = DiscoveredFileStatus.LINKED.name
            df.matched_title_id = title.id
            df.matched_episode_id = episodeId
            df.match_method = MatchMethod.MANUAL.name
            df.save()
        }

        return filesToLink.size
    }
}
