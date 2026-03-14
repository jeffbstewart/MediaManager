package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.OwnershipPhotoService
import com.vaadin.flow.component.ClientCallable
import java.time.LocalDateTime
import java.util.Base64

@Route(value = "document-ownership", layout = MainLayout::class)
@PageTitle("Document Ownership")
class DocumentOwnershipView : VerticalLayout() {

    private var currentItem: MediaItem? = null
    private var currentUpc: String? = null
    private val contentArea = VerticalLayout().apply {
        isPadding = false
        isSpacing = true
        width = "100%"
    }

    init {
        isPadding = true
        isSpacing = true
        width = "100%"
        style.set("max-width", "600px")
        style.set("margin", "0 auto")

        add(H2("Document Ownership"))
        add(Span("Scan a UPC or search for a title, then take photos as proof of ownership for insurance.").apply {
            style.set("color", "rgba(255,255,255,0.6)")
            style.set("margin-bottom", "var(--lumo-space-m)")
        })

        add(contentArea)
        showScanPhase()
    }

    private fun showScanPhase() {
        contentArea.removeAll()
        currentItem = null
        currentUpc = null

        // UPC text input (works with Bluetooth barcode scanners and manual entry)
        val upcField = TextField().apply {
            placeholder = "Scan or type UPC barcode..."
            width = "100%"
            isClearButtonVisible = true
            style.set("font-size", "var(--lumo-font-size-l)")
            element.setAttribute("inputmode", "numeric")
            element.setAttribute("autocomplete", "off")
        }

        upcField.addValueChangeListener { event ->
            val upc = event.value?.trim() ?: ""
            if (upc.length < 8 || !upc.all { it.isDigit() }) return@addValueChangeListener
            lookupUpc(upc)
            upcField.clear()
        }

        // Also handle Enter key for partial typing
        upcField.element.addEventListener("keypress") {
            upcField.value?.trim()?.let { upc ->
                if (upc.length >= 8 && upc.all { it.isDigit() }) {
                    lookupUpc(upc)
                    upcField.clear()
                }
            }
        }.filter = "event.key === 'Enter'"

        val scanBtn = Button("Scan with Camera", VaadinIcon.CAMERA.create()) {
            OwnershipScannerDialog(
                onItemFound = { item ->
                    currentItem = item
                    currentUpc = item.upc
                    showCapturePhase()
                },
                onNewUpc = { upc ->
                    currentUpc = upc
                    currentItem = null
                    showCapturePhase()
                }
            ).open()
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            width = "100%"
        }

        val orLabel = Span("— or search by title —").apply {
            style.set("color", "rgba(255,255,255,0.4)")
            style.set("text-align", "center")
            style.set("display", "block")
        }

        val searchField = TextField().apply {
            placeholder = "Search by title or UPC..."
            width = "100%"
            isClearButtonVisible = true
            valueChangeMode = ValueChangeMode.LAZY
            valueChangeTimeout = 300
        }

        val searchResults = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "100%"
        }

        searchField.addValueChangeListener { event ->
            val query = event.value?.trim()?.lowercase() ?: ""
            searchResults.removeAll()
            if (query.length < 2) return@addValueChangeListener

            val titleMap = buildTitleMap()
            val matches = MediaItem.findAll()
                .filter { item ->
                    val titles = titleMap[item.id]?.lowercase() ?: ""
                    val upc = item.upc?.lowercase() ?: ""
                    val product = item.product_name?.lowercase() ?: ""
                    titles.contains(query) || upc.contains(query) || product.contains(query)
                }
                .sortedBy { titleMap[it.id]?.lowercase() ?: "" }
                .take(20)

            for (item in matches) {
                val photoCount = OwnershipPhotoService.findByMediaItem(item.id!!).size
                val row = HorizontalLayout().apply {
                    width = "100%"
                    isPadding = false
                    isSpacing = true
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    style.set("padding", "var(--lumo-space-s)")
                    style.set("border-bottom", "1px solid rgba(255,255,255,0.1)")
                    style.set("cursor", "pointer")

                    val titleText = titleMap[item.id] ?: item.product_name ?: "—"
                    val format = item.media_format.replace("_", " ")
                    val info = VerticalLayout().apply {
                        isPadding = false
                        isSpacing = false
                        add(Span(titleText).apply {
                            style.set("font-weight", "500")
                        })
                        val subtitle = mutableListOf(format)
                        item.upc?.let { subtitle.add("UPC: $it") }
                        if (photoCount > 0) subtitle.add("$photoCount photo${if (photoCount != 1) "s" else ""}")
                        add(Span(subtitle.joinToString(" · ")).apply {
                            style.set("font-size", "var(--lumo-font-size-s)")
                            style.set("color", "rgba(255,255,255,0.5)")
                        })
                    }
                    add(info)
                    expand(info)

                    if (photoCount > 0) {
                        add(VaadinIcon.CHECK_CIRCLE.create().apply {
                            setSize("20px")
                            style.set("color", "var(--lumo-success-color)")
                        })
                    }

                    addClickListener {
                        currentItem = item
                        currentUpc = item.upc
                        showCapturePhase()
                    }
                }
                searchResults.add(row)
            }

            if (matches.isEmpty()) {
                searchResults.add(Span("No items found").apply {
                    style.set("color", "rgba(255,255,255,0.4)")
                    style.set("padding", "var(--lumo-space-m)")
                })
            }
        }

