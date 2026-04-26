package net.stewart.mediamanager.grpc

import com.google.protobuf.ByteString
import org.slf4j.LoggerFactory
import net.stewart.mediamanager.service.BarcodeScanService
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.DeviceToken
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.TranscodeLease
import net.stewart.mediamanager.entity.Camera as CameraEntity
import net.stewart.mediamanager.entity.CastMember as CastMemberEntity
import net.stewart.mediamanager.entity.Episode as EpisodeEntity
import net.stewart.mediamanager.entity.Genre as GenreEntity
import net.stewart.mediamanager.entity.PlaybackProgress as ProgressEntity
import net.stewart.mediamanager.entity.RefreshToken as RefreshTokenEntity
import net.stewart.mediamanager.entity.SessionToken as SessionTokenEntity
import net.stewart.mediamanager.entity.Tag as TagEntity
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.entity.Transcode as TranscodeEntity
import net.stewart.mediamanager.entity.ContentRating as ContentRatingEnum
import net.stewart.mediamanager.entity.MediaFormat as MediaFormatEnum
import net.stewart.mediamanager.entity.MediaType as MediaTypeEnum
import net.stewart.mediamanager.entity.EnrichmentStatus as EnrichmentStatusEnum
import net.stewart.mediamanager.entity.MatchMethod as MatchMethodEnum
import net.stewart.mediamanager.entity.AcquisitionStatus as AcquisitionStatusEnum
import net.stewart.mediamanager.entity.LeaseStatus as LeaseStatusEnum
import net.stewart.mediamanager.entity.LeaseType as LeaseTypeEnum
import net.stewart.mediamanager.entity.ArtistType as ArtistTypeEnum
import net.stewart.mediamanager.entity.Artist as ArtistEntity
import net.stewart.mediamanager.entity.Author as AuthorEntity
import net.stewart.mediamanager.entity.Track as TrackEntity
import net.stewart.mediamanager.entity.ListeningProgress as ListeningProgressEntity
import net.stewart.mediamanager.entity.ReadingProgress as ReadingProgressEntity
import net.stewart.mediamanager.service.WishLifecycleStage
import net.stewart.mediamanager.service.TranscoderAgent
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

// ============================================================================
// Time types
// ============================================================================

fun LocalDateTime.toProtoTimestamp(): Timestamp = timestamp {
    secondsSinceEpoch = this@toProtoTimestamp.toEpochSecond(ZoneOffset.UTC)
}

fun Double.toPlaybackOffset(): PlaybackOffset = playbackOffset { seconds = this@toPlaybackOffset }

fun LocalDate.toProtoCalendarDate(): CalendarDate = calendarDate {
    year = this@toProtoCalendarDate.year
    month = Month.forNumber(this@toProtoCalendarDate.monthValue) ?: Month.MONTH_UNKNOWN
    day = this@toProtoCalendarDate.dayOfMonth
}

// ============================================================================
// Color
// ============================================================================

fun String.toProtoColor(): Color = color { hex = this@toProtoColor }

// ============================================================================
// Enum conversions
// ============================================================================

fun String?.toProtoMediaType(): MediaType = when (this) {
    MediaTypeEnum.MOVIE.name -> MediaType.MEDIA_TYPE_MOVIE
    MediaTypeEnum.TV.name -> MediaType.MEDIA_TYPE_TV
    MediaTypeEnum.PERSONAL.name -> MediaType.MEDIA_TYPE_PERSONAL
    MediaTypeEnum.BOOK.name -> MediaType.MEDIA_TYPE_BOOK
    MediaTypeEnum.ALBUM.name -> MediaType.MEDIA_TYPE_ALBUM
    else -> MediaType.MEDIA_TYPE_UNKNOWN
}

fun String?.toProtoContentRating(): ContentRating {
    if (this == null) return ContentRating.CONTENT_RATING_UNKNOWN
    val parsed = ContentRatingEnum.fromTmdbCertification(this) ?: return ContentRating.CONTENT_RATING_NR
    return when (parsed) {
        ContentRatingEnum.G -> ContentRating.CONTENT_RATING_G
        ContentRatingEnum.PG -> ContentRating.CONTENT_RATING_PG
        ContentRatingEnum.PG_13 -> ContentRating.CONTENT_RATING_PG_13
        ContentRatingEnum.R -> ContentRating.CONTENT_RATING_R
        ContentRatingEnum.NC_17 -> ContentRating.CONTENT_RATING_NC_17
        ContentRatingEnum.TV_Y -> ContentRating.CONTENT_RATING_TV_Y
        ContentRatingEnum.TV_Y7 -> ContentRating.CONTENT_RATING_TV_Y7
        ContentRatingEnum.TV_G -> ContentRating.CONTENT_RATING_TV_G
        ContentRatingEnum.TV_PG -> ContentRating.CONTENT_RATING_TV_PG
        ContentRatingEnum.TV_14 -> ContentRating.CONTENT_RATING_TV_14
        ContentRatingEnum.TV_MA -> ContentRating.CONTENT_RATING_TV_MA
    }
}

fun String?.toProtoQuality(): Quality = when (this) {
    MediaFormatEnum.DVD.name -> Quality.QUALITY_SD
    MediaFormatEnum.UHD_BLURAY.name -> Quality.QUALITY_UHD
    MediaFormatEnum.BLURAY.name, MediaFormatEnum.HD_DVD.name -> Quality.QUALITY_FHD
    else -> Quality.QUALITY_UNKNOWN
}

