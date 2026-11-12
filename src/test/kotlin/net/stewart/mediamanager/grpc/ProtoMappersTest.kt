package net.stewart.mediamanager.grpc

import net.stewart.mediamanager.entity.ContentRating as ContentRatingEnum
import net.stewart.mediamanager.entity.MediaFormat as MediaFormatEnum
import net.stewart.mediamanager.entity.MediaType as MediaTypeEnum
import net.stewart.mediamanager.entity.EnrichmentStatus as EnrichmentStatusEnum
import net.stewart.mediamanager.entity.MatchMethod as MatchMethodEnum
import net.stewart.mediamanager.entity.AcquisitionStatus as AcquisitionStatusEnum
import net.stewart.mediamanager.entity.LeaseStatus as LeaseStatusEnum
import net.stewart.mediamanager.entity.LeaseType as LeaseTypeEnum
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.*

class ProtoMappersTest {

    // ========================================================================
    // Enum conversions
    // ========================================================================

    @Test
    fun `toProtoMediaType maps all values`() {
        assertEquals(MediaType.MEDIA_TYPE_MOVIE, MediaTypeEnum.MOVIE.name.toProtoMediaType())
        assertEquals(MediaType.MEDIA_TYPE_TV, MediaTypeEnum.TV.name.toProtoMediaType())
        assertEquals(MediaType.MEDIA_TYPE_PERSONAL, MediaTypeEnum.PERSONAL.name.toProtoMediaType())
        assertEquals(MediaType.MEDIA_TYPE_UNKNOWN, null.toProtoMediaType())
        assertEquals(MediaType.MEDIA_TYPE_UNKNOWN, "GARBAGE".toProtoMediaType())
    }

    @Test
    fun `toProtoContentRating maps movie ratings`() {
        assertEquals(ContentRating.CONTENT_RATING_G, "G".toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_PG, "PG".toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_PG_13, "PG-13".toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_R, "R".toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_NC_17, "NC-17".toProtoContentRating())
    }

    @Test
    fun `toProtoContentRating maps TV ratings`() {
        assertEquals(ContentRating.CONTENT_RATING_TV_Y, "TV-Y".toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_TV_Y7, "TV-Y7".toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_TV_G, "TV-G".toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_TV_PG, "TV-PG".toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_TV_14, "TV-14".toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_TV_MA, "TV-MA".toProtoContentRating())
    }

    @Test
    fun `toProtoContentRating handles null and unknown`() {
        assertEquals(ContentRating.CONTENT_RATING_UNKNOWN, null.toProtoContentRating())
        assertEquals(ContentRating.CONTENT_RATING_NR, "WEIRD_RATING".toProtoContentRating())
    }

    @Test
    fun `toProtoQuality derives from media format`() {
        assertEquals(Quality.QUALITY_SD, MediaFormatEnum.DVD.name.toProtoQuality())
        assertEquals(Quality.QUALITY_FHD, MediaFormatEnum.BLURAY.name.toProtoQuality())
        assertEquals(Quality.QUALITY_FHD, MediaFormatEnum.HD_DVD.name.toProtoQuality())
        assertEquals(Quality.QUALITY_UHD, MediaFormatEnum.UHD_BLURAY.name.toProtoQuality())
        assertEquals(Quality.QUALITY_UNKNOWN, null.toProtoQuality())
    }

    @Test
    fun `toProtoMediaFormat maps all values`() {
        assertEquals(MediaFormat.MEDIA_FORMAT_DVD, MediaFormatEnum.DVD.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_BLURAY, MediaFormatEnum.BLURAY.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_UHD_BLURAY, MediaFormatEnum.UHD_BLURAY.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_HD_DVD, MediaFormatEnum.HD_DVD.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_UNKNOWN, null.toProtoMediaFormat())
    }

    @Test
    fun `toProtoMatchMethod maps all values`() {
        assertEquals(net.stewart.mediamanager.grpc.MatchMethod.MATCH_METHOD_AUTO_EXACT,
            MatchMethodEnum.AUTO_EXACT.name.toProtoMatchMethod())
        assertEquals(net.stewart.mediamanager.grpc.MatchMethod.MATCH_METHOD_AUTO_NORMALIZED,
            MatchMethodEnum.AUTO_NORMALIZED.name.toProtoMatchMethod())
        assertEquals(net.stewart.mediamanager.grpc.MatchMethod.MATCH_METHOD_MANUAL,
            MatchMethodEnum.MANUAL.name.toProtoMatchMethod())
        assertEquals(net.stewart.mediamanager.grpc.MatchMethod.MATCH_METHOD_UNKNOWN,
            null.toProtoMatchMethod())
    }