        contentArea.add(upcField, scanBtn, orLabel, searchField, searchResults)
        upcField.focus()
    }

    private fun showCapturePhase() {
        contentArea.removeAll()
        val item = currentItem
        val upc = currentUpc

        val titleMap = buildTitleMap()

        // Item confirmation header
        val header = HorizontalLayout().apply {
            width = "100%"
            isPadding = false
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            style.set("background", "rgba(255,255,255,0.05)")
            style.set("border-radius", "var(--lumo-border-radius-l)")
            style.set("padding", "var(--lumo-space-m)")

            if (item != null) {
                val titleText = titleMap[item.id] ?: item.product_name ?: "—"
                val format = item.media_format.replace("_", " ")

                // Try to show poster
                val titleId = findTitleId(item)
                if (titleId != null) {
                    val title = Title.findById(titleId)
                    if (title != null && title.poster_path != null) {
                        add(Image("/posters/w185/$titleId", titleText).apply {
                            height = "80px"
                            width = "54px"
                            style.set("object-fit", "cover")
                            style.set("border-radius", "4px")
                            style.set("flex-shrink", "0")
                        })
                    }
                }

                val info = VerticalLayout().apply {
                    isPadding = false
                    isSpacing = false
                    add(Span(titleText).apply {
                        style.set("font-weight", "600")
                        style.set("font-size", "var(--lumo-font-size-l)")
                    })
                    val details = mutableListOf(format)
                    item.upc?.let { details.add("UPC: $it") }
                    add(Span(details.joinToString(" · ")).apply {
                        style.set("color", "rgba(255,255,255,0.5)")
                        style.set("font-size", "var(--lumo-font-size-s)")
                    })
                }
                add(info)
                expand(info)
            } else if (upc != null) {
                // Novel UPC — not yet in catalog
                add(VaadinIcon.BARCODE.create().apply {
                    setSize("40px")
                    style.set("color", "rgba(255,255,255,0.4)")
                    style.set("flex-shrink", "0")
                })
                val info = VerticalLayout().apply {
                    isPadding = false
                    isSpacing = false
                    add(Span("New Scan — Queued for Lookup").apply {
                        style.set("font-weight", "600")
                        style.set("font-size", "var(--lumo-font-size-l)")
                    })
                    add(Span("UPC: $upc").apply {
                        style.set("color", "rgba(255,255,255,0.5)")
                        style.set("font-size", "var(--lumo-font-size-s)")
                    })
                }
                add(info)
                expand(info)
            }
        }
        contentArea.add(header)

        // Use extracted OwnershipPhotoPanel
        val photoPanel = OwnershipPhotoPanel().apply {
            mediaItemId = item?.id
            this.upc = upc
            refresh()
        }
        contentArea.add(photoPanel)

        val scanAnotherBtn = Button("Scan Another", VaadinIcon.BARCODE.create()) {
            showScanPhase()
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            width = "100%"
        }

        contentArea.add(scanAnotherBtn)
    }

    private fun lookupUpc(upc: String) {
        if (upc.length < 8 || upc.length > 14 || !upc.all { it.isDigit() }) {
            Notification.show("Invalid UPC format", 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
            return
        }

        // Check if we already have this item
        val item = MediaItem.findAll().firstOrNull { it.upc == upc }

        if (item != null) {
            currentItem = item
            currentUpc = item.upc
            showCapturePhase()
        } else {
            // Novel UPC — create a BarcodeScan for lookup
            val existingScan = BarcodeScan.findAll().firstOrNull { it.upc == upc }
            if (existingScan == null) {
                BarcodeScan(
                    upc = upc,
                    scanned_at = LocalDateTime.now(),
                    lookup_status = LookupStatus.NOT_LOOKED_UP.name
                ).save()
            }
            currentUpc = upc
            currentItem = null
            showCapturePhase()
        }
    }

    private fun buildTitleMap(): Map<Long, String> {
        val allLinks = MediaItemTitle.findAll()
        val allTitles = Title.findAll().associateBy { it.id }
        return allLinks.groupBy { it.media_item_id }
            .mapValues { (_, links) ->
                links.mapNotNull { allTitles[it.title_id]?.name }.joinToString(", ")
            }
    }

    private fun findTitleId(item: MediaItem): Long? {
        return MediaItemTitle.findAll().firstOrNull { it.media_item_id == item.id }?.title_id
    }
}