fun String?.toProtoMediaFormat(): MediaFormat = when (this) {
    MediaFormatEnum.DVD.name -> MediaFormat.MEDIA_FORMAT_DVD
    MediaFormatEnum.BLURAY.name -> MediaFormat.MEDIA_FORMAT_BLURAY
    MediaFormatEnum.UHD_BLURAY.name -> MediaFormat.MEDIA_FORMAT_UHD_BLURAY
    MediaFormatEnum.HD_DVD.name -> MediaFormat.MEDIA_FORMAT_HD_DVD
    MediaFormatEnum.MASS_MARKET_PAPERBACK.name -> MediaFormat.MEDIA_FORMAT_MASS_MARKET_PAPERBACK
    MediaFormatEnum.TRADE_PAPERBACK.name -> MediaFormat.MEDIA_FORMAT_TRADE_PAPERBACK
    MediaFormatEnum.HARDBACK.name -> MediaFormat.MEDIA_FORMAT_HARDBACK
    MediaFormatEnum.EBOOK_EPUB.name -> MediaFormat.MEDIA_FORMAT_EBOOK_EPUB
    MediaFormatEnum.EBOOK_PDF.name -> MediaFormat.MEDIA_FORMAT_EBOOK_PDF
    MediaFormatEnum.AUDIOBOOK_CD.name -> MediaFormat.MEDIA_FORMAT_AUDIOBOOK_CD
    MediaFormatEnum.AUDIOBOOK_DIGITAL.name -> MediaFormat.MEDIA_FORMAT_AUDIOBOOK_DIGITAL
    MediaFormatEnum.CD.name -> MediaFormat.MEDIA_FORMAT_CD
    MediaFormatEnum.VINYL_LP.name -> MediaFormat.MEDIA_FORMAT_VINYL_LP
    MediaFormatEnum.AUDIO_FLAC.name -> MediaFormat.MEDIA_FORMAT_AUDIO_FLAC
    MediaFormatEnum.AUDIO_MP3.name -> MediaFormat.MEDIA_FORMAT_AUDIO_MP3
    MediaFormatEnum.AUDIO_AAC.name -> MediaFormat.MEDIA_FORMAT_AUDIO_AAC
    MediaFormatEnum.AUDIO_OGG.name -> MediaFormat.MEDIA_FORMAT_AUDIO_OGG
    MediaFormatEnum.AUDIO_WAV.name -> MediaFormat.MEDIA_FORMAT_AUDIO_WAV
    MediaFormatEnum.OTHER.name -> MediaFormat.MEDIA_FORMAT_OTHER
    else -> MediaFormat.MEDIA_FORMAT_UNKNOWN
}

fun MediaType.toEntityMediaType(): MediaTypeEnum = when (this) {
    MediaType.MEDIA_TYPE_MOVIE -> MediaTypeEnum.MOVIE
    MediaType.MEDIA_TYPE_TV -> MediaTypeEnum.TV
    MediaType.MEDIA_TYPE_PERSONAL -> MediaTypeEnum.PERSONAL
    MediaType.MEDIA_TYPE_BOOK -> MediaTypeEnum.BOOK
    MediaType.MEDIA_TYPE_ALBUM -> MediaTypeEnum.ALBUM
    else -> MediaTypeEnum.MOVIE
}

fun MediaFormat.toEntityMediaFormat(): MediaFormatEnum = when (this) {
    MediaFormat.MEDIA_FORMAT_DVD -> MediaFormatEnum.DVD
    MediaFormat.MEDIA_FORMAT_BLURAY -> MediaFormatEnum.BLURAY
    MediaFormat.MEDIA_FORMAT_UHD_BLURAY -> MediaFormatEnum.UHD_BLURAY
    MediaFormat.MEDIA_FORMAT_HD_DVD -> MediaFormatEnum.HD_DVD
    else -> MediaFormatEnum.BLURAY
}

fun String?.toProtoMatchMethod(): net.stewart.mediamanager.grpc.MatchMethod = when (this) {
    MatchMethodEnum.AUTO_EXACT.name -> net.stewart.mediamanager.grpc.MatchMethod.MATCH_METHOD_AUTO_EXACT
    MatchMethodEnum.AUTO_NORMALIZED.name -> net.stewart.mediamanager.grpc.MatchMethod.MATCH_METHOD_AUTO_NORMALIZED
    MatchMethodEnum.MANUAL.name -> net.stewart.mediamanager.grpc.MatchMethod.MATCH_METHOD_MANUAL
    else -> net.stewart.mediamanager.grpc.MatchMethod.MATCH_METHOD_UNKNOWN
}

fun String?.toProtoEnrichmentStatus(): EnrichmentStatus = when (this) {
    EnrichmentStatusEnum.PENDING.name -> EnrichmentStatus.ENRICHMENT_STATUS_PENDING
    EnrichmentStatusEnum.ENRICHED.name -> EnrichmentStatus.ENRICHMENT_STATUS_ENRICHED
    EnrichmentStatusEnum.SKIPPED.name -> EnrichmentStatus.ENRICHMENT_STATUS_SKIPPED
    EnrichmentStatusEnum.FAILED.name -> EnrichmentStatus.ENRICHMENT_STATUS_FAILED
    EnrichmentStatusEnum.REASSIGNMENT_REQUESTED.name -> EnrichmentStatus.ENRICHMENT_STATUS_REASSIGNMENT_REQUESTED
    EnrichmentStatusEnum.ABANDONED.name -> EnrichmentStatus.ENRICHMENT_STATUS_ABANDONED
    else -> EnrichmentStatus.ENRICHMENT_STATUS_UNKNOWN
}

