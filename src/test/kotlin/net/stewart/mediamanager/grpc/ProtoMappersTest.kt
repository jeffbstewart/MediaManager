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
}