/**
 * Scanner dialog for ownership documentation.
 * Scans a UPC barcode — if it matches an existing media item, returns it.
 * If the UPC is novel, creates a BarcodeScan for lookup and returns the UPC.
 */
class OwnershipScannerDialog(
    private val onItemFound: (MediaItem) -> Unit,
    private val onNewUpc: (String) -> Unit
) : Dialog() {

    private val scanLog = VerticalLayout().apply {
        isPadding = false
        isSpacing = false
        width = "100%"
        style.set("max-height", "150px")
        style.set("overflow-y", "auto")
    }

    init {
        headerTitle = "Scan for Ownership"
        width = "min(500px, 95vw)"
        @Suppress("DEPRECATION")
        isModal = true
        isCloseOnOutsideClick = false

        val closeBtn = Button(VaadinIcon.CLOSE_SMALL.create()) { close() }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            element.setAttribute("title", "Close")
        }
        header.add(closeBtn)

        val readerDiv = Div().apply {
            element.setAttribute("id", "mm-ownership-reader")
            width = "100%"
        }

        val statusLabel = Span("Starting camera...").apply {
            element.setAttribute("id", "mm-ownership-status")
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
            "if(!document.getElementById('mm-ownership-responsive')){" +
            "var s=document.createElement('style');s.id='mm-ownership-responsive';" +
            "s.textContent='@media(max-width:600px){" +
            "vaadin-dialog-overlay [part=overlay]{" +
            "width:100vw!important;height:100vh!important;" +
            "max-width:100vw!important;max-height:100vh!important;" +
            "top:0!important;left:0!important;" +
            "border-radius:0!important}" +
            "}';" +
            "document.head.appendChild(s)}"
        )

        addOpenedChangeListener { event ->
            if (!event.isOpened) {
                element.executeJs(
                    "if(window.__mmOwnershipScanner){" +
                    "window.__mmOwnershipScanner.stop().catch(function(){});" +
                    "delete window.__mmOwnershipScanner;}"
                )
            }
        }

        initScanner()
    }

    private fun initScanner() {
        element.executeJs(
            "var dialogEl=\$0;" +
            "var statusEl=document.getElementById('mm-ownership-status');" +

            "function boot(){" +
            "if(window.Html5Qrcode){startScanner();return;}" +
            "var sc=document.createElement('script');" +
            "sc.src='html5-qrcode.min.js';" +
            "sc.onload=startScanner;" +
            "sc.onerror=function(){statusEl.textContent='Failed to load scanner library';statusEl.style.color='var(--lumo-error-text-color)';};" +
            "document.head.appendChild(sc);}" +

            "var audioCtx=null;" +
            "try{audioCtx=new(window.AudioContext||window.webkitAudioContext)();}catch(e){}" +
            "function playBeep(freq,dur){" +
            "if(!audioCtx)return;" +
            "try{if(audioCtx.state==='suspended')audioCtx.resume();" +
            "var o=audioCtx.createOscillator();var g=audioCtx.createGain();" +
            "o.connect(g);g.connect(audioCtx.destination);" +
            "o.frequency.value=freq;g.gain.value=0.3;" +
            "o.start();o.stop(audioCtx.currentTime+(dur||0.15));}catch(e){}}" +

            "function flash(color){" +
            "var el=document.getElementById('mm-ownership-reader');" +
            "if(!el)return;" +
            "el.style.boxShadow='inset 0 0 0 4px '+color;" +
            "setTimeout(function(){el.style.boxShadow='none';},600);}" +

            "function startScanner(){" +
            "var reader=document.getElementById('mm-ownership-reader');" +
            "if(!reader){statusEl.textContent='Scanner element not found';return;}" +
            "var scanner=new Html5Qrcode('mm-ownership-reader');" +
            "window.__mmOwnershipScanner=scanner;" +
            "var lastCode='';var lastTime=0;" +
            "scanner.start(" +
            "{facingMode:'environment'}," +
            "{fps:10," +
            "qrbox:function(vw,vh){var w=Math.min(vw,400);return{width:w,height:Math.floor(w*0.4)};}," +
            "formatsToSupport:[" +
            "Html5QrcodeSupportedFormats.UPC_A," +
            "Html5QrcodeSupportedFormats.UPC_E," +
            "Html5QrcodeSupportedFormats.EAN_8," +
            "Html5QrcodeSupportedFormats.EAN_13" +
            "]}," +
            "function(code,result){" +
            "var now=Date.now();" +
            "if(code===lastCode&&now-lastTime<3000)return;" +
            "lastCode=code;lastTime=now;" +
            "dialogEl.\$server.onBarcodeDetected(code)" +
            ".then(function(r){" +
            "if(r.startsWith('FOUND')||r.startsWith('NEW')){playBeep(1200,0.15);flash('var(--lumo-success-color)');}" +
            "else{playBeep(400,0.25);flash('var(--lumo-error-color)');}" +
            "});" +
            "}," +
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
            this.element
        )
    }

    @ClientCallable
    fun onBarcodeDetected(code: String): String {
        val upc = code.trim()

        if (upc.length < 8 || upc.length > 14 || !upc.all { it.isDigit() }) {
            addLogEntry(upc, "Invalid format", "var(--lumo-error-text-color)")
            return "ERR:Invalid UPC format"
        }

        // Check if we already have this item
        val item = MediaItem.findAll().firstOrNull { it.upc == upc }

        // Stop scanner
        element.executeJs(
            "if(window.__mmOwnershipScanner){" +
            "window.__mmOwnershipScanner.stop().catch(function(){});" +
            "delete window.__mmOwnershipScanner;}"
        )

        if (item != null) {
            val titleName = findTitleName(item)
            addLogEntry(upc, "Found: ${titleName ?: upc}", "var(--lumo-success-text-color)")

            ui.ifPresent { ui ->
                ui.access {
                    close()
                    onItemFound(item)
                }
            }
            return "FOUND:${titleName ?: upc}"
        } else {
            // Novel UPC — create a BarcodeScan for lookup
            val existingScan = BarcodeScan.findAll().firstOrNull { it.upc == upc }
            if (existingScan == null) {
                BarcodeScan(
                    upc = upc,
                    scanned_at = LocalDateTime.now(),
                    lookup_status = LookupStatus.NOT_LOOKED_UP.name
                ).save()
            }

            addLogEntry(upc, "New — queued for lookup", "var(--lumo-primary-text-color)")

            ui.ifPresent { ui ->
                ui.access {
                    close()
                    onNewUpc(upc)
                }
            }
            return "NEW:$upc"
        }
    }

    private fun addLogEntry(upc: String, message: String, color: String) {
        val entry = Div().apply {
            style.set("padding", "var(--lumo-space-xs) var(--lumo-space-s)")
            style.set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
            style.set("font-size", "var(--lumo-font-size-s)")

            add(Span(upc).apply {
                style.set("font-family", "monospace")
                style.set("margin-right", "var(--lumo-space-s)")
            })
            add(Span(message).apply {
                style.set("color", color)
            })
        }
        if (scanLog.componentCount > 0) {
            scanLog.addComponentAtIndex(0, entry)
        } else {
            scanLog.add(entry)
        }
        while (scanLog.componentCount > 10) {
            scanLog.remove(scanLog.getComponentAt(scanLog.componentCount - 1))
        }
    }

    private fun findTitleName(item: MediaItem): String? {
        val link = MediaItemTitle.findAll().firstOrNull { it.media_item_id == item.id } ?: return null
        return Title.findById(link.title_id)?.name
    }
}
