package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title

/**
 * Shared validation for admin format-change actions. Used by both the
 * HTTP handler in MediaItemEditHttpService and the gRPC
 * SetMediaItemFormat handler in AdminGrpcService — keeps the typing
 * rules in one place so web and iOS callers can't drift apart.
 *
 * Rule: the new format must be compatible with the MediaItem's
 * linked title's media_type. Book titles take book formats, album
 * titles take music formats, video titles take disc formats.
 */
object MediaFormatSwitcher {

    data class Validation(
        val ok: Boolean,
        val reason: String? = null,
        /**
         * When true, the caller should null out replacement_value +
         * replacement_value_updated_at on the item so the Keepa agent
         * re-prices under the new format (hardcover to paperback is a
         * real price delta).
         */
        val clearPrice: Boolean = false
    )

    fun validate(item: MediaItem, newFormatRaw: String): Validation {
        val newFormat = try {
            MediaFormat.valueOf(newFormatRaw)
        } catch (_: IllegalArgumentException) {
            return Validation(ok = false, reason = "Unknown media_format: $newFormatRaw")
        }

        val titleIds = MediaItemTitle.findAll()
            .filter { it.media_item_id == item.id }
            .map { it.title_id }
        val mediaTypes = Title.findAll()
            .filter { it.id in titleIds }
            .map { it.media_type }
            .toSet()

        // An un-linked media item (no title yet) accepts any format.
        // Multi-title items must agree — we don't allow a format change
        // when the linked titles span media types (shouldn't happen in
        // practice but worth guarding).
        if (mediaTypes.size > 1) {
            return Validation(ok = false,
                reason = "MediaItem links titles of multiple types (${mediaTypes.joinToString()}); " +
                    "format change would be ambiguous.")
        }
        val mediaType = mediaTypes.firstOrNull() ?: return Validation(ok = true, clearPrice = true)

        val allowedByType: Set<MediaFormat> = when (mediaType) {
            MediaType.BOOK.name -> MediaFormat.BOOK_FORMATS
            MediaType.ALBUM.name ->
                MediaFormat.PHYSICAL_MUSIC_FORMATS + MediaFormat.DIGITAL_AUDIO_FORMATS
            MediaType.MOVIE.name, MediaType.TV.name, MediaType.PERSONAL.name ->
                setOf(MediaFormat.DVD, MediaFormat.BLURAY, MediaFormat.UHD_BLURAY, MediaFormat.HD_DVD)
            else -> emptySet()
        }

        if (newFormat !in allowedByType) {
            val allowedNames = allowedByType.joinToString { it.name }
            return Validation(ok = false,
                reason = "Format $newFormatRaw is not valid for a $mediaType title. " +
                    "Allowed formats: $allowedNames")
        }

        val previous = runCatching { MediaFormat.valueOf(item.media_format) }.getOrNull()
        val formatReallyChanged = previous != newFormat
        return Validation(
            ok = true,
            clearPrice = formatReallyChanged
        )
    }

    /**
     * Formats an admin can choose from for a given title media_type.
     * Used by the UI to populate the dropdown and by tests that want
     * a stable allow-list. Digital formats are excluded from the UI
     * (they come from ingestion file extensions, not user choice).
     */
    fun editableFormatsFor(mediaType: String?): List<String> = when (mediaType) {
        MediaType.BOOK.name -> listOf(
            MediaFormat.MASS_MARKET_PAPERBACK.name,
            MediaFormat.TRADE_PAPERBACK.name,
            MediaFormat.HARDBACK.name,
            MediaFormat.AUDIOBOOK_CD.name
        )
        MediaType.ALBUM.name -> listOf(
            MediaFormat.CD.name,
            MediaFormat.VINYL_LP.name
        )
        MediaType.MOVIE.name, MediaType.TV.name, MediaType.PERSONAL.name -> listOf(
            MediaFormat.DVD.name,
            MediaFormat.BLURAY.name,
            MediaFormat.UHD_BLURAY.name,
            MediaFormat.HD_DVD.name
        )
        else -> emptyList()
    }
}