    @Test
    fun `toProtoEnrichmentStatus maps all values`() {
        assertEquals(EnrichmentStatus.ENRICHMENT_STATUS_PENDING,
            EnrichmentStatusEnum.PENDING.name.toProtoEnrichmentStatus())
        assertEquals(EnrichmentStatus.ENRICHMENT_STATUS_ENRICHED,
            EnrichmentStatusEnum.ENRICHED.name.toProtoEnrichmentStatus())
        assertEquals(EnrichmentStatus.ENRICHMENT_STATUS_FAILED,
            EnrichmentStatusEnum.FAILED.name.toProtoEnrichmentStatus())
        assertEquals(EnrichmentStatus.ENRICHMENT_STATUS_ABANDONED,
            EnrichmentStatusEnum.ABANDONED.name.toProtoEnrichmentStatus())
        assertEquals(EnrichmentStatus.ENRICHMENT_STATUS_UNKNOWN,
            null.toProtoEnrichmentStatus())
    }

    @Test
    fun `toProtoAcquisitionStatus maps all values`() {
        assertEquals(AcquisitionStatus.ACQUISITION_STATUS_OWNED,
            AcquisitionStatusEnum.OWNED.name.toProtoAcquisitionStatus())
        assertEquals(AcquisitionStatus.ACQUISITION_STATUS_ORDERED,
            AcquisitionStatusEnum.ORDERED.name.toProtoAcquisitionStatus())
        assertEquals(AcquisitionStatus.ACQUISITION_STATUS_REJECTED,
            AcquisitionStatusEnum.REJECTED.name.toProtoAcquisitionStatus())
        assertEquals(AcquisitionStatus.ACQUISITION_STATUS_UNKNOWN,
            null.toProtoAcquisitionStatus())
    }

    @Test
    fun `toProtoLeaseStatus maps all values`() {
        assertEquals(LeaseStatus.LEASE_STATUS_CLAIMED, LeaseStatusEnum.CLAIMED.name.toProtoLeaseStatus())
        assertEquals(LeaseStatus.LEASE_STATUS_IN_PROGRESS, LeaseStatusEnum.IN_PROGRESS.name.toProtoLeaseStatus())
        assertEquals(LeaseStatus.LEASE_STATUS_COMPLETED, LeaseStatusEnum.COMPLETED.name.toProtoLeaseStatus())
        assertEquals(LeaseStatus.LEASE_STATUS_FAILED, LeaseStatusEnum.FAILED.name.toProtoLeaseStatus())
        assertEquals(LeaseStatus.LEASE_STATUS_EXPIRED, LeaseStatusEnum.EXPIRED.name.toProtoLeaseStatus())
    }

    @Test
    fun `toProtoLeaseType maps all values including mobile transcode`() {
        assertEquals(net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_TRANSCODE,
            LeaseTypeEnum.TRANSCODE.name.toProtoLeaseType())
        assertEquals(net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_LOW_STORAGE_TRANSCODE,
            LeaseTypeEnum.MOBILE_TRANSCODE.name.toProtoLeaseType())
    }

    // ========================================================================
    // Rating level mapping
    // ========================================================================

    @Test
    fun `toProtoRatingLevel maps ordinal levels correctly`() {
        assertEquals(RatingLevel.RATING_LEVEL_CHILDREN, 0.toProtoRatingLevel())
        assertEquals(RatingLevel.RATING_LEVEL_CHILDREN, 1.toProtoRatingLevel())
        assertEquals(RatingLevel.RATING_LEVEL_CHILDREN, 2.toProtoRatingLevel())
        assertEquals(RatingLevel.RATING_LEVEL_GENERAL, 3.toProtoRatingLevel())
        assertEquals(RatingLevel.RATING_LEVEL_TEEN, 4.toProtoRatingLevel())
        assertEquals(RatingLevel.RATING_LEVEL_MATURE, 5.toProtoRatingLevel())
        assertEquals(RatingLevel.RATING_LEVEL_ADULT, 6.toProtoRatingLevel())
        assertEquals(RatingLevel.RATING_LEVEL_UNKNOWN, null.toProtoRatingLevel())
    }

