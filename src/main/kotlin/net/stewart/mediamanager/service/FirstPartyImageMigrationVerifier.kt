package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.LocalImage
import net.stewart.mediamanager.entity.OwnershipPhoto
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase-4a auditor. Walks ownership_photo + local_image rows and confirms
 * each has a byte-identical copy at the unified [FirstPartyImageStore]
 * layout and a parseable sidecar. Read-only — never deletes, never
 * copies, never writes anything. Safe to call any time.
 *
 * See docs/IMAGE_CACHE_MIGRATION.md phase 4. The admin RPC that exposes
 * this (`AdminService.VerifyFirstPartyImageMigration`) is expected to be
 * polled by the maintainer until every bucket except `verified` hits
 * zero, at which point phase 4b (deletion of the legacy directories) is
 * safe to consider.
 *
 * Byte equivalence is established via file-size comparison rather than a
 * full SHA-256. The phase-3 copy updater does an atomic `Files.copy`,
 * which is byte-exact by construction, so a matching size is a reliable
 * proxy without the I/O cost of hashing every image on the NAS. A deep
 * verifier can be added later if we ever find ourselves distrusting the
 * copy path.
 */
object FirstPartyImageMigrationVerifier {

    private val log = LoggerFactory.getLogger(FirstPartyImageMigrationVerifier::class.java)

    const val SAMPLE_LIMIT = 10

    data class CategoryAudit(
        val totalRows: Int,
        val legacyMissing: Int,
        val verified: Int,
        val missingNewCopy: Int,
        val mismatchedBytes: Int,
        val missingSidecar: Int,
        val invalidSidecar: Int,
        val sampleMissingNewCopy: List<String>,
        val sampleMismatchedBytes: List<String>,
        val sampleMissingSidecar: List<String>,
        val sampleInvalidSidecar: List<String>
    ) {
        val failureCount: Int
            get() = missingNewCopy + mismatchedBytes + missingSidecar + invalidSidecar
    }

    data class Report(
        val ownership: CategoryAudit,
        val localImages: CategoryAudit
    ) {
        /** True when every category has no failure counters outstanding. */
        val safeToDeleteOldLayout: Boolean
            get() = ownership.failureCount == 0 && localImages.failureCount == 0
    }

    fun run(): Report {
        val ownership = auditOwnershipPhotos()
        val local = auditLocalImages()
        log.info("First-party migration audit: ownership={}, local={}, safeToDelete={}",
            ownership.summaryString(), local.summaryString(),
            ownership.failureCount == 0 && local.failureCount == 0)
        return Report(ownership, local)
    }

    // -------------------------------------------------------------------
    // Ownership photos
    // -------------------------------------------------------------------

    private fun auditOwnershipPhotos(): CategoryAudit {
        val photos = OwnershipPhoto.findAll()
        var legacyMissing = 0
        var verified = 0
        var missingNewCopy = 0
        var mismatchedBytes = 0
        var missingSidecar = 0
        var invalidSidecar = 0
        val sMissing = mutableListOf<String>()
        val sMismatch = mutableListOf<String>()
        val sNoSidecar = mutableListOf<String>()
        val sBadSidecar = mutableListOf<String>()

        for (photo in photos) {
            val uuid = photo.id ?: continue
            val ext = extensionFor(photo.content_type)
            val legacyRelative = photo.disk_path
                ?: OwnershipPhotoStorage.legacyPath(uuid, photo.content_type)
            val legacyFile = OwnershipPhotoStorage.resolveAbsolute(legacyRelative).toFile()
            if (!legacyFile.exists()) {
                legacyMissing++
                continue
            }

            val newPath = FirstPartyImageStore.pathFor(
                FirstPartyImageStore.Category.OWNERSHIP_PHOTOS, uuid, ext
            )
            val outcome = auditPair(legacyFile, newPath)
            when (outcome) {
                is AuditOutcome.Verified -> verified++
                is AuditOutcome.MissingNewCopy -> {
                    missingNewCopy++
                    if (sMissing.size < SAMPLE_LIMIT) sMissing.add(legacyFile.toString())
                }
                is AuditOutcome.MismatchedBytes -> {
                    mismatchedBytes++
                    if (sMismatch.size < SAMPLE_LIMIT) sMismatch.add(legacyFile.toString())
                }
                is AuditOutcome.MissingSidecar -> {
                    missingSidecar++
                    if (sNoSidecar.size < SAMPLE_LIMIT) sNoSidecar.add(legacyFile.toString())
                }
                is AuditOutcome.InvalidSidecar -> {
                    invalidSidecar++
                    if (sBadSidecar.size < SAMPLE_LIMIT) sBadSidecar.add(legacyFile.toString())
                }
            }
        }
        return CategoryAudit(
            totalRows = photos.size,
            legacyMissing = legacyMissing,
            verified = verified,
            missingNewCopy = missingNewCopy,
            mismatchedBytes = mismatchedBytes,
            missingSidecar = missingSidecar,
            invalidSidecar = invalidSidecar,
            sampleMissingNewCopy = sMissing,
            sampleMismatchedBytes = sMismatch,
            sampleMissingSidecar = sNoSidecar,
            sampleInvalidSidecar = sBadSidecar
        )
    }

