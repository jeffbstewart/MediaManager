package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TagSourceType
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackTag
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Auto-tag generation from audio-file metadata. Called from
 * [MusicIngestionService] after each track + album write.
 *
 * Value types (genre, style, BPM bucket, decade, time signature) are
 * each mapped to one [Tag] row per canonical value, lazily created
 * with a matching [TagSourceType]. Tags are dedup'd by (source_type,
 * source_key) where [source_key] carries the canonical lowercase form
 * so "Post-Punk" and "post-punk" collapse to one tag.
 *
 * Granularity: always attaches to the track. An album-level tag is
 * then computed as the most-common value across its tracks, preserving
 * the existing tag-page album grid. "Tracks tagged X" at query time
 * unions direct track tags with tracks belonging to X-tagged albums
 * (see [TagService.getTrackIdsForTagsWithInheritance]).
 */
object AutoTagApplicator {

    private val log = LoggerFactory.getLogger(AutoTagApplicator::class.java)

    /**
     * Small synonyms map for the most-common genre/style drift. Seeded
     * deliberately thin — we add pairs as we see them in the wild
     * rather than guessing. Keys and values are already in canonical
     * form (lowercased, collapsed whitespace).
     */
    private val SYNONYMS: Map<String, String> = mapOf(
        "rock and roll" to "rock",
        "rock & roll" to "rock",
        "r&b" to "r and b",
        "rnb" to "r and b",
        "hip hop" to "hip-hop",
        "hiphop" to "hip-hop"
    )

    /** Palette used for auto-created tags. Cycled by hash so repeated runs stay stable. */
    private val AUTO_PALETTE: List<String> = listOf(
        "#6B7280", "#8B5CF6", "#14B8A6", "#F59E0B",
        "#EF4444", "#3B82F6", "#22C55E", "#EC4899"
    )

    data class TrackAutoTagInput(
        val trackId: Long,
        val genres: List<String>,
        val styles: List<String>,
        val bpm: Int?,
        val timeSignature: String?,
        val year: Int?
    )

    /**
     * Attach track-level auto-tags for a single track. Idempotent —
     * running twice on the same input creates the tag rows once and
     * attaches them once. Existing manual tags on the track are left
     * alone; this method only adds.
     */
    fun applyToTrack(input: TrackAutoTagInput) {
        val toAttach = mutableListOf<Pair<TagSourceType, String>>()
        for (g in input.genres) canonicalize(g)?.let { toAttach += TagSourceType.GENRE to it }
        for (s in input.styles) canonicalize(s)?.let { toAttach += TagSourceType.STYLE to it }
        bucketForBpm(input.bpm)?.let { toAttach += TagSourceType.BPM_BUCKET to it }
        decadeFor(input.year)?.let { toAttach += TagSourceType.DECADE to it }
        input.timeSignature
            ?.takeIf { TIME_SIG_RE.matches(it) }
            ?.let { toAttach += TagSourceType.TIME_SIG to it }

        if (toAttach.isEmpty()) return

        val existing = TrackTag.findAll().filter { it.track_id == input.trackId }
            .map { it.tag_id }.toSet()
        val now = LocalDateTime.now()
        for ((source, canonical) in toAttach.distinct()) {
            val tag = findOrCreateTag(source, canonical)
            if (tag.id!! !in existing) {
                TrackTag(
                    track_id = input.trackId,
                    tag_id = tag.id!!,
                    created_at = now
                ).save()
            }
        }
        if (toAttach.isNotEmpty()) SearchIndexService.onTagChanged()
    }

    /**
     * Apply album-level auto-tags by majority vote over its tracks.
     * A value wins the album-level slot when it shows up on strictly
     * more than half of the album's tracks — a conservative threshold
     * so compilations ("Now That's What I Call Music") end up with no
     * dominant genre rather than a misleading one.
     */
    fun applyToAlbum(titleId: Long) {
        val tracks = Track.findAll().filter { it.title_id == titleId }.mapNotNull { it.id }
        if (tracks.isEmpty()) return
        val trackTagLinks = TrackTag.findAll().filter { it.track_id in tracks }
        if (trackTagLinks.isEmpty()) return

        val allTags = Tag.findAll().filter { it.id in trackTagLinks.map { l -> l.tag_id }.toSet() }
            .associateBy { it.id }

        val majorityCutoff = tracks.size / 2
        val dominantTagIds = trackTagLinks
            .groupBy { it.tag_id }
            .filter { (tagId, links) ->
                val tag = allTags[tagId] ?: return@filter false
                // Only propagate auto-tag source types; manually-added
                // track tags shouldn't leak up to the album.
                tag.source_type in PROPAGATING_SOURCES
                    && links.map { it.track_id }.distinct().size > majorityCutoff
            }
            .keys

        val existingTitleTagIds = TitleTag.findAll().filter { it.title_id == titleId }
            .map { it.tag_id }.toSet()
        val now = LocalDateTime.now()
        for (tagId in dominantTagIds) {
            if (tagId !in existingTitleTagIds) {
                TitleTag(title_id = titleId, tag_id = tagId, created_at = now).save()
            }
        }
        if (dominantTagIds.any { it !in existingTitleTagIds }) {
            SearchIndexService.onTagChanged()
        }
    }

