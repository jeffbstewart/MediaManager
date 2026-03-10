package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.lowagie.text.*
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.StreamResource
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.OwnershipPhotoService
import java.awt.Color
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Route(value = "report", layout = MainLayout::class)
@PageTitle("Insurance Inventory Report")
class InventoryReportView : KComposite() {

    private var includePhotos = false

    private val root = ui {
        verticalLayout {
            isPadding = true
            isSpacing = true
            defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER

            h3("Insurance Inventory Report")

            add(Span("Generate a report of all media purchases for insurance documentation.").apply {
                style.set("color", "var(--lumo-secondary-text-color)")
            })

            val photosCheckbox = com.vaadin.flow.component.checkbox.Checkbox("Include ownership photos in PDF").apply {
                value = false
                addValueChangeListener { includePhotos = it.value }
                val photoCount = OwnershipPhotoService.totalCount()
                val itemCount = OwnershipPhotoService.itemsWithPhotos()
                if (photoCount > 0) {
                    label = "Include ownership photos in PDF ($photoCount photos across $itemCount items)"
                } else {
                    isEnabled = false
                    label = "Include ownership photos in PDF (no photos captured yet)"
                }
            }
            add(photosCheckbox)

            val timestamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

            val pdfResource = StreamResource("inventory-report-$timestamp.pdf") {
                ByteArrayInputStream(generatePdf(includePhotos))
            }
            pdfResource.setContentType("application/pdf")

            val csvResource = StreamResource("inventory-report-$timestamp.csv") {
                ByteArrayInputStream(generateCsv())
            }
            csvResource.setContentType("text/csv")

            val pdfButton = Button("Download PDF", VaadinIcon.DOWNLOAD.create()).apply {
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }
            val pdfAnchor = Anchor(pdfResource, "").apply {
                element.setAttribute("download", true)
                add(pdfButton)
            }

            val csvButton = Button("Download CSV", VaadinIcon.TABLE.create()).apply {
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }
            val csvAnchor = Anchor(csvResource, "").apply {
                element.setAttribute("download", true)
                add(csvButton)
            }

            add(HorizontalLayout(pdfAnchor, csvAnchor).apply {
                isSpacing = true
            })
        }
    }

    private data class ReportData(
        val allItems: List<MediaItem>,
        val linksByItem: Map<Long, List<MediaItemTitle>>,
        val allTitles: Map<Long?, Title>
    ) {
        fun titleNames(item: MediaItem): String =
            linksByItem[item.id]
                ?.mapNotNull { allTitles[it.title_id]?.name }
                ?.joinToString(", ")
                ?: "(unlinked)"
    }

    private fun loadData(): ReportData {
        val allItems = MediaItem.findAll()
        val allLinks = MediaItemTitle.findAll()
        val allTitles = Title.findAll().associateBy { it.id }
        val linksByItem = allLinks.groupBy { it.media_item_id }
        return ReportData(allItems, linksByItem, allTitles)
    }

    private fun sortKey(titleName: String): String {
        val lower = titleName.lowercase().trim()
        return if (lower.startsWith("the ")) lower.removePrefix("the ").trim() else lower
    }

    // ---- CSV ----