fun String?.toProtoAcquisitionStatus(): AcquisitionStatus = when (this) {
    AcquisitionStatusEnum.UNKNOWN.name -> AcquisitionStatus.ACQUISITION_STATUS_UNKNOWN
    AcquisitionStatusEnum.NOT_AVAILABLE.name -> AcquisitionStatus.ACQUISITION_STATUS_NOT_AVAILABLE
    AcquisitionStatusEnum.REJECTED.name -> AcquisitionStatus.ACQUISITION_STATUS_REJECTED
    AcquisitionStatusEnum.ORDERED.name -> AcquisitionStatus.ACQUISITION_STATUS_ORDERED
    AcquisitionStatusEnum.OWNED.name -> AcquisitionStatus.ACQUISITION_STATUS_OWNED
    AcquisitionStatusEnum.NEEDS_ASSISTANCE.name -> AcquisitionStatus.ACQUISITION_STATUS_NEEDS_ASSISTANCE
    else -> AcquisitionStatus.ACQUISITION_STATUS_UNKNOWN
}

private val protoMappersLog = LoggerFactory.getLogger("net.stewart.mediamanager.grpc.ProtoMappers")

/**
 * MusicBrainz personnel-credit role string → proto PersonnelRole.
 * Free-form on the storage side; clients want a closed set so they can
 * label + group sections without string-matching. Anything unrecognised
 * buckets into OTHER + a warning, so the log surfaces values worth
 * promoting to first-class enum entries.
 */
fun String?.toProtoPersonnelRole(): PersonnelRole = when (this?.uppercase()) {
    "PERFORMER" -> PersonnelRole.PERSONNEL_ROLE_PERFORMER
    "COMPOSER" -> PersonnelRole.PERSONNEL_ROLE_COMPOSER
    "PRODUCER" -> PersonnelRole.PERSONNEL_ROLE_PRODUCER
    "ENGINEER" -> PersonnelRole.PERSONNEL_ROLE_ENGINEER
    "MIXER" -> PersonnelRole.PERSONNEL_ROLE_MIXER
    "OTHER" -> PersonnelRole.PERSONNEL_ROLE_OTHER
    null -> PersonnelRole.PERSONNEL_ROLE_UNKNOWN
    else -> {
        protoMappersLog.warn("Unrecognised personnel role '{}' bucketed into OTHER", this)
        PersonnelRole.PERSONNEL_ROLE_OTHER
    }
}

/**
 * Display label for a media-format chip ("Blu-ray", "4K UHD", …).
 * Used for the `display_formats` row on TitleDetail. Falls back to the
 * raw entity name for formats that don't have a friendlier display.
 */
fun String?.toDisplayFormat(): String = when (this) {
    MediaFormatEnum.DVD.name -> "DVD"
    MediaFormatEnum.BLURAY.name -> "Blu-ray"
    MediaFormatEnum.UHD_BLURAY.name -> "4K UHD"
    MediaFormatEnum.HD_DVD.name -> "HD DVD"
    MediaFormatEnum.MASS_MARKET_PAPERBACK.name -> "Paperback"
    MediaFormatEnum.TRADE_PAPERBACK.name -> "Trade paperback"
    MediaFormatEnum.HARDBACK.name -> "Hardcover"
    MediaFormatEnum.EBOOK_EPUB.name -> "EPUB"
    MediaFormatEnum.EBOOK_PDF.name -> "PDF"
    MediaFormatEnum.AUDIOBOOK_CD.name -> "Audiobook (CD)"
    MediaFormatEnum.AUDIOBOOK_DIGITAL.name -> "Audiobook"
    MediaFormatEnum.CD.name -> "CD"
    MediaFormatEnum.VINYL_LP.name -> "Vinyl"
    MediaFormatEnum.AUDIO_FLAC.name -> "FLAC"
    MediaFormatEnum.AUDIO_MP3.name -> "MP3"
    MediaFormatEnum.AUDIO_AAC.name -> "AAC"
    MediaFormatEnum.AUDIO_OGG.name -> "Ogg Vorbis"
    MediaFormatEnum.AUDIO_WAV.name -> "WAV"
    null -> ""
    else -> this
}

fun WishLifecycleStage.toProtoWishLifecycleStage(): net.stewart.mediamanager.grpc.WishLifecycleStage = when (this) {
    WishLifecycleStage.WISHED_FOR -> net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_WISHED_FOR
    WishLifecycleStage.NOT_FEASIBLE -> net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_NOT_FEASIBLE
    WishLifecycleStage.WONT_ORDER -> net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_WONT_ORDER
    WishLifecycleStage.NEEDS_ASSISTANCE -> net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_NEEDS_ASSISTANCE
    WishLifecycleStage.ORDERED -> net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_ORDERED
    WishLifecycleStage.IN_HOUSE_PENDING_NAS -> net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_IN_HOUSE_PENDING_NAS
    WishLifecycleStage.ON_NAS_PENDING_DESKTOP -> net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_ON_NAS_PENDING_DESKTOP
    WishLifecycleStage.READY_TO_WATCH -> net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_READY_TO_WATCH
}