    @Test
    fun `toProtoAccessLevel maps levels`() {
        assertEquals(AccessLevel.ACCESS_LEVEL_VIEWER, 1.toProtoAccessLevel())
        assertEquals(AccessLevel.ACCESS_LEVEL_ADMIN, 2.toProtoAccessLevel())
        assertEquals(AccessLevel.ACCESS_LEVEL_UNKNOWN, 0.toProtoAccessLevel())
    }

    // ========================================================================
    // Time types
    // ========================================================================

    @Test
    fun `toProtoTimestamp converts LocalDateTime to epoch seconds`() {
        val dt = LocalDateTime.of(2025, 6, 15, 12, 30, 0)
        val ts = dt.toProtoTimestamp()
        assertEquals(dt.toEpochSecond(ZoneOffset.UTC), ts.secondsSinceEpoch)
    }

    @Test
    fun `toPlaybackOffset wraps double`() {
        val offset = 123.456.toPlaybackOffset()
        assertEquals(123.456, offset.seconds, 0.001)
    }

    @Test
    fun `toProtoCalendarDate converts LocalDate`() {
        val date = LocalDate.of(2025, 3, 22)
        val cd = date.toProtoCalendarDate()
        assertEquals(2025, cd.year)
        assertEquals(Month.MONTH_MARCH, cd.month)
        assertEquals(22, cd.day)
    }

    // ========================================================================
    // Color
    // ========================================================================

    @Test
    fun `toProtoColor wraps hex string`() {
        val color = "#FF5733".toProtoColor()
        assertEquals("#FF5733", color.hex)
    }

    // ========================================================================
    // Settings key mapping
    // ========================================================================

    @Test
    fun `SettingKey round-trips through config key`() {
        val key = SettingKey.SETTING_KEY_NAS_ROOT_PATH
        val configKey = key.toConfigKey()
        assertEquals("nas_root_path", configKey)
        assertEquals(key, configKey!!.toSettingKey())
    }

    @Test
    fun `UNKNOWN SettingKey maps to null config key`() {
        assertNull(SettingKey.SETTING_KEY_UNKNOWN.toConfigKey())
    }

    @Test
    fun `Unknown config key maps to null SettingKey`() {
        assertNull("nonexistent_key".toSettingKey())
    }

    // ========================================================================
    // toProtoMediaType — book + album coverage missing from the
    // earlier set, plus the reverse direction.
    // ========================================================================

    @Test
    fun `toProtoMediaType maps book and album entries`() {
        assertEquals(MediaType.MEDIA_TYPE_BOOK, MediaTypeEnum.BOOK.name.toProtoMediaType())
        assertEquals(MediaType.MEDIA_TYPE_ALBUM, MediaTypeEnum.ALBUM.name.toProtoMediaType())
    }

    @Test
    fun `toEntityMediaType reverses the mapping`() {
        assertEquals(MediaTypeEnum.MOVIE, MediaType.MEDIA_TYPE_MOVIE.toEntityMediaType())
        assertEquals(MediaTypeEnum.TV, MediaType.MEDIA_TYPE_TV.toEntityMediaType())
        assertEquals(MediaTypeEnum.PERSONAL, MediaType.MEDIA_TYPE_PERSONAL.toEntityMediaType())
        assertEquals(MediaTypeEnum.BOOK, MediaType.MEDIA_TYPE_BOOK.toEntityMediaType())
        assertEquals(MediaTypeEnum.ALBUM, MediaType.MEDIA_TYPE_ALBUM.toEntityMediaType())
        // UNKNOWN falls through to MOVIE — caller must check before
        // round-tripping through the wire.
        assertEquals(MediaTypeEnum.MOVIE, MediaType.MEDIA_TYPE_UNKNOWN.toEntityMediaType())
    }

