package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.ClientCallable
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.Broadcaster
import net.stewart.mediamanager.service.ScanUpdateEvent
import java.time.LocalDateTime

/**
 * Dialog that opens the device camera for barcode scanning using html5-qrcode.
 * Supports continuous scanning: detect → beep → submit → ready for next.
 * Works on iOS Safari and iOS Chrome (both WebKit-based).
 *
 * Communication flow:
 * 1. Dialog opens, executeJs() loads html5-qrcode and starts the camera
 * 2. JS detects a barcode → calls @ClientCallable onBarcodeDetected()
 * 3. Server validates, creates BarcodeScan, returns result string
 * 4. JS shows visual/audio feedback, continues scanning
 */
class BarcodeScannerDialog(
    private val onScanComplete: () -> Unit
) : Dialog() {

    private val scanLog = VerticalLayout().apply {
        isPadding = false
        isSpacing = false
        width = "100%"
        style.set("max-height", "150px")
        style.set("overflow-y", "auto")
    }

    /** Map of UPC → status Span, so we can update entries when lookup completes. */
    private val statusSpans = mutableMapOf<String, Span>()

    private val scanListener: (ScanUpdateEvent) -> Unit = { event ->
        ui.ifPresent { ui -> ui.access {
            val span = statusSpans[event.upc]
            if (span != null) {
                val (text, color) = when (event.newStatus) {
                    LookupStatus.FOUND.name -> (event.notes ?: "Found") to "var(--lumo-success-text-color)"
                    LookupStatus.NOT_FOUND.name -> "Not found" to "var(--lumo-error-text-color)"
                    else -> event.newStatus to "var(--lumo-secondary-text-color)"
                }
                span.text = text
                span.style.set("color", color)
            }
        } }
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        Broadcaster.register(scanListener)
    }

    override fun onDetach(detachEvent: DetachEvent) {
        Broadcaster.unregister(scanListener)
        super.onDetach(detachEvent)
    }

    init {
        headerTitle = "Scan Barcode"
        width = "min(500px, 95vw)"
        @Suppress("DEPRECATION")
        isModal = true
        isCloseOnOutsideClick = false

        val closeBtn = Button(VaadinIcon.CLOSE_SMALL.create()) { close() }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            element.setAttribute("title", "Close")
        }
        header.add(closeBtn)

        // Camera viewfinder target
        val readerDiv = Div().apply {
            element.setAttribute("id", "mm-barcode-reader")
            width = "100%"
        }

        val statusLabel = Span("Starting camera...").apply {
            element.setAttribute("id", "mm-scan-status")
            style.set("color", "var(--lumo-secondary-text-color)")
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("display", "block")
            style.set("text-align", "center")
            style.set("padding", "var(--lumo-space-xs) 0")
        }

        val content = VerticalLayout().apply {
            isPadding = true
            isSpacing = true
            defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER
            add(readerDiv, statusLabel, scanLog)
        }
        add(content)

        // Mobile fullscreen
        element.executeJs(
            "if(!document.getElementById('mm-scan-responsive')){" +
            "var s=document.createElement('style');s.id='mm-scan-responsive';" +
            "s.textContent='@media(max-width:600px){" +
            "vaadin-dialog-overlay [part=overlay]{" +
            "width:100vw!important;height:100vh!important;" +
            "max-width:100vw!important;max-height:100vh!important;" +
            "top:0!important;left:0!important;" +
            "border-radius:0!important}" +
            "}';" +
            "document.head.appendChild(s)}"
        )

        // Stop camera when dialog closes
        addOpenedChangeListener { event ->
            if (!event.isOpened) {
                element.executeJs(
                    "if(window.__mmScanner){" +
                    "window.__mmScanner.stop().catch(function(){});" +
                    "delete window.__mmScanner;}"
                )
                onScanComplete()
            }
        }

        // Load library and start scanner
        initScanner()
    }

    private fun initScanner() {
        element.executeJs(
            // $0 = this dialog element (for $server calls)
            "var dialogEl=\$0;" +
            "var statusEl=document.getElementById('mm-scan-status');" +

            // Load html5-qrcode library (idempotent)
            "function boot(){" +
            "if(window.Html5Qrcode){startScanner();return;}" +
            "var sc=document.createElement('script');" +
            "sc.src='html5-qrcode.min.js';" +
            "sc.onload=startScanner;" +
            "sc.onerror=function(){statusEl.textContent='Failed to load scanner library';statusEl.style.color='var(--lumo-error-text-color)';};" +
            "document.head.appendChild(sc);}" +

            // Audio context (created once on user gesture — dialog open satisfies this)
            "var audioCtx=null;" +
            "try{audioCtx=new(window.AudioContext||window.webkitAudioContext)();}catch(e){}" +
            "function playBeep(freq,dur){" +
            "if(!audioCtx)return;" +
            "try{if(audioCtx.state==='suspended')audioCtx.resume();" +
            "var o=audioCtx.createOscillator();var g=audioCtx.createGain();" +
            "o.connect(g);g.connect(audioCtx.destination);" +
            "o.frequency.value=freq;g.gain.value=0.3;" +
            "o.start();o.stop(audioCtx.currentTime+(dur||0.15));}catch(e){}}" +

            // Visual flash on the reader element
            "function flash(color){" +
            "var el=document.getElementById('mm-barcode-reader');" +
            "if(!el)return;" +
            "el.style.boxShadow='inset 0 0 0 4px '+color;" +
            "setTimeout(function(){el.style.boxShadow='none';},600);}" +

            // Start the camera scanner
            "function startScanner(){" +
            "var reader=document.getElementById('mm-barcode-reader');" +
            "if(!reader){statusEl.textContent='Scanner element not found';return;}" +
            "var scanner=new Html5Qrcode('mm-barcode-reader');" +
            "window.__mmScanner=scanner;" +
            "var lastCode='';var lastTime=0;" +
            "scanner.start(" +
            "{facingMode:'environment'}," +  // rear camera
            "{fps:10," +
            "qrbox:function(vw,vh){var w=Math.min(vw,400);return{width:w,height:Math.floor(w*0.4)};}," +  // wide rectangle for barcodes
            "formatsToSupport:[" +
            "Html5QrcodeSupportedFormats.UPC_A," +
            "Html5QrcodeSupportedFormats.UPC_E," +
            "Html5QrcodeSupportedFormats.EAN_8," +
            "Html5QrcodeSupportedFormats.EAN_13" +
            "]}," +
            // Success callback
            "function(code,result){" +
            "var now=Date.now();" +
            "if(code===lastCode&&now-lastTime<3000)return;" +  // 3s cooldown per code
            "lastCode=code;lastTime=now;" +
            "dialogEl.\$server.onBarcodeDetected(code)" +
            ".then(function(r){" +
            "if(r.startsWith('OK')){playBeep(1200,0.15);flash('var(--lumo-success-color)');}" +
            "else if(r.startsWith('DUP')){playBeep(800,0.1);flash('var(--lumo-primary-color)');}" +
            "else{playBeep(400,0.25);flash('var(--lumo-error-color)');}" +
            "});" +
            "}," +
            // Error callback (normal — no barcode in frame)
            "function(){}" +
            ").then(function(){" +
            "statusEl.textContent='Point camera at a barcode';" +
            "}).catch(function(err){" +
            "var msg=err&&err.message?err.message:String(err);" +
            "if(msg.indexOf('NotAllowedError')>=0||msg.indexOf('Permission')>=0){" +
            "statusEl.textContent='Camera permission denied. Please allow camera access and try again.';" +
            "}else if(msg.indexOf('NotFoundError')>=0||msg.indexOf('Requested device not found')>=0){" +
            "statusEl.textContent='No camera found on this device.';" +
            "}else{" +
            "statusEl.textContent='Camera error: '+msg;}" +
            "statusEl.style.color='var(--lumo-error-text-color)';});}" +

            "boot();",
            this.element  // $0
        )
    }

    /**
     * Called from client-side JS when a barcode is detected.
     * Returns a result string for JS feedback:
     * - "OK" — new scan created
     * - "DUP:Title Name" — already in database
     * - "ERR:reason" — validation failure
     */
    @ClientCallable
    fun onBarcodeDetected(code: String): String {
        val upc = code.trim()

        if (upc.length < 8 || upc.length > 14 || !upc.all { it.isDigit() }) {
            addScanLogEntry(upc, "Invalid format", "var(--lumo-error-text-color)")
            return "ERR:Invalid UPC format"
        }

        // Check for duplicate
        val existingScan = BarcodeScan.findAll().firstOrNull { it.upc == upc }
        if (existingScan != null) {
            val mediaItemId = existingScan.media_item_id
            if (mediaItemId != null) {
                val join = MediaItemTitle.findAll().firstOrNull { it.media_item_id == mediaItemId }
                val title = join?.let { Title.findById(it.title_id) }
                if (title?.enrichment_status == EnrichmentStatus.ENRICHED.name) {
                    // Already known and enriched — navigate to item page
                    close()
                    ui.ifPresent { it.navigate("item/$mediaItemId") }
                    return "DUP:${title.name}"
                }
            }
            val titleName = findTitleForScan(existingScan)
            val label = titleName ?: upc
            addScanLogEntry(upc, "Already scanned: $label", "var(--lumo-primary-text-color)")
            return "DUP:$label"
        }

        // Create new scan
        BarcodeScan(
            upc = upc,
            scanned_at = LocalDateTime.now(),
            lookup_status = LookupStatus.NOT_LOOKED_UP.name
        ).save()

        addScanLogEntry(upc, "Queued for lookup", "var(--lumo-success-text-color)")
        return "OK"
    }

    private fun addScanLogEntry(upc: String, message: String, color: String) {
        val statusSpan = Span(message).apply {
            style.set("color", color)
        }
        // Track "Queued" entries so the Broadcaster can update them
        if (message == "Queued for lookup") {
            statusSpans[upc] = statusSpan
        }
        val entry = Div().apply {
            style.set("padding", "var(--lumo-space-xs) var(--lumo-space-s)")
            style.set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
            style.set("font-size", "var(--lumo-font-size-s)")

            add(Span(upc).apply {
                style.set("font-family", "monospace")
                style.set("margin-right", "var(--lumo-space-s)")
            })
            add(statusSpan)
        }
        // Prepend (newest first)
        if (scanLog.componentCount > 0) {
            scanLog.addComponentAtIndex(0, entry)
        } else {
            scanLog.add(entry)
        }
        // Keep max 10 entries
        while (scanLog.componentCount > 10) {
            scanLog.remove(scanLog.getComponentAt(scanLog.componentCount - 1))
        }
    }

    private fun findTitleForScan(scan: BarcodeScan): String? {
        val mediaItemId = scan.media_item_id ?: return null
        val join = MediaItemTitle.findAll().firstOrNull { it.media_item_id == mediaItemId } ?: return null
        return Title.findById(join.title_id)?.name
    }
}