fun String?.toProtoLeaseStatus(): LeaseStatus = when (this) {
    LeaseStatusEnum.CLAIMED.name -> LeaseStatus.LEASE_STATUS_CLAIMED
    LeaseStatusEnum.IN_PROGRESS.name -> LeaseStatus.LEASE_STATUS_IN_PROGRESS
    LeaseStatusEnum.COMPLETED.name -> LeaseStatus.LEASE_STATUS_COMPLETED
    LeaseStatusEnum.FAILED.name -> LeaseStatus.LEASE_STATUS_FAILED
    LeaseStatusEnum.EXPIRED.name -> LeaseStatus.LEASE_STATUS_EXPIRED
    else -> LeaseStatus.LEASE_STATUS_UNKNOWN
}

fun String?.toProtoLeaseType(): net.stewart.mediamanager.grpc.LeaseType = when (this) {
    LeaseTypeEnum.TRANSCODE.name -> net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_TRANSCODE
    LeaseTypeEnum.THUMBNAILS.name -> net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_THUMBNAILS
    LeaseTypeEnum.SUBTITLES.name -> net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_SUBTITLES
    LeaseTypeEnum.CHAPTERS.name -> net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_CHAPTERS
    LeaseTypeEnum.MOBILE_TRANSCODE.name -> net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_LOW_STORAGE_TRANSCODE
    else -> net.stewart.mediamanager.grpc.LeaseType.LEASE_TYPE_UNKNOWN
}

fun Int?.toProtoRatingLevel(): RatingLevel = when (this) {
    0, 1 -> RatingLevel.RATING_LEVEL_CHILDREN     // TV-Y, TV-Y7, G, TV-G (ordinal 0-2)
    2 -> RatingLevel.RATING_LEVEL_CHILDREN
    3 -> RatingLevel.RATING_LEVEL_GENERAL          // PG, TV-PG
    4 -> RatingLevel.RATING_LEVEL_TEEN             // PG-13, TV-14
    5 -> RatingLevel.RATING_LEVEL_MATURE           // R, TV-MA
    6 -> RatingLevel.RATING_LEVEL_ADULT            // NC-17
    else -> RatingLevel.RATING_LEVEL_UNKNOWN
}

fun Int.toProtoAccessLevel(): AccessLevel = when {
    this >= 2 -> AccessLevel.ACCESS_LEVEL_ADMIN
    this >= 1 -> AccessLevel.ACCESS_LEVEL_VIEWER
    else -> AccessLevel.ACCESS_LEVEL_UNKNOWN
}

// ============================================================================
// Catalog types
// ============================================================================

private val DIRECT_EXTENSIONS = setOf("mp4", "m4v")
private val TRANSCODE_EXTENSIONS = setOf("mkv", "avi")

fun isPlayable(transcode: TranscodeEntity, nasRoot: String?): Boolean {
    val filePath = transcode.file_path ?: return false
    val ext = File(filePath).extension.lowercase()
    return when {
        ext in DIRECT_EXTENSIONS -> File(filePath).exists()
        ext in TRANSCODE_EXTENSIONS -> nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, filePath)
        else -> false
    }
}

fun hasSubtitleFile(transcode: TranscodeEntity, nasRoot: String?): Boolean {
    val filePath = transcode.file_path ?: return false
    return TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt") != null
}

fun TitleEntity.toProto(
    bestTranscode: TranscodeEntity? = null,
    nasRoot: String? = null,
    familyNames: List<String>? = null,
    artistName: String? = null,
    authorName: String? = null
): Title = title {
    id = this@toProto.id!!
    name = this@toProto.name
    mediaType = this@toProto.media_type.toProtoMediaType()
    this@toProto.release_year?.let { year = it }
    this@toProto.description?.let { description = it }
    this@toProto.posterUrl(PosterSize.FULL)?.let { posterUrl = it }
    this@toProto.backdropUrl()?.let { backdropUrl = it }
    contentRating = this@toProto.content_rating.toProtoContentRating()
    this@toProto.popularity?.let { popularity = it }
    quality = bestTranscode?.media_format.toProtoQuality()
    playable = bestTranscode != null && nasRoot != null && isPlayable(bestTranscode, nasRoot)
    bestTranscode?.id?.let { transcodeId = it }
    this@toProto.tmdb_id?.let { tmdbId = it }
    this@toProto.tmdb_collection_id?.let { tmdbCollectionId = it }
    this@toProto.tmdb_collection_name?.let { tmdbCollectionName = it }
    familyNames?.let { this.familyMembers.addAll(it) }
    highQualityTranscodeAvailable = bestTranscode?.file_path != null
    lowStorageTranscodeAvailable = bestTranscode?.for_mobile_available == true
    artistName?.takeIf { it.isNotBlank() }?.let { this.artistName = it }
    authorName?.takeIf { it.isNotBlank() }?.let { this.authorName = it }
}

fun TranscodeEntity.toProto(
    episode: EpisodeEntity? = null,
    nasRoot: String? = null
): Transcode = transcode {
    id = this@toProto.id!!
    mediaFormat = this@toProto.media_format.toProtoMediaFormat()
    quality = this@toProto.media_format.toProtoQuality()
    this@toProto.episode_id?.let { episodeId = it }
    episode?.season_number?.let { seasonNumber = it }
    episode?.episode_number?.let { episodeNumber = it }
    episode?.name?.let { episodeName = it }
    playable = nasRoot != null && isPlayable(this@toProto, nasRoot)
    hasSubtitles = hasSubtitleFile(this@toProto, nasRoot)
    lowStorageTranscodeAvailable = this@toProto.for_mobile_available
    lowStorageTranscodeRequested = this@toProto.for_mobile_requested
}

