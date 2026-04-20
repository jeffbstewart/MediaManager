package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.lowagie.text.*
import com.lowagie.text.pdf.ColumnText
import com.lowagie.text.pdf.PdfPageEventHelper
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import net.stewart.mediamanager.entity.*
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * Standalone inventory report generator usable from both Vaadin and Armeria contexts.
 * Includes photo embedding, page headers/footers, and lightweight price lookup queries.
 */
object InventoryReportGenerator {

    private data class ReportData(
        val allItems: List<MediaItem>,
        val linksByItem: Map<Long, List<MediaItemTitle>>,
        val allTitles: Map<Long?, Title>
    ) {
        fun titleNames(item: MediaItem): String =
            linksByItem[item.id]?.mapNotNull { allTitles[it.title_id]?.name }?.joinToString(", ") ?: "(unlinked)"
    }

    private data class PriceLookupSummary(val id: Long, val mediaItemId: Long, val keepaAsin: String?, val lookedUpAt: java.time.LocalDateTime?)

    private fun loadPriceLookupSummaries(): List<PriceLookupSummary> =
        JdbiOrm.jdbi().withHandle<List<PriceLookupSummary>, Exception> { handle ->
            handle.createQuery("SELECT id, media_item_id, keepa_asin, looked_up_at FROM price_lookup")
                .map { rs, _ -> PriceLookupSummary(rs.getLong("id"), rs.getLong("media_item_id"), rs.getString("keepa_asin"), rs.getTimestamp("looked_up_at")?.toLocalDateTime()) }
                .list()
        }

    /**
     * Digital editions aren't insurable — excluded. Covers EPUB / PDF /
     * digital audiobook (from books) plus audio rip formats (from music).
     * Physical formats (CD, VINYL_LP, DVD, BLURAY, etc.) remain in the
     * report even when pricing is missing ("—" in the price column).
     */
    private val DIGITAL_FORMATS: Set<String> = setOf(
        "EBOOK_EPUB", "EBOOK_PDF", "AUDIOBOOK_DIGITAL",
        "AUDIO_FLAC", "AUDIO_MP3", "AUDIO_AAC", "AUDIO_OGG", "AUDIO_WAV"
    )

    private fun loadData(): ReportData {
        val allItems = MediaItem.findAll().filter { it.media_format !in DIGITAL_FORMATS }
        val allLinks = MediaItemTitle.findAll()
        val allTitles = Title.findAll().associateBy { it.id }
        return ReportData(allItems, allLinks.groupBy { it.media_item_id }, allTitles)
    }

    private fun sortKey(name: String): String { val l = name.lowercase().trim(); return if (l.startsWith("the ")) l.removePrefix("the ").trim() else l }

    // ---- CSV ----