    @Test
    fun `toEntityMediaFormat reverses for video formats`() {
        assertEquals(MediaFormatEnum.DVD, MediaFormat.MEDIA_FORMAT_DVD.toEntityMediaFormat())
        assertEquals(MediaFormatEnum.BLURAY, MediaFormat.MEDIA_FORMAT_BLURAY.toEntityMediaFormat())
        assertEquals(MediaFormatEnum.UHD_BLURAY, MediaFormat.MEDIA_FORMAT_UHD_BLURAY.toEntityMediaFormat())
        assertEquals(MediaFormatEnum.HD_DVD, MediaFormat.MEDIA_FORMAT_HD_DVD.toEntityMediaFormat())
        // Anything else (book / audio / unknown) falls through to BLURAY
        // — caller must constrain inputs to the video-format subset.
        assertEquals(MediaFormatEnum.BLURAY, MediaFormat.MEDIA_FORMAT_CD.toEntityMediaFormat())
        assertEquals(MediaFormatEnum.BLURAY, MediaFormat.MEDIA_FORMAT_UNKNOWN.toEntityMediaFormat())
    }

    @Test
    fun `toProtoMediaFormat covers book formats`() {
        assertEquals(MediaFormat.MEDIA_FORMAT_MASS_MARKET_PAPERBACK,
            MediaFormatEnum.MASS_MARKET_PAPERBACK.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_TRADE_PAPERBACK,
            MediaFormatEnum.TRADE_PAPERBACK.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_HARDBACK,
            MediaFormatEnum.HARDBACK.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_EBOOK_EPUB,
            MediaFormatEnum.EBOOK_EPUB.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_EBOOK_PDF,
            MediaFormatEnum.EBOOK_PDF.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_AUDIOBOOK_CD,
            MediaFormatEnum.AUDIOBOOK_CD.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_AUDIOBOOK_DIGITAL,
            MediaFormatEnum.AUDIOBOOK_DIGITAL.name.toProtoMediaFormat())
    }

    @Test
    fun `toProtoMediaFormat covers audio formats`() {
        assertEquals(MediaFormat.MEDIA_FORMAT_CD, MediaFormatEnum.CD.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_VINYL_LP, MediaFormatEnum.VINYL_LP.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_AUDIO_FLAC, MediaFormatEnum.AUDIO_FLAC.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_AUDIO_MP3, MediaFormatEnum.AUDIO_MP3.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_AUDIO_AAC, MediaFormatEnum.AUDIO_AAC.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_AUDIO_OGG, MediaFormatEnum.AUDIO_OGG.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_AUDIO_WAV, MediaFormatEnum.AUDIO_WAV.name.toProtoMediaFormat())
        assertEquals(MediaFormat.MEDIA_FORMAT_OTHER, MediaFormatEnum.OTHER.name.toProtoMediaFormat())
    }

    // ========================================================================
    // toProtoEnrichmentStatus — additional values the earlier test omitted.
    // ========================================================================

    @Test
    fun `toProtoEnrichmentStatus maps SKIPPED and REASSIGNMENT_REQUESTED`() {
        assertEquals(EnrichmentStatus.ENRICHMENT_STATUS_SKIPPED,
            EnrichmentStatusEnum.SKIPPED.name.toProtoEnrichmentStatus())
        assertEquals(EnrichmentStatus.ENRICHMENT_STATUS_REASSIGNMENT_REQUESTED,
            EnrichmentStatusEnum.REASSIGNMENT_REQUESTED.name.toProtoEnrichmentStatus())
    }

    // ========================================================================
    // toProtoAcquisitionStatus — additional values.
    // ========================================================================

    @Test
    fun `toProtoAcquisitionStatus maps NOT_AVAILABLE and NEEDS_ASSISTANCE`() {
        assertEquals(AcquisitionStatus.ACQUISITION_STATUS_NOT_AVAILABLE,
            AcquisitionStatusEnum.NOT_AVAILABLE.name.toProtoAcquisitionStatus())
        assertEquals(AcquisitionStatus.ACQUISITION_STATUS_NEEDS_ASSISTANCE,
            AcquisitionStatusEnum.NEEDS_ASSISTANCE.name.toProtoAcquisitionStatus())
    }

    @Test
    fun `toProtoAcquisitionStatus maps explicit UNKNOWN`() {
        // UNKNOWN appears twice in the implementation — once as a
        // mapped name, once as the else branch. Pin both.
        assertEquals(AcquisitionStatus.ACQUISITION_STATUS_UNKNOWN,
            AcquisitionStatusEnum.UNKNOWN.name.toProtoAcquisitionStatus())
        assertEquals(AcquisitionStatus.ACQUISITION_STATUS_UNKNOWN,
            "GARBAGE".toProtoAcquisitionStatus())
    }