fun CastMemberEntity.toProto(): CastMember = castMember {
    tmdbPersonId = this@toProto.tmdb_person_id
    name = this@toProto.name
    this@toProto.character_name?.let { characterName = it }
    this@toProto.profile_path?.let { headshotUrl = "/headshots/${this@toProto.id}" }
    order = this@toProto.cast_order
}

fun GenreEntity.toProto(): Genre = genre {
    id = this@toProto.id!!
    name = this@toProto.name
}

fun TagEntity.toProto(): Tag = tag {
    id = this@toProto.id!!
    name = this@toProto.name
    color = this@toProto.bg_color.toProtoColor()
}

fun ProgressEntity.toProto(): PlaybackProgress = playbackProgress {
    transcodeId = this@toProto.transcode_id
    position = this@toProto.position_seconds.toPlaybackOffset()
    this@toProto.duration_seconds?.let { duration = it.toPlaybackOffset() }
    this@toProto.updated_at?.let { updatedAt = it.toProtoTimestamp() }
}

fun EpisodeEntity.toProtoEpisode(
    transcode: TranscodeEntity?,
    nasRoot: String?,
    resumePos: Double,
    duration: Double?
): Episode = episode {
    episodeId = this@toProtoEpisode.id!!
    transcode?.id?.let { transcodeId = it }
    seasonNumber = this@toProtoEpisode.season_number
    episodeNumber = this@toProtoEpisode.episode_number
    this@toProtoEpisode.name?.let { name = it }
    quality = transcode?.media_format.toProtoQuality()
    playable = transcode != null && nasRoot != null && isPlayable(transcode, nasRoot)
    hasSubtitles = transcode != null && hasSubtitleFile(transcode, nasRoot)
    resumePosition = resumePos.toPlaybackOffset()
    duration?.let { this.duration = it.toPlaybackOffset() }
    lowStorageTranscodeAvailable = transcode?.for_mobile_available == true
    lowStorageTranscodeRequested = transcode?.for_mobile_requested == true
}

// ============================================================================
// Session types
// ============================================================================

fun SessionTokenEntity.toProto(isCurrent: Boolean): SessionInfo = sessionInfo {
    id = this@toProto.id!!
    type = SessionType.SESSION_TYPE_BROWSER
    this@toProto.user_agent.takeIf { it.isNotBlank() }?.let { deviceName = it }
    this.isCurrent = isCurrent
    this@toProto.created_at?.let { createdAt = it.toProtoTimestamp() }
    expiresAt = this@toProto.expires_at.toProtoTimestamp()
    this@toProto.last_used_at?.let { lastUsedAt = it.toProtoTimestamp() }
}

fun RefreshTokenEntity.toProto(isCurrent: Boolean): SessionInfo = sessionInfo {
    id = this@toProto.id!!
    type = SessionType.SESSION_TYPE_APP
    this@toProto.device_name.takeIf { it.isNotBlank() }?.let { deviceName = it }
    this.isCurrent = isCurrent
    this@toProto.created_at?.let { createdAt = it.toProtoTimestamp() }
    expiresAt = this@toProto.expires_at.toProtoTimestamp()
}

fun DeviceToken.toProto(): SessionInfo = sessionInfo {
    id = this@toProto.id!!
    type = SessionType.SESSION_TYPE_DEVICE
    this@toProto.device_name.takeIf { it.isNotBlank() }?.let { deviceName = it }
    isCurrent = false  // device tokens are never "current" for an app session
    this@toProto.created_at?.let { createdAt = it.toProtoTimestamp() }
    this@toProto.last_used_at?.let { lastUsedAt = it.toProtoTimestamp() }
}

// ============================================================================
// User / Profile
// ============================================================================

fun AppUser.toProfileResponse(): ProfileResponse = profileResponse {
    id = this@toProfileResponse.id!!
    username = this@toProfileResponse.username
    this@toProfileResponse.display_name.takeIf { it.isNotBlank() }?.let { displayName = it }
    isAdmin = this@toProfileResponse.isAdmin()
    this@toProfileResponse.ratingCeilingValue?.let {
        ratingCeiling = it.ordinalLevel.toProtoRatingLevel()
        ratingCeilingLabel = it.label
    }
    liveTvMinQuality = when (this@toProfileResponse.live_tv_min_quality) {
        1 -> Quality.QUALITY_SD
        2 -> Quality.QUALITY_FHD
        3 -> Quality.QUALITY_UHD
        else -> Quality.QUALITY_UNKNOWN
    }
    subtitlesEnabled = this@toProfileResponse.subtitles_enabled
    mustChangePassword = this@toProfileResponse.must_change_password
    this@toProfileResponse.privacy_policy_version?.let { privacyPolicyVersion = it }
    this@toProfileResponse.privacy_policy_accepted_at?.let { privacyPolicyAcceptedAt = it.toProtoTimestamp() }
    this@toProfileResponse.terms_of_use_version?.let { termsOfUseVersion = it }
    this@toProfileResponse.terms_of_use_accepted_at?.let { termsOfUseAcceptedAt = it.toProtoTimestamp() }
}

fun AppUser.toUserInfo(): UserInfo = userInfo {
    id = this@toUserInfo.id!!
    username = this@toUserInfo.username
    this@toUserInfo.display_name.takeIf { it.isNotBlank() }?.let { displayName = it }
    accessLevel = this@toUserInfo.access_level.toProtoAccessLevel()
    locked = this@toUserInfo.locked
    mustChangePassword = this@toUserInfo.must_change_password
    this@toUserInfo.ratingCeilingValue?.let { ratingCeiling = it.ordinalLevel.toProtoRatingLevel() }
}