    fun generateCsv(): ByteArray {
        val data = loadData()
        val sorted = data.allItems.sortedBy { sortKey(data.titleNames(it)) }
        val lookups = loadPriceLookupSummaries().groupBy { it.mediaItemId }.mapValues { (_, l) -> l.maxByOrNull { it.lookedUpAt ?: java.time.LocalDateTime.MIN }!! }
        val orders = AmazonOrder.findAll()
        val sb = StringBuilder()
        sb.appendLine("Title(s),Format,UPC,ASIN,Purchase Date,Purchase Place,Order #,Purchase Price,Replacement Value,Price Source,Price Date")
        for (item in sorted) {
            val lu = lookups[item.id]
            val asin = item.override_asin ?: orders.firstOrNull { it.linked_media_item_id == item.id && it.asin.isNotBlank() }?.asin ?: lu?.keepaAsin
            sb.append(csv(data.titleNames(item))).append(',').append(csv(item.media_format.replace("_", " "))).append(',')
            sb.append(csv(item.upc ?: "")).append(',').append(csv(asin ?: "")).append(',')
            sb.append(csv(item.purchase_date?.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")) ?: "")).append(',')
            sb.append(csv(item.purchase_place ?: "")).append(',').append(csv(item.amazon_order_id ?: "")).append(',')
            sb.append(csv(item.purchase_price?.setScale(2)?.toString() ?: "")).append(',')
            sb.append(csv(item.replacement_value?.setScale(2)?.toString() ?: "")).append(',')
            sb.append(csv(if (lu != null) "Keepa (Amazon.com)" else if (item.replacement_value != null) "Manual" else "")).append(',')
            sb.append(csv(lu?.lookedUpAt?.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")) ?: "")).appendLine()
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ---- PDF ----

    fun generatePdf(withPhotos: Boolean, outputFile: File, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        onProgress(0, 1, "Loading catalog data...")
        val data = loadData()
        val valued = data.allItems.filter { it.purchase_price != null }

        onProgress(0, 1, "Loading pricing data...")
        val lookups = loadPriceLookupSummaries()
        val itemIdsWithLookup = lookups.map { it.mediaItemId }.toSet()
        val allPhotoCounts = OwnershipPhotoService.countByMediaItem()
        val photoCounts = if (withPhotos) allPhotoCounts else emptyMap()

        onProgress(0, 1, "Building summary...")
        val out = FileOutputStream(outputFile)
        val document = Document(PageSize.LETTER.rotate(), 36f, 36f, 36f, 50f)
        val writer = PdfWriter.getInstance(document, out)

        val bf = com.lowagie.text.pdf.BaseFont.createFont()
        val evt = object : PdfPageEventHelper() {
            var tpl: com.lowagie.text.pdf.PdfTemplate? = null
            override fun onOpenDocument(w: PdfWriter, d: Document) { tpl = w.directContent.createTemplate(30f, 12f) }
            override fun onEndPage(w: PdfWriter, d: Document) {
                val cb = w.directContent
                if (w.pageNumber > 1) {
                    cb.beginText(); cb.setFontAndSize(bf, 8f); cb.setColorFill(Color(120, 120, 120))
                    cb.setTextMatrix(d.leftMargin(), d.top() + 18f); cb.showText("Insurance Inventory Report"); cb.endText()
                    val ds = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                    cb.beginText(); cb.setTextMatrix(d.right() - bf.getWidthPoint(ds, 8f), d.top() + 18f); cb.showText(ds); cb.endText()
                }
                val pt = "Page ${w.pageNumber} of "; val x = d.right() - bf.getWidthPoint(pt, 8f) - 20f
                cb.beginText(); cb.setFontAndSize(bf, 8f); cb.setColorFill(Color(120, 120, 120)); cb.setTextMatrix(x, 20f); cb.showText(pt); cb.endText()
                cb.addTemplate(tpl, x + bf.getWidthPoint(pt, 8f), 20f)
            }
            override fun onCloseDocument(w: PdfWriter, d: Document) { tpl?.beginText(); tpl?.setFontAndSize(bf, 8f); tpl?.setColorFill(Color(120, 120, 120)); tpl?.showText("${w.pageNumber}"); tpl?.endText() }
        }
        writer.pageEvent = evt; document.open()

        val tF = Font(Font.HELVETICA, 18f, Font.BOLD); val sF = Font(Font.HELVETICA, 14f, Font.BOLD)
        val nF = Font(Font.HELVETICA, 9f, Font.NORMAL); val hF = Font(Font.HELVETICA, 8f, Font.BOLD, Color.WHITE)
        val cF = Font(Font.HELVETICA, 8f, Font.NORMAL)
        val slF = Font(Font.HELVETICA, 10f, Font.NORMAL, Color(80, 80, 80)); val svF = Font(Font.HELVETICA, 10f, Font.BOLD)

        document.add(Paragraph("Insurance Inventory Report", tF).apply { alignment = Element.ALIGN_CENTER })
        document.add(Paragraph("Generated: ${LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))}", nF).apply { alignment = Element.ALIGN_CENTER; spacingAfter = 16f })
        document.add(Paragraph("Executive Summary", sF).apply { spacingAfter = 8f })

        val total = data.allItems.size
        val grand = valued.mapNotNull { it.purchase_price }.fold(BigDecimal.ZERO, BigDecimal::add)
        val repl = data.allItems.mapNotNull { it.replacement_value }.fold(BigDecimal.ZERO, BigDecimal::add)
        val replCount = data.allItems.count { it.replacement_value != null }
        // Include every format actually present in the inventory. Previously this was a
        // hardcoded list of disc formats — books and future formats were silently dropped.
        // Digital book editions are excluded (nothing to insure).
        val physicalBookFormats = listOf("MASS_MARKET_PAPERBACK", "TRADE_PAPERBACK", "HARDBACK", "AUDIOBOOK_CD")
        val formatLabels = listOf("DVD", "BLURAY", "UHD_BLURAY", "HD_DVD") + physicalBookFormats
        val fmts = formatLabels.mapNotNull { f ->
            data.allItems.groupBy { it.media_format }[f]?.size?.let { "$it ${f.replace("_", " ")}" }
        }

        val st = PdfPTable(2).apply { widthPercentage = 70f; horizontalAlignment = Element.ALIGN_LEFT; setWidths(floatArrayOf(2f, 3f)) }
        fun sr(l: String, v: String) { st.addCell(PdfPCell(Phrase(l, slF)).apply { border = Rectangle.NO_BORDER; setPadding(3f) }); st.addCell(PdfPCell(Phrase(v, svF)).apply { border = Rectangle.NO_BORDER; setPadding(3f) }) }
        sr("Total items:", "$total"); if (fmts.isNotEmpty()) sr("Formats:", fmts.joinToString(", "))
        sr("Documented value:", "\$${grand.setScale(2)}")
        if (valued.isNotEmpty()) sr("Average:", "\$${grand.divide(BigDecimal(valued.size), 2, java.math.RoundingMode.HALF_UP)}")
        sr("Coverage:", "${valued.size} of $total items")
        if (replCount > 0) { sr("Replacement:", "\$${repl.setScale(2)} ($replCount items)"); val a = data.allItems.count { it.replacement_value != null && it.id in itemIdsWithLookup }; sr("Pricing:", "$a auto, ${replCount - a} manual, ${total - replCount} unpriced") }
        val tp = allPhotoCounts.values.sum(); if (tp > 0) sr("Evidence:", "$tp photos, ${allPhotoCounts.size} items")
        val ds = valued.mapNotNull { it.purchase_date }; if (ds.isNotEmpty()) { val f = DateTimeFormatter.ofPattern("MMM yyyy"); sr("Dates:", "${ds.min().format(f)} \u2013 ${ds.max().format(f)}") }
        document.add(st)

        // One section per media format. Drops the Format column from
        // each table (redundant — the section header carries the
        // format), redistributes the column widths to the remaining
        // six, and keeps the photo-row behaviour per item unchanged.
        // Preserves a stable section order: discs first (common insurance
        // case), then books, then any other formats present.
        val sortedFormatOrder = listOf(
            "UHD_BLURAY", "BLURAY", "DVD", "HD_DVD",
            "HARDBACK", "TRADE_PAPERBACK", "MASS_MARKET_PAPERBACK", "AUDIOBOOK_CD",
            "CD", "DIGITAL_MUSIC_ALBUM"
        )
        val byFormat = data.allItems.groupBy { it.media_format }
        val orderedFormats = (sortedFormatOrder.filter { byFormat.containsKey(it) } +
            byFormat.keys.filter { it !in sortedFormatOrder }.sorted())

        onProgress(0, total, "Building PDF...")
        var idx = 0
        for (fmt in orderedFormats) {
            val items = byFormat[fmt].orEmpty().sortedBy { sortKey(data.titleNames(it)) }
            if (items.isEmpty()) continue

            val prettyFmt = fmt.replace("_", " ")
            document.add(Paragraph(
                "\n$prettyFmt — ${items.size} item${if (items.size == 1) "" else "s"}",
                sF
            ).apply { spacingBefore = 12f; spacingAfter = 6f })

            val tbl = PdfPTable(6).apply {
                widthPercentage = 100f
                // Title(s) gets the lion's share; UPC / Purchase Place
                // expand into what used to be the Format column.
                setWidths(floatArrayOf(3f, 1.7f, 1.7f, 1f, 1f, 1f))
            }
            fun hc(t: String) = PdfPCell(Phrase(t, hF)).apply { backgroundColor = Color(60, 60, 60); setPadding(4f) }
            tbl.addCell(hc("Title(s)"))
            tbl.addCell(hc("UPC"))
            tbl.addCell(hc("Purchase Place"))
            tbl.addCell(hc("Price"))
            tbl.addCell(hc("Replacement"))
            tbl.addCell(hc("Date"))
            tbl.headerRows = 1

            for (item in items) {
                idx++; if (idx % 50 == 0) onProgress(idx, total, "Building PDF")
                fun c(t: String) = PdfPCell(Phrase(t, cF)).apply { setPadding(3f) }
                tbl.addCell(c(data.titleNames(item)))
                tbl.addCell(c(item.upc ?: ""))
                tbl.addCell(c(item.purchase_place ?: ""))
                tbl.addCell(c(item.purchase_price?.setScale(2)?.let { "\$$it" } ?: ""))
                tbl.addCell(c(item.replacement_value?.setScale(2)?.let { "\$$it" } ?: ""))
                tbl.addCell(c(item.purchase_date?.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")) ?: ""))
                if (photoCounts.containsKey(item.id)) addPhotoRow(tbl, 6, item.id!!)
            }
            document.add(tbl)
        }
        onProgress(total, total, "Finalizing..."); document.close(); out.close()
    }

    private fun addPhotoRow(table: PdfPTable, cols: Int, mediaItemId: Long) {
        val photos = OwnershipPhotoService.findByMediaItem(mediaItemId); if (photos.isEmpty()) return
        val cell = PdfPCell().apply { colspan = cols; setPadding(4f); border = Rectangle.BOX; borderWidth = 0.5f; borderColor = Color(200, 200, 200) }
        val pt = PdfPTable(photos.size.coerceAtMost(6)).apply { widthPercentage = 100f }
        for (photo in photos.take(6)) {
            val f = OwnershipPhotoService.getFile(photo.id!!)
            if (f != null && f.exists()) {
                try { val img = loadImg(f, photo.orientation); val s = 72f / img.height; img.scaleAbsolute(img.width * s, 72f); pt.addCell(PdfPCell(img).apply { border = Rectangle.NO_BORDER; setPadding(2f) })
                } catch (_: Exception) { pt.addCell(PdfPCell(Phrase("[photo]", Font(Font.HELVETICA, 8f, Font.ITALIC, Color(150, 150, 150)))).apply { border = Rectangle.NO_BORDER }) }
            }
        }
        cell.addElement(pt); table.addCell(cell)
    }

    private fun loadImg(file: File, orientation: Int): com.lowagie.text.Image {
        val thumb = readSub(file, 300) ?: return com.lowagie.text.Image.getInstance(file.absolutePath)
        if (orientation <= 1) { val b = ByteArrayOutputStream(); ImageIO.write(thumb, "jpg", b); return com.lowagie.text.Image.getInstance(b.toByteArray()) }
        val w = thumb.width; val h = thumb.height; val (nw, nh) = when (orientation) { 6, 8, 5, 7 -> h to w; else -> w to h }
        val r = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB); val g = r.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val tx = AffineTransform()
        when (orientation) { 2 -> { tx.scale(-1.0, 1.0); tx.translate(-w.toDouble(), 0.0) }; 3 -> { tx.translate(w.toDouble(), h.toDouble()); tx.rotate(Math.PI) }
            4 -> { tx.scale(1.0, -1.0); tx.translate(0.0, -h.toDouble()) }; 5 -> { tx.rotate(Math.PI / 2); tx.scale(1.0, -1.0) }
            6 -> { tx.translate(h.toDouble(), 0.0); tx.rotate(Math.PI / 2) }; 7 -> { tx.translate(h.toDouble(), w.toDouble()); tx.rotate(Math.PI / 2); tx.scale(1.0, -1.0); tx.translate(0.0, -w.toDouble()) }
            8 -> { tx.translate(0.0, w.toDouble()); tx.rotate(-Math.PI / 2) } }
        g.drawImage(thumb, tx, null); g.dispose(); val b = ByteArrayOutputStream(); ImageIO.write(r, "jpg", b); return com.lowagie.text.Image.getInstance(b.toByteArray())
    }

    private fun readSub(file: File, maxPx: Int): BufferedImage? {
        val s = ImageIO.createImageInputStream(file) ?: return null; val rs = ImageIO.getImageReaders(s); if (!rs.hasNext()) { s.close(); return null }
        val r = rs.next(); try { r.input = s; val w = r.getWidth(0); val h = r.getHeight(0); val l = maxOf(w, h)
            if (l <= maxPx) return r.read(0); val sub = l / maxPx; val p = r.defaultReadParam; p.setSourceSubsampling(sub, sub, 0, 0); return r.read(0, p)
        } finally { r.dispose(); s.close() }
    }

    private fun csv(v: String): String = if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"${v.replace("\"", "\"\"")}\"" else v
}