    // ----------------------------- helpers ---------------------------

    private val PROPAGATING_SOURCES = setOf(
        TagSourceType.GENRE.name,
        TagSourceType.STYLE.name,
        TagSourceType.DECADE.name
        // BPM_BUCKET + TIME_SIG intentionally do NOT propagate — a
        // single album can span BPM buckets, and time signature
        // certainly varies track-to-track on classical albums.
    )

    private val TIME_SIG_RE = Regex("""^\d{1,2}/\d{1,2}$""")

    /**
     * Produce the canonical form of a raw genre/style string. Returns
     * null for inputs that shouldn't become tags (empty, "unknown",
     * too long). Runs the synonyms map on the canonical key before
     * returning so two spellings end up on the same Tag row.
     */
    internal fun canonicalize(raw: String): String? {
        val cleaned = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank()) return null
        if (cleaned.length > 64) return null
        if (cleaned in BLOCKLIST) return null
        return SYNONYMS[cleaned] ?: cleaned
    }

    private val BLOCKLIST = setOf("unknown", "other", "n/a", "none", "no genre")

    /** "120-140" etc. Returns null for unreasonable BPM values. */
    internal fun bucketForBpm(bpm: Int?): String? {
        if (bpm == null || bpm < 1 || bpm > 400) return null
        val lo = (bpm / 20) * 20
        val hi = lo + 20
        return when {
            bpm < 60  -> "<60"
            bpm >= 180 -> "180+"
            else -> "$lo-$hi"
        }
    }

    /** "1980s", "2010s", etc. Returns null for null / implausible years. */
    internal fun decadeFor(year: Int?): String? {
        if (year == null || year < 1900 || year > 2100) return null
        val d = (year / 10) * 10
        return "${d}s"
    }

    /**
     * Find-or-create a tag for the given (source, canonicalKey). Lookup
     * order:
     *
     * 1. Exact match on (source_type, source_key) — the canonical path
     *    for auto-tags we've already created.
     * 2. Case-insensitive match on the display name across ALL source
     *    types — "Pop" the GENRE (from TMDB) and "Pop" the STYLE (from
     *    ID3) collapse to one tag the user sees. Avoids colliding with
     *    the UNIQUE constraint on tag.name, and keeps the user-facing
     *    model intuitive.
     * 3. Create a new tag when neither lookup hits.
     */
    internal fun findOrCreateTag(source: TagSourceType, canonicalKey: String): Tag {
        val byKey = Tag.findAll().firstOrNull {
            it.source_type == source.name && it.source_key == canonicalKey
        }
        if (byKey != null) return byKey

        val name = displayNameFor(source, canonicalKey)
        val byName = Tag.findAll().firstOrNull {
            it.name.equals(name, ignoreCase = true)
        }
        if (byName != null) {
            // Reuse the existing row rather than inserting a duplicate.
            // Don't clobber its source_type — it was set by whoever
            // created the tag first and some code paths
            // (autoAssociateOnEnrichment) key off of it.
            return byName
        }

        val color = AUTO_PALETTE[(canonicalKey.hashCode() and 0x7FFFFFFF) % AUTO_PALETTE.size]
        val tag = Tag(
            name = name,
            bg_color = color,
            source_type = source.name,
            source_key = canonicalKey,
            created_at = LocalDateTime.now()
        )
        tag.save()
        log.info("Auto-created tag '{}' ({}) key={}", tag.name, source, canonicalKey)
        return tag
    }

    /** Pretty display name for a just-created tag row. */
    internal fun displayNameFor(source: TagSourceType, canonicalKey: String): String = when (source) {
        TagSourceType.BPM_BUCKET -> "${canonicalKey} BPM"
        TagSourceType.TIME_SIG -> canonicalKey
        TagSourceType.DECADE -> canonicalKey   // already "1980s"
        else -> canonicalKey.split(" ", "-").joinToString(" ") {
            if (it.isEmpty()) it else it.replaceFirstChar { c -> c.uppercaseChar() }
        }
    }
}