// ============================================================================
// Live
// ============================================================================

fun CameraEntity.toProto(): net.stewart.mediamanager.grpc.Camera = camera {
    id = this@toProto.id!!
    name = this@toProto.name
    streamUrl = "/cam/${this@toProto.id}/stream.m3u8"
    snapshotUrl = "/cam/${this@toProto.id}/snapshot.jpg"
}

fun LiveTvChannel.toProto(): TvChannel = tvChannel {
    id = this@toProto.id!!
    name = this@toProto.guide_name
    number = this@toProto.guide_number
    quality = receptionQualityToProto(this@toProto.reception_quality)
    streamUrl = "/live-tv-stream/${this@toProto.id}/stream.m3u8"
}

private fun receptionQualityToProto(level: Int): Quality = when {
    level >= 4 -> Quality.QUALITY_UHD
    level >= 3 -> Quality.QUALITY_FHD
    else -> Quality.QUALITY_SD
}

// ============================================================================
// Admin: Transcode leases
// ============================================================================

fun TranscodeLease.toActiveLease(): ActiveLease = activeLease {
    leaseId = this@toActiveLease.id!!
    buddyName = this@toActiveLease.buddy_name
    relativePath = this@toActiveLease.relative_path
    leaseType = this@toActiveLease.lease_type.toProtoLeaseType()
    status = this@toActiveLease.status.toProtoLeaseStatus()
    progressPercent = this@toActiveLease.progress_percent
    this@toActiveLease.encoder?.let { encoder = it }
    this@toActiveLease.claimed_at?.let { claimedAt = it.toProtoTimestamp() }
    this@toActiveLease.expires_at?.let { expiresAt = it.toProtoTimestamp() }
}

fun TranscodeLease.toRecentLease(): RecentLease = recentLease {
    leaseId = this@toRecentLease.id!!
    buddyName = this@toRecentLease.buddy_name
    relativePath = this@toRecentLease.relative_path
    leaseType = this@toRecentLease.lease_type.toProtoLeaseType()
    status = this@toRecentLease.status.toProtoLeaseStatus()
    progressPercent = this@toRecentLease.progress_percent
    this@toRecentLease.completed_at?.let { completedAt = it.toProtoTimestamp() }
}

// ============================================================================
// Admin: Settings key mapping
// ============================================================================

private val SETTING_KEY_TO_CONFIG = mapOf(
    SettingKey.SETTING_KEY_NAS_ROOT_PATH to "nas_root_path",
    SettingKey.SETTING_KEY_FFMPEG_PATH to "ffmpeg_path",
    SettingKey.SETTING_KEY_GO2RTC_PATH to "go2rtc_path",
    SettingKey.SETTING_KEY_ROKU_BASE_URL to "roku_base_url",
    SettingKey.SETTING_KEY_GO2RTC_API_PORT to "go2rtc_api_port",
    SettingKey.SETTING_KEY_PERSONAL_VIDEO_ENABLED to "personal_video_enabled",
    SettingKey.SETTING_KEY_FOR_MOBILE_ENABLED to "for_mobile_enabled",
    SettingKey.SETTING_KEY_KEEPA_ENABLED to "keepa_enabled",
    SettingKey.SETTING_KEY_PERSONAL_VIDEO_NAS_DIR to "personal_video_nas_dir",
    SettingKey.SETTING_KEY_BUDDY_LEASE_DURATION_MINUTES to "buddy_lease_duration_minutes",
    SettingKey.SETTING_KEY_KEEPA_API_KEY to "keepa_api_key",
    SettingKey.SETTING_KEY_KEEPA_TOKENS_PER_MINUTE to "keepa_tokens_per_minute",
    SettingKey.SETTING_KEY_LIVE_TV_MIN_RATING to "live_tv_min_rating",
    SettingKey.SETTING_KEY_LIVE_TV_MAX_STREAMS to "live_tv_max_streams",
    SettingKey.SETTING_KEY_LIVE_TV_IDLE_TIMEOUT_SECONDS to "live_tv_idle_timeout_seconds",
    SettingKey.SETTING_KEY_PRIVACY_POLICY_URL to "privacy_policy_url",
    SettingKey.SETTING_KEY_PRIVACY_POLICY_VERSION to "privacy_policy_version",
    SettingKey.SETTING_KEY_IOS_TERMS_OF_USE_URL to "ios_terms_of_use_url",
    SettingKey.SETTING_KEY_IOS_TERMS_OF_USE_VERSION to "ios_terms_of_use_version",
    SettingKey.SETTING_KEY_WEB_TERMS_OF_USE_URL to "web_terms_of_use_url",
    SettingKey.SETTING_KEY_WEB_TERMS_OF_USE_VERSION to "web_terms_of_use_version"
)

private val CONFIG_TO_SETTING_KEY = SETTING_KEY_TO_CONFIG.entries.associate { (k, v) -> v to k }

/** Maps a proto SettingKey to its app_config key string, or null if unknown. */
fun SettingKey.toConfigKey(): String? = SETTING_KEY_TO_CONFIG[this]

/** Maps an app_config key string to a proto SettingKey, or null if not enumerated. */
fun String.toSettingKey(): SettingKey? = CONFIG_TO_SETTING_KEY[this]

// ============================================================================
// Buddy progress events (for server-streaming)
// ============================================================================

