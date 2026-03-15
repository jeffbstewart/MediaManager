package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

data class ScanUpdateEvent(
    val scanId: Long,
    val upc: String,
    val newStatus: String,
    val notes: String?
)

/** Broadcast by TmdbEnrichmentAgent after a Title is enriched or its status changes. */
data class TitleUpdateEvent(
    val titleId: Long,
    val name: String,
    val enrichmentStatus: String
)

data class NasScanProgress(
    val phase: String,       // SCANNING, MATCHING, CLEANUP, COMPLETE, FAILED
    val filesFound: Int = 0,
    val matched: Int = 0,
    val unmatched: Int = 0,
    val deleted: Int = 0,
    val message: String? = null
)

/**
 * Thread-safe event bus connecting background agents to open Vaadin UIs.
 *
 * Background agents (UpcLookupAgent, TmdbEnrichmentAgent, NasScannerService) broadcast events here.
 * Vaadin views register/unregister listeners on attach/detach to receive live updates
 * via server push.
 */
object Broadcaster {
    private val log = LoggerFactory.getLogger(Broadcaster::class.java)

    private val scanListeners = CopyOnWriteArrayList<(ScanUpdateEvent) -> Unit>()
    private val titleListeners = CopyOnWriteArrayList<(TitleUpdateEvent) -> Unit>()
    private val nasScanListeners = CopyOnWriteArrayList<(NasScanProgress) -> Unit>()
    private val transcoderListeners = CopyOnWriteArrayList<(TranscoderProgressEvent) -> Unit>()
    private val buddyListeners = CopyOnWriteArrayList<(BuddyProgressEvent) -> Unit>()

    private fun <T> safeForEach(listeners: List<(T) -> Unit>, event: T) {
        for (listener in listeners) {
            try {
                listener(event)
            } catch (e: Exception) {
                log.warn("Broadcaster listener threw: {}", e.message)
            }
        }
    }

    fun register(listener: (ScanUpdateEvent) -> Unit) { scanListeners.add(listener) }
    fun unregister(listener: (ScanUpdateEvent) -> Unit) { scanListeners.remove(listener) }
    fun broadcast(event: ScanUpdateEvent) { safeForEach(scanListeners, event) }

    fun registerTitleListener(listener: (TitleUpdateEvent) -> Unit) { titleListeners.add(listener) }
    fun unregisterTitleListener(listener: (TitleUpdateEvent) -> Unit) { titleListeners.remove(listener) }
    fun broadcastTitleUpdate(event: TitleUpdateEvent) { safeForEach(titleListeners, event) }

    fun registerNasScanListener(listener: (NasScanProgress) -> Unit) { nasScanListeners.add(listener) }
    fun unregisterNasScanListener(listener: (NasScanProgress) -> Unit) { nasScanListeners.remove(listener) }
    fun broadcastNasScan(event: NasScanProgress) { safeForEach(nasScanListeners, event) }

    fun registerTranscoderListener(listener: (TranscoderProgressEvent) -> Unit) { transcoderListeners.add(listener) }
    fun unregisterTranscoderListener(listener: (TranscoderProgressEvent) -> Unit) { transcoderListeners.remove(listener) }
    fun broadcastTranscoderProgress(event: TranscoderProgressEvent) { safeForEach(transcoderListeners, event) }

    fun registerBuddyListener(listener: (BuddyProgressEvent) -> Unit) { buddyListeners.add(listener) }
    fun unregisterBuddyListener(listener: (BuddyProgressEvent) -> Unit) { buddyListeners.remove(listener) }
    fun broadcastBuddyProgress(event: BuddyProgressEvent) { safeForEach(buddyListeners, event) }

}

data class BuddyProgressEvent(
    val leaseId: Long,
    val buddyName: String,
    val relativePath: String,
    val status: String,
    val progressPercent: Int,
    val encoder: String?
)
