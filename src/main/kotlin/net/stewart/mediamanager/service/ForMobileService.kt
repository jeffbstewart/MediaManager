package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Transcode
import org.slf4j.LoggerFactory

/**
 * Manages ForMobile transcode availability.
 *
 * On startup, reconciles the `for_mobile_available` flag on all transcodes
 * by checking whether the ForMobile MP4 file exists on disk.
 */
object ForMobileService {

    private val log = LoggerFactory.getLogger(ForMobileService::class.java)

    /**
     * Reconciles for_mobile_available flags against actual files on disk.
     * Sets the flag true if the ForMobile MP4 exists, false if it doesn't.
     */
    fun reconcile() {
        val nasRoot = TranscoderAgent.getNasRoot() ?: return
        val transcodes = Transcode.findAll().filter { it.file_path != null }
        var setTrue = 0
        var setFalse = 0

        for (tc in transcodes) {
            val exists = TranscoderAgent.isMobileTranscoded(nasRoot, tc.file_path!!)
            if (exists && !tc.for_mobile_available) {
                tc.for_mobile_available = true
                tc.save()
                setTrue++
            } else if (!exists && tc.for_mobile_available) {
                tc.for_mobile_available = false
                tc.save()
                setFalse++
            }
        }

        if (setTrue > 0 || setFalse > 0) {
            log.info("ForMobile reconciliation: {} set available, {} cleared", setTrue, setFalse)
        }
    }
}