fun net.stewart.mediamanager.service.BuddyProgressEvent.toProtoProgressEvent(): TranscodeProgressEvent =
    transcodeProgressEvent {
        leaseId = this@toProtoProgressEvent.leaseId
        buddyName = this@toProtoProgressEvent.buddyName
        relativePath = this@toProtoProgressEvent.relativePath
        status = this@toProtoProgressEvent.status.toProtoLeaseStatus()
        progressPercent = this@toProtoProgressEvent.progressPercent
        this@toProtoProgressEvent.encoder?.let { encoder = it }
    }

// ============================================================================
// Barcode scan mappers
// ============================================================================

fun BarcodeScanService.ScanInfo.toProtoRecentScan(): RecentScan = recentScan {
    scanId = this@toProtoRecentScan.scanId
    upc = this@toProtoRecentScan.upc
    status = this@toProtoRecentScan.status.toProtoScanStatus()
    this@toProtoRecentScan.titleName?.let { titleName = it }
    this@toProtoRecentScan.posterUrl?.let { posterUrl = it }
    this@toProtoRecentScan.titleId?.let { titleId = it }
    this@toProtoRecentScan.scannedAt?.let { scannedAt = it.toProtoTimestamp() }
}

fun BarcodeScanService.CompositeStatus.toProtoScanStatus(): ScanStatus = when (this) {
    BarcodeScanService.CompositeStatus.SUBMITTED -> ScanStatus.SCAN_STATUS_SUBMITTED
    BarcodeScanService.CompositeStatus.UPC_FOUND -> ScanStatus.SCAN_STATUS_UPC_FOUND
    BarcodeScanService.CompositeStatus.UPC_NOT_FOUND -> ScanStatus.SCAN_STATUS_UPC_NOT_FOUND
    BarcodeScanService.CompositeStatus.ENRICHING -> ScanStatus.SCAN_STATUS_ENRICHING
    BarcodeScanService.CompositeStatus.ENRICHED -> ScanStatus.SCAN_STATUS_ENRICHED
    BarcodeScanService.CompositeStatus.ENRICHMENT_FAILED -> ScanStatus.SCAN_STATUS_ENRICHMENT_FAILED
    BarcodeScanService.CompositeStatus.NO_MATCH -> ScanStatus.SCAN_STATUS_NO_MATCH
}

// ============================================================================
// Camera admin mappers
// ============================================================================

fun net.stewart.mediamanager.entity.Camera.toAdminProto(): AdminCamera = adminCamera {
    id = this@toAdminProto.id!!
    name = this@toAdminProto.name
    rtspUrl = net.stewart.mediamanager.service.UriCredentialRedactor.redact(this@toAdminProto.rtsp_url)
    snapshotUrl = net.stewart.mediamanager.service.UriCredentialRedactor.redact(this@toAdminProto.snapshot_url)
    streamName = this@toAdminProto.go2rtc_name
    enabled = this@toAdminProto.enabled
    displayOrder = this@toAdminProto.display_order
}

fun net.stewart.mediamanager.entity.OwnershipPhoto.toProtoPhotoInfo(): OwnershipPhotoInfo = ownershipPhotoInfo {
    photoId = this@toProtoPhotoInfo.id!!
    // Legacy URL field for Android TV — iOS uses ImageService with
    // IMAGE_TYPE_OWNERSHIP_PHOTO + uuid = photo_id instead.
    @Suppress("DEPRECATION")
    url = "/ownership-photos/${this@toProtoPhotoInfo.id}"
    this@toProtoPhotoInfo.captured_at?.let { capturedAt = it.toProtoTimestamp() }
}

fun net.stewart.mediamanager.entity.PasskeyCredential.toProto(): PasskeyCredentialInfo = passkeyCredentialInfo {
    id = this@toProto.id!!
    displayName = this@toProto.display_name
    this@toProto.created_at?.let { createdAt = it.toProtoTimestamp() }
    this@toProto.last_used_at?.let { lastUsedAt = it.toProtoTimestamp() }
}

// ============================================================================
// Audio / Books
// ============================================================================

fun String?.toProtoArtistType(): ArtistType = when (this) {
    ArtistTypeEnum.PERSON.name -> ArtistType.ARTIST_TYPE_PERSON
    ArtistTypeEnum.GROUP.name -> ArtistType.ARTIST_TYPE_GROUP
    ArtistTypeEnum.ORCHESTRA.name -> ArtistType.ARTIST_TYPE_ORCHESTRA
    ArtistTypeEnum.CHOIR.name -> ArtistType.ARTIST_TYPE_CHOIR
    ArtistTypeEnum.OTHER.name -> ArtistType.ARTIST_TYPE_OTHER
    else -> ArtistType.ARTIST_TYPE_UNKNOWN
}

// Maps a MusicBrainz release-group primary-type string to the proto enum.
// Unrecognized values fall through to OTHER so the field carries information.
fun String?.toProtoReleaseGroupType(): ReleaseGroupType = when (this?.lowercase()) {
    "album" -> ReleaseGroupType.RELEASE_GROUP_TYPE_ALBUM
    "ep" -> ReleaseGroupType.RELEASE_GROUP_TYPE_EP
    "single" -> ReleaseGroupType.RELEASE_GROUP_TYPE_SINGLE
    "compilation" -> ReleaseGroupType.RELEASE_GROUP_TYPE_COMPILATION
    "soundtrack" -> ReleaseGroupType.RELEASE_GROUP_TYPE_SOUNDTRACK
    "live" -> ReleaseGroupType.RELEASE_GROUP_TYPE_LIVE
    "remix" -> ReleaseGroupType.RELEASE_GROUP_TYPE_REMIX
    null, "" -> ReleaseGroupType.RELEASE_GROUP_TYPE_UNKNOWN
    else -> ReleaseGroupType.RELEASE_GROUP_TYPE_OTHER
}