    private fun generateCsv(): ByteArray {
        val data = loadData()
        val sorted = data.allItems.sortedBy { sortKey(data.titleNames(it)) }
        val sb = StringBuilder()

        sb.appendLine("Title(s),Format,UPC,Purchase Date,Purchase Place,Order #,Purchase Price,Replacement Value")

        for (item in sorted) {
            sb.append(csvField(data.titleNames(item)))
            sb.append(',')
            sb.append(csvField(item.media_format.replace("_", " ")))
            sb.append(',')
            sb.append(csvField(item.upc ?: ""))
            sb.append(',')
            sb.append(csvField(item.purchase_date?.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")) ?: ""))
            sb.append(',')
            sb.append(csvField(item.purchase_place ?: ""))
            sb.append(',')
            sb.append(csvField(item.amazon_order_id ?: ""))
            sb.append(',')
            sb.append(csvField(item.purchase_price?.setScale(2)?.toString() ?: ""))
            sb.append(',')
            sb.append(csvField(item.replacement_value?.setScale(2)?.toString() ?: ""))
            sb.appendLine()
        }

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun csvField(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    // ---- PDF ----

    private fun generatePdf(withPhotos: Boolean = false): ByteArray {
        val data = loadData()
        val valued = data.allItems.filter { it.purchase_price != null }
        val unvalued = data.allItems.filter { it.purchase_price == null }
        val photoCounts = if (withPhotos) OwnershipPhotoService.countByMediaItem() else emptyMap()

        val out = ByteArrayOutputStream()
        val document = Document(PageSize.LETTER, 36f, 36f, 36f, 36f)
        PdfWriter.getInstance(document, out)
        document.open()

        val titleFont = Font(Font.HELVETICA, 18f, Font.BOLD)
        val sectionFont = Font(Font.HELVETICA, 14f, Font.BOLD)
        val sellerFont = Font(Font.HELVETICA, 11f, Font.BOLD)
        val normalFont = Font(Font.HELVETICA, 9f, Font.NORMAL)
        val boldFont = Font(Font.HELVETICA, 9f, Font.BOLD)
        val headerFont = Font(Font.HELVETICA, 9f, Font.BOLD, Color.WHITE)
        val subtotalFont = Font(Font.HELVETICA, 10f, Font.BOLD)
        val totalFont = Font(Font.HELVETICA, 12f, Font.BOLD)
        val summaryLabelFont = Font(Font.HELVETICA, 10f, Font.NORMAL, Color(80, 80, 80))
        val summaryValueFont = Font(Font.HELVETICA, 10f, Font.BOLD)

        // Report title
        val titlePara = Paragraph("Insurance Inventory Report", titleFont)
        titlePara.alignment = Element.ALIGN_CENTER
        document.add(titlePara)

        val datePara = Paragraph(
            "Generated: ${LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))}",
            normalFont
        )
        datePara.alignment = Element.ALIGN_CENTER
        datePara.spacingAfter = 16f
        document.add(datePara)

        // === Executive Summary ===
        val summarySection = Paragraph("Executive Summary", sectionFont)
        summarySection.spacingAfter = 8f
        document.add(summarySection)

        val totalItems = data.allItems.size
        val grandTotal = valued.mapNotNull { it.purchase_price }.fold(BigDecimal.ZERO, BigDecimal::add)
        val replacementTotal = data.allItems.mapNotNull { it.replacement_value }.fold(BigDecimal.ZERO, BigDecimal::add)
        val itemsWithReplacement = data.allItems.count { it.replacement_value != null }

        // Format breakdown
        val formatCounts = data.allItems.groupBy { it.media_format }
        val formatBreakdown = listOf("DVD", "BLURAY", "UHD_BLURAY", "HD_DVD")
            .mapNotNull { fmt ->
                val count = formatCounts[fmt]?.size ?: 0
                if (count > 0) "${count} ${fmt.replace("_", " ")}${if (count != 1) "s" else ""}" else null
            }

        val summaryTable = PdfPTable(2).apply {
            widthPercentage = 70f
            horizontalAlignment = Element.ALIGN_LEFT
            setWidths(floatArrayOf(2f, 3f))
        }

        fun addSummaryRow(label: String, value: String) {
            val labelCell = PdfPCell(Phrase(label, summaryLabelFont)).apply {
                border = Rectangle.NO_BORDER; setPadding(3f)
            }
            val valueCell = PdfPCell(Phrase(value, summaryValueFont)).apply {
                border = Rectangle.NO_BORDER; setPadding(3f)
            }
            summaryTable.addCell(labelCell)
            summaryTable.addCell(valueCell)
        }

        addSummaryRow("Total items:", "$totalItems")
        if (formatBreakdown.isNotEmpty()) {
            addSummaryRow("Format breakdown:", formatBreakdown.joinToString(", "))
        }
        addSummaryRow("Documented value:", "\$${grandTotal.setScale(2)}")
        if (valued.isNotEmpty()) {
            val avgPrice = grandTotal.divide(BigDecimal(valued.size), 2, java.math.RoundingMode.HALF_UP)
            addSummaryRow("Average price per item:", "\$${avgPrice}")
        }
        val coveragePct = if (totalItems > 0) (valued.size * 100) / totalItems else 0
        addSummaryRow("Price coverage:", "${valued.size} of $totalItems items ($coveragePct%)")

        if (itemsWithReplacement > 0) {
            addSummaryRow("Replacement value:", "\$${replacementTotal.setScale(2)} ($itemsWithReplacement items)")
        }

        // Evidence coverage
        val allPhotoCounts = OwnershipPhotoService.countByMediaItem()
        val itemsWithEvidence = allPhotoCounts.keys.intersect(data.allItems.mapNotNull { it.id }.toSet()).size
        val totalPhotos = allPhotoCounts.values.sum()
        if (totalPhotos > 0) {
            val evidencePct = if (totalItems > 0) (itemsWithEvidence * 100) / totalItems else 0
            addSummaryRow("Evidence coverage:", "$itemsWithEvidence of $totalItems items ($evidencePct%) — $totalPhotos photos")
        }

        // Date range
        val dates = valued.mapNotNull { it.purchase_date }
        if (dates.isNotEmpty()) {
            val earliest = dates.min()
            val latest = dates.max()
            val fmt = DateTimeFormatter.ofPattern("MMM yyyy")
            addSummaryRow("Date range:", "${earliest.format(fmt)} \u2013 ${latest.format(fmt)}")
        }

        // Top retailers
        val topRetailers = valued.groupBy { it.purchase_place ?: "Unknown" }
            .entries.sortedByDescending { it.value.size }
            .take(5)
            .joinToString(", ") { "${it.key} (${it.value.size})" }
        if (topRetailers.isNotEmpty()) {
            addSummaryRow("Top retailers:", topRetailers)
        }

        document.add(summaryTable)

        // === Gap Analysis ===
        if (unvalued.isNotEmpty()) {
            val gapSection = Paragraph("\nValuation Gap Analysis", sectionFont)
            gapSection.spacingBefore = 12f
            gapSection.spacingAfter = 8f
            document.add(gapSection)

            val gapTable = PdfPTable(2).apply {
                widthPercentage = 70f
                horizontalAlignment = Element.ALIGN_LEFT
                setWidths(floatArrayOf(2f, 3f))
            }

            fun addGapRow(label: String, value: String) {
                val labelCell = PdfPCell(Phrase(label, summaryLabelFont)).apply {
                    border = Rectangle.NO_BORDER; setPadding(3f)
                }
                val valueCell = PdfPCell(Phrase(value, summaryValueFont)).apply {
                    border = Rectangle.NO_BORDER; setPadding(3f)
                }
                gapTable.addCell(labelCell)
                gapTable.addCell(valueCell)
            }

            addGapRow("Unpriced items:", "${unvalued.size} of $totalItems")

            // Estimate gap value using format-average pricing
            val avgByFormat = valued.groupBy { it.media_format }
                .mapValues { (_, items) ->
                    val prices = items.mapNotNull { it.purchase_price }
                    if (prices.isNotEmpty()) prices.fold(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal(prices.size), 2, java.math.RoundingMode.HALF_UP)
                    else null
                }

            val overallAvg = if (valued.isNotEmpty())
                grandTotal.divide(BigDecimal(valued.size), 2, java.math.RoundingMode.HALF_UP)
            else BigDecimal.ZERO

            val gapByFormat = unvalued.groupBy { it.media_format }
            var totalEstGap = BigDecimal.ZERO
            val gapFormatLines = mutableListOf<String>()
            var bestFormatKey: String? = null
            var bestFormatDisplay: String? = null
            var bestFormatValue = BigDecimal.ZERO

            for ((fmt, items) in gapByFormat) {
                val avg = avgByFormat[fmt] ?: overallAvg
                val est = avg.multiply(BigDecimal(items.size))
                totalEstGap += est
                val displayFmt = fmt.replace("_", " ")
                gapFormatLines.add("${items.size} $displayFmt (~\$${est.setScale(2)})")
                if (est > bestFormatValue) {
                    bestFormatValue = est
                    bestFormatKey = fmt
                    bestFormatDisplay = displayFmt
                }
            }

            if (gapFormatLines.isNotEmpty()) {
                addGapRow("Unpriced by format:", gapFormatLines.joinToString(", "))
            }
            addGapRow("Estimated gap value:", "~\$${totalEstGap.setScale(2)}")
            addGapRow("Est. total with gap:", "\$${grandTotal.setScale(2)} \u2013 \$${(grandTotal + totalEstGap).setScale(2)}")

            document.add(gapTable)

            if (bestFormatKey != null && gapByFormat.size > 1) {
                val guidancePara = Paragraph(
                    "Tip: Adding prices for the ${gapByFormat[bestFormatKey]?.size ?: ""} " +
                            "unpriced ${bestFormatDisplay}s would add the most estimated value (~\$${bestFormatValue.setScale(2)}).",
                    Font(Font.HELVETICA, 9f, Font.ITALIC, Color(100, 100, 100))
                )
                guidancePara.spacingBefore = 4f
                document.add(guidancePara)
            }
        }

        // Spacing before detail sections
        document.add(Paragraph(" ").apply { spacingAfter = 12f })

        // Section 1: Purchases with Valuations — grouped by seller
        val sec1 = Paragraph("Purchases with Valuations", sectionFont)
        sec1.spacingAfter = 8f
        document.add(sec1)

        if (valued.isEmpty()) {
            document.add(Paragraph("No purchases with valuations.", normalFont))
        } else {
            val bySeller = valued.groupBy { it.purchase_place ?: "Unknown" }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)

            var runningTotal = BigDecimal.ZERO

            for ((seller, items) in bySeller) {
                val sorted = items.sortedBy { sortKey(data.titleNames(it)) }

                val sellerPara = Paragraph(seller, sellerFont)
                sellerPara.spacingBefore = 10f
                sellerPara.spacingAfter = 4f
                document.add(sellerPara)

                val hasOrders = sorted.any { it.amazon_order_id != null }
                val hasReplacement = sorted.any { it.replacement_value != null }
                val table = createValuedTable(sorted, data, headerFont, normalFont, boldFont, hasOrders, hasReplacement, photoCounts)
                document.add(table)

                val subtotal = sorted.mapNotNull { it.purchase_price }.fold(BigDecimal.ZERO, BigDecimal::add)
                runningTotal += subtotal
                val subtotalText = "Subtotal: \$${subtotal.setScale(2)}"
                val replSubtotal = sorted.mapNotNull { it.replacement_value }.fold(BigDecimal.ZERO, BigDecimal::add)
                val displaySubtotal = if (hasReplacement && replSubtotal > BigDecimal.ZERO)
                    "$subtotalText  (Replacement: \$${replSubtotal.setScale(2)})"
                else subtotalText
                val subtotalPara = Paragraph(displaySubtotal, subtotalFont)
                subtotalPara.alignment = Element.ALIGN_RIGHT
                subtotalPara.spacingBefore = 4f
                document.add(subtotalPara)
            }

            val totalPara = Paragraph("Total Purchase Value: \$${grandTotal.setScale(2)}", totalFont)
            totalPara.alignment = Element.ALIGN_RIGHT
            totalPara.spacingBefore = 12f
            if (replacementTotal > BigDecimal.ZERO) {
                totalPara.spacingAfter = 2f
                document.add(totalPara)
                val replTotalPara = Paragraph("Total Replacement Value: \$${replacementTotal.setScale(2)} ($itemsWithReplacement items)", subtotalFont)
                replTotalPara.alignment = Element.ALIGN_RIGHT
                replTotalPara.spacingAfter = 20f
                document.add(replTotalPara)
            } else {
                totalPara.spacingAfter = 20f
                document.add(totalPara)
            }
        }

        // Section 2: Purchases without Valuations
        val sec2 = Paragraph("Purchases without Valuations", sectionFont)
        sec2.spacingBefore = 16f
        sec2.spacingAfter = 8f
        document.add(sec2)

        if (unvalued.isEmpty()) {
            document.add(Paragraph("All purchases have valuations.", normalFont))
        } else {
            val sorted = unvalued.sortedBy { sortKey(data.titleNames(it)) }
            val table = createUnvaluedTable(sorted, data, headerFont, normalFont, boldFont, photoCounts)
            document.add(table)
        }

        document.close()
        return out.toByteArray()
    }

    private fun createValuedTable(
        items: List<MediaItem>,
        data: ReportData,
        headerFont: Font,
        normalFont: Font,
        boldFont: Font,
        includeOrderNumber: Boolean,
        includeReplacement: Boolean = false,
        photoCounts: Map<Long, Int> = emptyMap()
    ): PdfPTable {
        var colCount = 5
        if (includeOrderNumber) colCount++
        if (includeReplacement) colCount++
        val table = PdfPTable(colCount)
        table.widthPercentage = 100f

        val widths = mutableListOf(3f, 1f, 1.2f, 1.5f)
        if (includeOrderNumber) widths.add(1.5f)
        widths.add(1f) // purchase price
        if (includeReplacement) widths.add(1f) // replacement value
        table.setWidths(widths.toFloatArray())

        val headerBg = Color(51, 51, 51)
        fun addHeader(text: String) {
            val cell = PdfPCell(Phrase(text, headerFont))
            cell.backgroundColor = headerBg
            cell.setPadding(5f)
            table.addCell(cell)
        }

        addHeader("Title(s)")
        addHeader("Format")
        addHeader("UPC")
        addHeader("Purchase Date")
        if (includeOrderNumber) addHeader("Order #")
        addHeader("Purchase Price")
        if (includeReplacement) addHeader("Repl. Value")

        val altBg = Color(245, 245, 245)
        items.forEachIndexed { index, item ->
            val bg = if (index % 2 == 0) null else altBg

            fun addCell(text: String, font: Font = normalFont, align: Int = Element.ALIGN_LEFT) {
                val cell = PdfPCell(Phrase(text, font))
                cell.setPadding(4f)
                cell.horizontalAlignment = align
                if (bg != null) cell.backgroundColor = bg
                table.addCell(cell)
            }

            addCell(data.titleNames(item), boldFont)
            addCell(item.media_format.replace("_", " "))
            addCell(item.upc ?: "")
            addCell(item.purchase_date?.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")) ?: "")
            if (includeOrderNumber) addCell(item.amazon_order_id ?: "")
            addCell("\$${item.purchase_price!!.setScale(2)}", normalFont, Element.ALIGN_RIGHT)
            if (includeReplacement) {
                addCell(item.replacement_value?.let { "\$${it.setScale(2)}" } ?: "", normalFont, Element.ALIGN_RIGHT)
            }

            // Photo row (if photos are included and this item has evidence)
            if (photoCounts.containsKey(item.id)) {
                addPhotoRow(table, colCount, item.id!!, bg)
            }
        }

        return table
    }

    private fun createUnvaluedTable(
        items: List<MediaItem>,
        data: ReportData,
        headerFont: Font,
        normalFont: Font,
        boldFont: Font,
        photoCounts: Map<Long, Int> = emptyMap()
    ): PdfPTable {
        val table = PdfPTable(4)
        table.widthPercentage = 100f
        table.setWidths(floatArrayOf(4f, 1f, 1.5f, 1.5f))

        val headerBg = Color(51, 51, 51)
        fun addHeader(text: String) {
            val cell = PdfPCell(Phrase(text, headerFont))
            cell.backgroundColor = headerBg
            cell.setPadding(5f)
            table.addCell(cell)
        }

        addHeader("Title(s)")
        addHeader("Format")
        addHeader("UPC")
        addHeader("Purchase Place")

        val altBg = Color(245, 245, 245)
        items.forEachIndexed { index, item ->
            val bg = if (index % 2 == 0) null else altBg

            fun addCell(text: String, font: Font = normalFont, align: Int = Element.ALIGN_LEFT) {
                val cell = PdfPCell(Phrase(text, font))
                cell.setPadding(4f)
                cell.horizontalAlignment = align
                if (bg != null) cell.backgroundColor = bg
                table.addCell(cell)
            }

            addCell(data.titleNames(item), boldFont)
            addCell(item.media_format.replace("_", " "))
            addCell(item.upc ?: "")
            addCell(item.purchase_place ?: "")

            if (photoCounts.containsKey(item.id)) {
                addPhotoRow(table, 4, item.id!!, bg)
            }
        }

        return table
    }

    private fun addPhotoRow(table: PdfPTable, colCount: Int, mediaItemId: Long, bg: Color?) {
        val photos = OwnershipPhotoService.findByMediaItem(mediaItemId)
        if (photos.isEmpty()) return

        val photoCell = PdfPCell().apply {
            colspan = colCount
            setPadding(4f)
            border = Rectangle.BOX
            borderWidth = 0.5f
            borderColor = Color(200, 200, 200)
            if (bg != null) backgroundColor = bg
        }

        // Build a sub-table to hold photos in a row
        val photoTable = PdfPTable(photos.size.coerceAtMost(6)).apply {
            widthPercentage = 100f
        }

        val targetHeight = 72f // 1 inch

        for (photo in photos.take(6)) {
            val file = OwnershipPhotoService.getFile(photo.id!!)
            if (file != null && file.exists()) {
                try {
                    val img = applyOrientation(file, photo.orientation)
                    val scale = targetHeight / img.height
                    img.scaleAbsolute(img.width * scale, targetHeight)
                    val imgCell = PdfPCell(img).apply {
                        border = Rectangle.NO_BORDER
                        setPadding(2f)
                        horizontalAlignment = Element.ALIGN_LEFT
                        verticalAlignment = Element.ALIGN_MIDDLE
                    }
                    photoTable.addCell(imgCell)
                } catch (_: Exception) {
                    val fallbackCell = PdfPCell(Phrase("[photo]", Font(Font.HELVETICA, 8f, Font.ITALIC, Color(150, 150, 150)))).apply {
                        border = Rectangle.NO_BORDER
                        setPadding(2f)
                    }
                    photoTable.addCell(fallbackCell)
                }
            }
        }

        // Pad remaining cells if fewer than column count
        val remaining = photos.size.coerceAtMost(6) - photos.take(6).count { OwnershipPhotoService.getFile(it.id!!)?.exists() == true || true }
        // PdfPTable requires all cells to be filled — but we already added one per photo above

        photoCell.addElement(photoTable)
        table.addCell(photoCell)
    }

    /**
     * Load an image and apply EXIF orientation correction for PDF embedding.
     * Browsers handle EXIF orientation automatically, but PDF libraries don't.
     * EXIF orientation values:
     *   1 = normal, 2 = flip H, 3 = 180°, 4 = flip V,
     *   5 = transpose, 6 = 90° CW, 7 = transverse, 8 = 90° CCW
     */
    private fun applyOrientation(file: java.io.File, orientation: Int): com.lowagie.text.Image {
        if (orientation == 1 || orientation == 0) {
            return com.lowagie.text.Image.getInstance(file.absolutePath)
        }

        val original = ImageIO.read(file) ?: return com.lowagie.text.Image.getInstance(file.absolutePath)
        val w = original.width
        val h = original.height

        val (newW, newH) = when (orientation) {
            6, 8, 5, 7 -> h to w  // 90° rotations swap dimensions
            else -> w to h
        }

        val rotated = BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
        val g = rotated.createGraphics()
        val tx = AffineTransform()

        when (orientation) {
            2 -> { tx.scale(-1.0, 1.0); tx.translate(-w.toDouble(), 0.0) }           // flip H
            3 -> { tx.translate(w.toDouble(), h.toDouble()); tx.rotate(Math.PI) }     // 180°
            4 -> { tx.scale(1.0, -1.0); tx.translate(0.0, -h.toDouble()) }           // flip V
            5 -> { tx.rotate(Math.PI / 2); tx.scale(1.0, -1.0) }                     // transpose
            6 -> { tx.translate(h.toDouble(), 0.0); tx.rotate(Math.PI / 2) }         // 90° CW
            7 -> { tx.translate(h.toDouble(), w.toDouble()); tx.rotate(Math.PI / 2); tx.scale(1.0, -1.0); tx.translate(0.0, -w.toDouble()) }  // transverse
            8 -> { tx.translate(0.0, w.toDouble()); tx.rotate(-Math.PI / 2) }        // 90° CCW
        }

        g.drawImage(original, tx, null)
        g.dispose()

        val baos = java.io.ByteArrayOutputStream()
        ImageIO.write(rotated, "jpg", baos)
        return com.lowagie.text.Image.getInstance(baos.toByteArray())
    }
}