    // ========================================================================
    // toProtoLeaseStatus / Type — round out the missing branches.
    // ========================================================================

    @Test
    fun `toProtoLeaseStatus null and unknown fall to UNKNOWN`() {
        assertEquals(LeaseStatus.LEASE_STATUS_UNKNOWN, null.toProtoLeaseStatus())
        assertEquals(LeaseStatus.LEASE_STATUS_UNKNOWN, "WHATEVER".toProtoLeaseStatus())
    }

    @Test
    fun `toProtoLeaseType maps every lease type`() {
        assertEquals(net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_THUMBNAILS,
            LeaseTypeEnum.THUMBNAILS.name.toProtoLeaseType())
        assertEquals(net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_SUBTITLES,
            LeaseTypeEnum.SUBTITLES.name.toProtoLeaseType())
        assertEquals(net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_CHAPTERS,
            LeaseTypeEnum.CHAPTERS.name.toProtoLeaseType())
        assertEquals(net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_UNKNOWN,
            null.toProtoLeaseType())
        assertEquals(net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_UNKNOWN,
            "GARBAGE".toProtoLeaseType())
    }

    // ========================================================================
    // toProtoPersonnelRole — closed set + bucket-into-OTHER for unknowns.
    // ========================================================================

    @Test
    fun `toProtoPersonnelRole maps known roles`() {
        assertEquals(PersonnelRole.PERSONNEL_ROLE_PERFORMER, "performer".toProtoPersonnelRole())
        assertEquals(PersonnelRole.PERSONNEL_ROLE_COMPOSER, "Composer".toProtoPersonnelRole())
        assertEquals(PersonnelRole.PERSONNEL_ROLE_PRODUCER, "PRODUCER".toProtoPersonnelRole())
        assertEquals(PersonnelRole.PERSONNEL_ROLE_ENGINEER, "engineer".toProtoPersonnelRole())
        assertEquals(PersonnelRole.PERSONNEL_ROLE_MIXER, "MIXER".toProtoPersonnelRole())
        assertEquals(PersonnelRole.PERSONNEL_ROLE_OTHER, "other".toProtoPersonnelRole())
    }

    @Test
    fun `toProtoPersonnelRole null returns UNKNOWN`() {
        assertEquals(PersonnelRole.PERSONNEL_ROLE_UNKNOWN, null.toProtoPersonnelRole())
    }

    @Test
    fun `toProtoPersonnelRole buckets unrecognised into OTHER`() {
        // The else branch logs a warning + buckets — verify the
        // bucketing without trying to capture the log.
        assertEquals(PersonnelRole.PERSONNEL_ROLE_OTHER, "Vocalist".toProtoPersonnelRole())
        assertEquals(PersonnelRole.PERSONNEL_ROLE_OTHER, "DJ".toProtoPersonnelRole())
    }

    // ========================================================================
    // toDisplayFormat — every case + the raw-fallback else branch.
    // ========================================================================

    @Test
    fun `toDisplayFormat maps every defined format to a label`() {
        // Pull the enum entries through the mapper to make sure none
        // were forgotten when a new entry was added.
        val labels = mapOf(
            MediaFormatEnum.DVD to "DVD",
            MediaFormatEnum.BLURAY to "Blu-ray",
            MediaFormatEnum.UHD_BLURAY to "4K UHD",
            MediaFormatEnum.HD_DVD to "HD DVD",
            MediaFormatEnum.MASS_MARKET_PAPERBACK to "Paperback",
            MediaFormatEnum.TRADE_PAPERBACK to "Trade paperback",
            MediaFormatEnum.HARDBACK to "Hardcover",
            MediaFormatEnum.EBOOK_EPUB to "EPUB",
            MediaFormatEnum.EBOOK_PDF to "PDF",
            MediaFormatEnum.AUDIOBOOK_CD to "Audiobook (CD)",
            MediaFormatEnum.AUDIOBOOK_DIGITAL to "Audiobook",
            MediaFormatEnum.CD to "CD",
            MediaFormatEnum.VINYL_LP to "Vinyl",
            MediaFormatEnum.AUDIO_FLAC to "FLAC",
            MediaFormatEnum.AUDIO_MP3 to "MP3",
            MediaFormatEnum.AUDIO_AAC to "AAC",
            MediaFormatEnum.AUDIO_OGG to "Ogg Vorbis",
            MediaFormatEnum.AUDIO_WAV to "WAV",
        )
        for ((fmt, expected) in labels) {
            assertEquals(expected, fmt.name.toDisplayFormat(), "format=$fmt")
        }
    }