fun ArtistEntity.toProto(): Artist = artist {
    id = this@toProto.id!!
    name = this@toProto.name
    this@toProto.sort_name.takeIf { it.isNotBlank() }?.let { sortName = it }
    artistType = this@toProto.artist_type.toProtoArtistType()
    this@toProto.musicbrainz_artist_id?.takeIf { it.isNotBlank() }?.let { musicbrainzArtistId = it }
    this@toProto.begin_date?.year?.let { beginYear = it }
    this@toProto.end_date?.year?.let { endYear = it }
}

fun ArtistEntity.toListItem(
    ownedAlbumCount: Int,
    fallbackAlbumTitleId: Long? = null
): ArtistListItem = artistListItem {
    id = this@toListItem.id!!
    name = this@toListItem.name
    this@toListItem.sort_name.takeIf { it.isNotBlank() }?.let { sortName = it }
    artistType = this@toListItem.artist_type.toProtoArtistType()
    this.ownedAlbumCount = ownedAlbumCount
    fallbackAlbumTitleId?.let { this.fallbackAlbumTitleId = it }
}

fun AuthorEntity.toProto(): Author = author {
    id = this@toProto.id!!
    name = this@toProto.name
    this@toProto.biography?.takeIf { it.isNotBlank() }?.let { biography = it }
    this@toProto.open_library_author_id?.takeIf { it.isNotBlank() }?.let { openlibraryId = it }
    this@toProto.wikidata_id?.takeIf { it.isNotBlank() }?.let { wikidataId = it }
    this@toProto.birth_date?.year?.let { birthYear = it }
    this@toProto.death_date?.year?.let { deathYear = it }
}

fun AuthorEntity.toListItem(ownedBookCount: Int): AuthorListItem = authorListItem {
    id = this@toListItem.id!!
    name = this@toListItem.name
    this.ownedBookCount = ownedBookCount
}

fun TrackEntity.toProto(trackArtistNames: List<String> = emptyList()): Track = track {
    id = this@toProto.id!!
    titleId = this@toProto.title_id
    trackNumber = this@toProto.track_number
    discNumber = this@toProto.disc_number
    name = this@toProto.name
    this@toProto.duration_seconds?.let { duration = it.toDouble().toPlaybackOffset() }
    this@toProto.musicbrainz_recording_id?.takeIf { it.isNotBlank() }?.let { musicbrainzRecordingId = it }
    playable = !this@toProto.file_path.isNullOrBlank()
    if (trackArtistNames.isNotEmpty()) this.trackArtistNames.addAll(trackArtistNames)
    this@toProto.bpm?.let { bpm = it }
    this@toProto.time_signature?.takeIf { it.isNotBlank() }?.let { timeSignature = it }
}

fun ListeningProgressEntity.toProto(): ListeningProgress = listeningProgress {
    trackId = this@toProto.track_id
    position = this@toProto.position_seconds.toDouble().toPlaybackOffset()
    this@toProto.duration_seconds?.let { duration = it.toDouble().toPlaybackOffset() }
    this@toProto.updated_at?.let { updatedAt = it.toProtoTimestamp() }
}

fun net.stewart.mediamanager.entity.MediaItem.toBookEdition(): BookEdition {
    val fmt = this.media_format
    val editionFmt = when (fmt) {
        MediaFormatEnum.EBOOK_EPUB.name -> BookEditionFormat.BOOK_EDITION_FORMAT_EBOOK_EPUB
        MediaFormatEnum.EBOOK_PDF.name -> BookEditionFormat.BOOK_EDITION_FORMAT_EBOOK_PDF
        MediaFormatEnum.AUDIOBOOK_DIGITAL.name -> BookEditionFormat.BOOK_EDITION_FORMAT_AUDIOBOOK_DIGITAL
        MediaFormatEnum.MASS_MARKET_PAPERBACK.name,
        MediaFormatEnum.TRADE_PAPERBACK.name -> BookEditionFormat.BOOK_EDITION_FORMAT_PAPERBACK
        MediaFormatEnum.HARDBACK.name -> BookEditionFormat.BOOK_EDITION_FORMAT_HARDCOVER
        else -> BookEditionFormat.BOOK_EDITION_FORMAT_UNKNOWN
    }
    val isDigital = fmt == MediaFormatEnum.EBOOK_EPUB.name ||
        fmt == MediaFormatEnum.EBOOK_PDF.name ||
        fmt == MediaFormatEnum.AUDIOBOOK_DIGITAL.name
    val size = this.file_path?.let { path ->
        try { java.io.File(path).takeIf { it.exists() }?.length() } catch (_: Exception) { null }
    }
    return bookEdition {
        mediaItemId = this@toBookEdition.id!!
        editionFormat = editionFmt
        size?.let { fileSizeBytes = it }
        this@toBookEdition.storage_location?.takeIf { it.isNotBlank() }?.let { storageLocation = it }
        downloadable = isDigital && !this@toBookEdition.file_path.isNullOrBlank()
    }
}

fun ReadingProgressEntity.toProto(): ReadingProgress = readingProgress {
    mediaItemId = this@toProto.media_item_id
    locator = this@toProto.cfi
    fraction = this@toProto.percent
    this@toProto.updated_at?.let { updatedAt = it.toProtoTimestamp() }
}
