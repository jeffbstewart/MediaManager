package net.stewart.mediamanager.grpc

import com.google.protobuf.ByteString
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
    else -> MediaFormat.MEDIA_FORMAT_UNKNOWN
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
    familyNames: List<String>? = null
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
    this@toProto.profile_path?.let { headshotUrl = "/headshots/${this@toProto.tmdb_person_id}" }
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
    this@toProfileResponse.rating_ceiling?.let {
        ratingCeiling = it.toProtoRatingLevel()
        ratingCeilingLabel = ContentRatingEnum.ceilingLabel(it)
    }
    liveTvMinQuality = when (this@toProfileResponse.live_tv_min_quality) {
        1 -> Quality.QUALITY_SD
        2 -> Quality.QUALITY_FHD
        3 -> Quality.QUALITY_UHD
        else -> Quality.QUALITY_UNKNOWN
    }
    subtitlesEnabled = this@toProfileResponse.subtitles_enabled
    mustChangePassword = this@toProfileResponse.must_change_password
}

fun AppUser.toUserInfo(): UserInfo = userInfo {
    id = this@toUserInfo.id!!
    username = this@toUserInfo.username
    this@toUserInfo.display_name.takeIf { it.isNotBlank() }?.let { displayName = it }
    accessLevel = this@toUserInfo.access_level.toProtoAccessLevel()
    locked = this@toUserInfo.locked
    mustChangePassword = this@toUserInfo.must_change_password
    this@toUserInfo.rating_ceiling?.let { ratingCeiling = it.toProtoRatingLevel() }
}

// ============================================================================
// Live
// ============================================================================

fun CameraEntity.toProto(): net.stewart.mediamanager.grpc.Camera = camera {
    id = this@toProto.id!!
    name = this@toProto.name
    streamUrl = "/cam/${this@toProto.go2rtc_name}/stream.m3u8"
    snapshotUrl = "/cam/${this@toProto.go2rtc_name}/snapshot.jpg"
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
    SettingKey.SETTING_KEY_LIVE_TV_IDLE_TIMEOUT_SECONDS to "live_tv_idle_timeout_seconds"
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
    url = "/ownership-photos/${this@toProtoPhotoInfo.id}"
    this@toProtoPhotoInfo.captured_at?.let { capturedAt = it.toProtoTimestamp() }
}