    @Test
    fun `toDisplayFormat null returns empty string`() {
        assertEquals("", null.toDisplayFormat())
    }

    @Test
    fun `toDisplayFormat returns input verbatim for unknown`() {
        // OTHER and any unmapped string fall through to the raw input.
        assertEquals("OTHER", MediaFormatEnum.OTHER.name.toDisplayFormat())
        assertEquals("UNRECOGNISED", "UNRECOGNISED".toDisplayFormat())
    }

    // ========================================================================
    // toProtoWishLifecycleStage — every stage maps.
    // ========================================================================

    @Test
    fun `toProtoWishLifecycleStage maps every stage`() {
        val pairs = listOf(
            net.stewart.mediamanager.service.WishLifecycleStage.WISHED_FOR to
                net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_WISHED_FOR,
            net.stewart.mediamanager.service.WishLifecycleStage.NOT_FEASIBLE to
                net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_NOT_FEASIBLE,
            net.stewart.mediamanager.service.WishLifecycleStage.WONT_ORDER to
                net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_WONT_ORDER,
            net.stewart.mediamanager.service.WishLifecycleStage.NEEDS_ASSISTANCE to
                net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_NEEDS_ASSISTANCE,
            net.stewart.mediamanager.service.WishLifecycleStage.ORDERED to
                net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_ORDERED,
            net.stewart.mediamanager.service.WishLifecycleStage.IN_HOUSE_PENDING_NAS to
                net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_IN_HOUSE_PENDING_NAS,
            net.stewart.mediamanager.service.WishLifecycleStage.ON_NAS_PENDING_DESKTOP to
                net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_ON_NAS_PENDING_DESKTOP,
            net.stewart.mediamanager.service.WishLifecycleStage.READY_TO_WATCH to
                net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_READY_TO_WATCH,
        )
        for ((entity, expected) in pairs) {
            assertEquals(expected, entity.toProtoWishLifecycleStage(), "stage=$entity")
        }
    }

    // ========================================================================
    // toProtoArtistType / toProtoReleaseGroupType
    // ========================================================================

    @Test
    fun `toProtoArtistType maps known and unknown values`() {
        assertEquals(ArtistType.ARTIST_TYPE_PERSON, "PERSON".toProtoArtistType())
        assertEquals(ArtistType.ARTIST_TYPE_GROUP, "GROUP".toProtoArtistType())
        assertEquals(ArtistType.ARTIST_TYPE_ORCHESTRA, "ORCHESTRA".toProtoArtistType())
        assertEquals(ArtistType.ARTIST_TYPE_CHOIR, "CHOIR".toProtoArtistType())
        assertEquals(ArtistType.ARTIST_TYPE_OTHER, "OTHER".toProtoArtistType())
        assertEquals(ArtistType.ARTIST_TYPE_UNKNOWN, null.toProtoArtistType())
        assertEquals(ArtistType.ARTIST_TYPE_UNKNOWN, "WHATEVER".toProtoArtistType())
    }

