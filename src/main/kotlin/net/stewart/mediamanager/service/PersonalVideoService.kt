package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime

object PersonalVideoService {
    private val log = LoggerFactory.getLogger(PersonalVideoService::class.java)

    /**
     * Creates a personal video title and links it to a discovered file.
     * Returns the newly created Title.
     */
    fun createAndLink(
        discoveredFileId: Long,
        name: String,
        eventDate: LocalDate? = null,
        description: String? = null,
        familyMemberIds: List<Long> = emptyList(),
        tagIds: List<Long> = emptyList()
    ): Title {
        val df = DiscoveredFile.findById(discoveredFileId)
            ?: throw IllegalArgumentException("Discovered file $discoveredFileId not found")

        val now = LocalDateTime.now()
        val title = Title(
            name = name.trim(),
            media_type = MediaType.PERSONAL.name,
            enrichment_status = EnrichmentStatus.SKIPPED.name,
            description = description?.trim()?.ifBlank { null },
            event_date = eventDate,
            release_year = eventDate?.year,
            sort_name = name.trim().replace(Regex("^(?:The|A|An)\\s+", RegexOption.IGNORE_CASE), "").trim(),
            created_at = now,
            updated_at = now
        )
        title.save()
        log.info("Personal video title created: id={} name=\"{}\"", title.id, title.name)

        // Create transcode link
        Transcode(
            title_id = title.id!!,
            file_path = df.file_path,
            file_size_bytes = df.file_size_bytes,
            file_modified_at = df.file_modified_at,
            status = TranscodeStatus.COMPLETE.name,
            media_format = MediaFormat.UNKNOWN.name,
            match_method = MatchMethod.MANUAL.name,
            created_at = now,
            updated_at = now
        ).save()

        // Update discovered file status
        df.match_status = DiscoveredFileStatus.LINKED.name
        df.matched_title_id = title.id
        df.match_method = MatchMethod.MANUAL.name
        df.save()

        // Link family members
        for (memberId in familyMemberIds) {
            FamilyMemberService.addMemberToTitle(title.id!!, memberId)
        }

        // Link tags
        for (tagId in tagIds) {
            TagService.addTagToTitle(title.id!!, tagId)
        }

        return title
    }

    /** Returns true if the personal video feature is enabled in app_config. */
    fun isEnabled(): Boolean {
        return AppConfig.findAll()
            .firstOrNull { it.config_key == "personal_video_enabled" }
            ?.config_val == "true"
    }

    /** Returns all personal video titles, sorted by event_date descending (chronological). */
    fun getAllPersonalVideos(): List<Title> {
        return Title.findAll()
            .filter { it.media_type == MediaType.PERSONAL.name }
            .filter { !it.hidden }
            .sortedByDescending { it.event_date }
    }

    /** Returns unmatched discovered files that are classified as PERSONAL. */
    fun getUnlinkedPersonalFiles(): List<DiscoveredFile> {
        return DiscoveredFile.findAll()
            .filter { it.media_type == MediaType.PERSONAL.name }
            .filter { it.match_status == DiscoveredFileStatus.UNMATCHED.name }
            .sortedBy { it.file_name }
    }
}