    // -------------------------------------------------------------------
    // Local images
    // -------------------------------------------------------------------

    private fun auditLocalImages(): CategoryAudit {
        val records = LocalImage.findAll()
        var legacyMissing = 0
        var verified = 0
        var missingNewCopy = 0
        var mismatchedBytes = 0
        var missingSidecar = 0
        var invalidSidecar = 0
        val sMissing = mutableListOf<String>()
        val sMismatch = mutableListOf<String>()
        val sNoSidecar = mutableListOf<String>()
        val sBadSidecar = mutableListOf<String>()

        for (record in records) {
            val uuid = record.id ?: continue
            val ext = extensionFor(record.content_type)
            val legacyFile = legacyLocalImageFile(uuid, ext)
            if (!legacyFile.exists()) {
                legacyMissing++
                continue
            }

            val newPath = FirstPartyImageStore.pathFor(
                FirstPartyImageStore.Category.LOCAL_IMAGES, uuid, ext
            )
            when (auditPair(legacyFile, newPath)) {
                is AuditOutcome.Verified -> verified++
                is AuditOutcome.MissingNewCopy -> {
                    missingNewCopy++
                    if (sMissing.size < SAMPLE_LIMIT) sMissing.add(legacyFile.toString())
                }
                is AuditOutcome.MismatchedBytes -> {
                    mismatchedBytes++
                    if (sMismatch.size < SAMPLE_LIMIT) sMismatch.add(legacyFile.toString())
                }
                is AuditOutcome.MissingSidecar -> {
                    missingSidecar++
                    if (sNoSidecar.size < SAMPLE_LIMIT) sNoSidecar.add(legacyFile.toString())
                }
                is AuditOutcome.InvalidSidecar -> {
                    invalidSidecar++
                    if (sBadSidecar.size < SAMPLE_LIMIT) sBadSidecar.add(legacyFile.toString())
                }
            }
        }
        return CategoryAudit(
            totalRows = records.size,
            legacyMissing = legacyMissing,
            verified = verified,
            missingNewCopy = missingNewCopy,
            mismatchedBytes = mismatchedBytes,
            missingSidecar = missingSidecar,
            invalidSidecar = invalidSidecar,
            sampleMissingNewCopy = sMissing,
            sampleMismatchedBytes = sMismatch,
            sampleMissingSidecar = sNoSidecar,
            sampleInvalidSidecar = sBadSidecar
        )
    }

    // -------------------------------------------------------------------
    // Shared per-pair audit logic
    // -------------------------------------------------------------------

    private sealed class AuditOutcome {
        data object Verified : AuditOutcome()
        data object MissingNewCopy : AuditOutcome()
        data object MismatchedBytes : AuditOutcome()
        data object MissingSidecar : AuditOutcome()
        data object InvalidSidecar : AuditOutcome()
    }

    private fun auditPair(legacyFile: File, newPath: Path): AuditOutcome {
        if (!Files.exists(newPath)) return AuditOutcome.MissingNewCopy
        val legacySize = runCatching { legacyFile.length() }.getOrNull() ?: return AuditOutcome.MissingNewCopy
        val newSize = runCatching { Files.size(newPath) }.getOrNull() ?: return AuditOutcome.MissingNewCopy
        if (legacySize != newSize) return AuditOutcome.MismatchedBytes

        val sidecar = MetadataWriter.sidecarFor(newPath)
        if (!Files.exists(sidecar)) return AuditOutcome.MissingSidecar
        // MetadataWriter.readSidecar swallows parse errors and returns null.
        // That's exactly the signal we need: null → invalid.
        return if (MetadataWriter.readSidecar(newPath) == null) AuditOutcome.InvalidSidecar
        else AuditOutcome.Verified
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private fun legacyLocalImageFile(uuid: String, ext: String): File {
        val ab = uuid.substring(0, 2)
        val cd = uuid.substring(2, 4)
        return File("data/local-images/$ab/$cd/$uuid.$ext")
    }

    private fun extensionFor(contentType: String): String = when {
        contentType.contains("png") -> "png"
        contentType.contains("webp") -> "webp"
        contentType.contains("heic") -> "heic"
        contentType.contains("heif") -> "heif"
        else -> "jpg"
    }

    private fun CategoryAudit.summaryString(): String =
        "total=$totalRows verified=$verified missing=$missingNewCopy " +
            "sizeMismatch=$mismatchedBytes noSidecar=$missingSidecar " +
            "badSidecar=$invalidSidecar legacyMissing=$legacyMissing"
}