    @Test
    fun `toProtoReleaseGroupType is case-insensitive and covers known types`() {
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_ALBUM, "Album".toProtoReleaseGroupType())
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_ALBUM, "album".toProtoReleaseGroupType())
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_EP, "ep".toProtoReleaseGroupType())
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_SINGLE, "Single".toProtoReleaseGroupType())
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_COMPILATION, "Compilation".toProtoReleaseGroupType())
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_SOUNDTRACK, "soundtrack".toProtoReleaseGroupType())
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_LIVE, "Live".toProtoReleaseGroupType())
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_REMIX, "REMIX".toProtoReleaseGroupType())
    }

    @Test
    fun `toProtoReleaseGroupType null and blank fall to UNKNOWN`() {
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_UNKNOWN, null.toProtoReleaseGroupType())
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_UNKNOWN, "".toProtoReleaseGroupType())
    }

    @Test
    fun `toProtoReleaseGroupType buckets unrecognised into OTHER`() {
        // MusicBrainz emits secondary types like "Mixtape/Street",
        // "Demo", "DJ-mix" — anything we don't enumerate buckets
        // into OTHER (distinct from UNKNOWN, which means "no input").
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_OTHER, "WHATEVER".toProtoReleaseGroupType())
        assertEquals(ReleaseGroupType.RELEASE_GROUP_TYPE_OTHER, "Mixtape".toProtoReleaseGroupType())
    }

    // ========================================================================
    // Entity → proto pure-data mappers (no DB).
    // ========================================================================

    @Test
    fun `Genre toProto copies id and name`() {
        val genre = net.stewart.mediamanager.entity.Genre(id = 7, name = "Sci-Fi")
        val proto = genre.toProto()
        assertEquals(7L, proto.id)
        assertEquals("Sci-Fi", proto.name)
    }

    @Test
    fun `Tag toProto copies id name and color`() {
        val tag = net.stewart.mediamanager.entity.Tag(
            id = 3, name = "Comfort Watch", bg_color = "#1B5E20")
        val proto = tag.toProto()
        assertEquals(3L, proto.id)
        assertEquals("Comfort Watch", proto.name)
        assertEquals("#1B5E20", proto.color.hex)
    }

    @Test
    fun `Camera toProto wires same-origin stream and snapshot URLs`() {
        val cam = net.stewart.mediamanager.entity.Camera(
            id = 5, name = "Front Door", rtsp_url = "rtsp://x")
        val proto = cam.toProto()
        assertEquals(5L, proto.id)
        assertEquals("Front Door", proto.name)
        assertEquals("/cam/5/stream.m3u8", proto.streamUrl)
        assertEquals("/cam/5/snapshot.jpg", proto.snapshotUrl)
    }

    @Test
    fun `LiveTvChannel toProto maps reception quality to FHD UHD SD`() {
        // reception_quality is stored as 1..5; the helper buckets:
        //   >=4 → UHD, 3 → FHD, else → SD.
        val uhd = net.stewart.mediamanager.entity.LiveTvChannel(
            id = 1, guide_name = "WTTW HD", guide_number = "5.1", reception_quality = 5)
        assertEquals(Quality.QUALITY_UHD, uhd.toProto().quality)

        val fhd = net.stewart.mediamanager.entity.LiveTvChannel(
            id = 2, guide_name = "WLS", guide_number = "7.1", reception_quality = 3)
        assertEquals(Quality.QUALITY_FHD, fhd.toProto().quality)

        val sd = net.stewart.mediamanager.entity.LiveTvChannel(
            id = 3, guide_name = "WGN", guide_number = "9.1", reception_quality = 1)
        assertEquals(Quality.QUALITY_SD, sd.toProto().quality)
    }

    @Test
    fun `LiveTvChannel toProto carries network affiliation when present`() {
        val withAff = net.stewart.mediamanager.entity.LiveTvChannel(
            id = 1, guide_name = "WTTW", guide_number = "5.1",
            network_affiliation = "PBS", reception_quality = 4)
        assertEquals("PBS", withAff.toProto().networkAffiliation)
    }

    @Test
    fun `LiveTvChannel toProto omits affiliation when blank or null`() {
        val withoutAff = net.stewart.mediamanager.entity.LiveTvChannel(
            id = 1, guide_name = "WTTW", guide_number = "5.1",
            network_affiliation = null, reception_quality = 4)
        // optional string defaults to "" when unset.
        assertEquals("", withoutAff.toProto().networkAffiliation)

        val blank = net.stewart.mediamanager.entity.LiveTvChannel(
            id = 1, guide_name = "WTTW", guide_number = "5.1",
            network_affiliation = "   ", reception_quality = 4)
        assertEquals("", blank.toProto().networkAffiliation)
    }

    @Test
    fun `LiveTvChannel toProto wires same-origin stream URL`() {
        val ch = net.stewart.mediamanager.entity.LiveTvChannel(
            id = 42, guide_name = "ABC", guide_number = "7.1", reception_quality = 4)
        assertEquals("/live-tv-stream/42/stream.m3u8", ch.toProto().streamUrl)
    }
}
